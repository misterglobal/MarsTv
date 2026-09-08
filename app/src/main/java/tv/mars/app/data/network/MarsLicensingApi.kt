package tv.mars.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tv.mars.app.entitlement.DeviceChallenge
import tv.mars.app.entitlement.DeviceIdentity
import java.io.IOException

@Serializable
data class DeviceRegistrationRequest(
    val devicePublicKeySpki: String,
    val devicePublicKeyThumbprint: String,
    val installationFingerprint: String,
    val platform: String,
    val appVersionCode: Int,
)

@Serializable
data class ActivationSession(
    val deviceId: String,
    val deviceCode: String,
    val activationSessionId: String,
    val activationCode: String,
    val activationUrl: String,
    val qrPayload: String,
    val expiresAt: String,
)

@Serializable
private data class ChallengeRequest(
    val target_method: String,
    val canonical_path: String,
    val body_sha256_b64url: String,
)

@Serializable
private data class ChallengeResponse(
    val challenge_id: String,
    val nonce_b64url: String,
    val expires_at_epoch_seconds: Long,
    val http_method: String,
    val canonical_path: String,
    val body_sha256_b64url: String,
)

@Serializable
data class DeviceStatusResponse(
    val status: String,
    val entitlement: String? = null,
    val revocation: String? = null,
    val server_time: Long,
)

class MarsLicensingApi(private val backend: MarsBackendClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun register(identity: DeviceIdentity, appVersionCode: Int): ActivationSession {
        val request = DeviceRegistrationRequest(
            identity.publicKeySpkiBase64(),
            identity.publicKeyThumbprint(),
            identity.installationFingerprint(),
            identity.platform(),
            appVersionCode,
        )
        val session = post("/api/v1/devices/register", json.encodeToString(request), ActivationSession.serializer())
        identity.adoptRegisteredDeviceUuid(session.deviceId)
        return session
    }

    suspend fun status(identity: DeviceIdentity): DeviceStatusResponse {
        val deviceId = identity.deviceUuid()
        val path = "/api/v1/devices/$deviceId/status"
        return authenticatedPost(identity, path, DeviceStatusResponse.serializer())
    }

    suspend fun createActivationSession(identity: DeviceIdentity): ActivationSession {
        return authenticatedPost(identity, "/api/v1/activation-sessions", ActivationSession.serializer())
    }

    private suspend fun <T> authenticatedPost(
        identity: DeviceIdentity,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T {
        val deviceId = identity.deviceUuid()
        val body = "{}"
        val bodyHash = DeviceIdentity.base64Url(DeviceIdentity.sha256(body.toByteArray(Charsets.UTF_8)))
        val challengeBody = json.encodeToString(ChallengeRequest("POST", path, bodyHash))
        val challengeResponse = post(
            "/api/v1/devices/$deviceId/challenges",
            challengeBody,
            ChallengeResponse.serializer(),
        )
        val challenge = DeviceChallenge(
            challengeResponse.challenge_id,
            challengeResponse.nonce_b64url,
            deviceId,
            challengeResponse.http_method,
            challengeResponse.canonical_path,
            challengeResponse.body_sha256_b64url,
            challengeResponse.expires_at_epoch_seconds,
        )
        require(challenge.httpMethod == "POST" && challenge.canonicalPath == path && challenge.bodySha256Base64Url == bodyHash) {
            "Backend returned a challenge for the wrong request"
        }
        return post(
            path,
            body,
            serializer,
            mapOf(
                "X-Mars-Challenge-Id" to challenge.challengeId,
                "X-Mars-Device-Signature" to identity.signChallenge(challenge),
            ),
        )
    }

    private suspend fun <T> post(
        path: String,
        body: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        require(body.toByteArray().size <= MAX_JSON_BYTES)
        val builder = backend.request(path).post(body.toRequestBody(jsonType))
        headers.forEach(builder::header)
        backend.client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw BackendHttpException(response.code)
            val declaredLength = response.body.contentLength()
            if (declaredLength > MAX_JSON_BYTES) throw IOException("Backend response exceeded limit")
            val responseBytes = response.body.source().readByteArray(MAX_JSON_BYTES.toLong() + 1)
            if (responseBytes.size > MAX_JSON_BYTES) throw IOException("Backend response exceeded limit")
            val responseBody = responseBytes.toString(Charsets.UTF_8)
            json.decodeFromString(serializer, responseBody)
        }
    }

    companion object { const val MAX_JSON_BYTES = 16 * 1024 }
}

class BackendHttpException(val statusCode: Int) : IOException("MarsTV backend returned HTTP $statusCode")
