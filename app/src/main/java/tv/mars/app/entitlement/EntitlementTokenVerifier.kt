package tv.mars.app.entitlement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

data class VerifiedEntitlement(
    val state: EntitlementState.Pro,
    val issuedAtEpochSeconds: Long,
    val tokenVersion: Int,
)

data class VerifiedRevocation(
    val deviceId: String,
    val licenseId: String,
    val licenseVersion: Long,
    val reasonCode: String,
)

class EntitlementTokenVerifier private constructor(private val keys: Map<String, PublicKey>) {
    private val json = Json { ignoreUnknownKeys = false }

    fun verifyCached(token: String, deviceUuid: String, keyThumbprint: String): Result<VerifiedEntitlement> =
        verify(token, deviceUuid, keyThumbprint, serverTimeEpochSeconds = null)

    fun verifyReceived(
        token: String,
        deviceUuid: String,
        keyThumbprint: String,
        serverTimeEpochSeconds: Long,
    ): Result<VerifiedEntitlement> = verify(token, deviceUuid, keyThumbprint, serverTimeEpochSeconds)

    fun verifyRevocation(
        token: String,
        deviceUuid: String,
        serverTimeEpochSeconds: Long,
    ): Result<VerifiedRevocation> = runCatching {
        val payload = verifySignatureAndPayload(token, REVOCATION_TYPE)
        require(payload.string("iss") == ISSUER && payload.string("aud") == AUDIENCE)
        require(payload.string("sub") == deviceUuid) { "Revocation belongs to another device" }
        require(payload.string("status") == "revoked")
        require(payload.long("token_version").toInt() == TOKEN_VERSION)
        require(payload.long("iat") <= serverTimeEpochSeconds + MAX_FUTURE_IAT_SECONDS)
        VerifiedRevocation(
            deviceId = deviceUuid,
            licenseId = payload.string("license_id"),
            licenseVersion = payload.long("license_version"),
            reasonCode = payload.string("reason_code"),
        )
    }

    private fun verify(
        token: String,
        deviceUuid: String,
        keyThumbprint: String,
        serverTimeEpochSeconds: Long?,
    ): Result<VerifiedEntitlement> = runCatching {
        val payload = verifySignatureAndPayload(token, ENTITLEMENT_TYPE)
        require(payload.string("iss") == ISSUER) { "Wrong entitlement issuer" }
        require(payload.string("aud") == AUDIENCE) { "Wrong entitlement audience" }
        require(payload.string("sub") == deviceUuid) { "Entitlement belongs to another device" }
        require(payload.string("device_key_thumbprint") == keyThumbprint) { "Entitlement key binding failed" }
        val tokenVersion = payload.long("token_version").toInt()
        require(tokenVersion == TOKEN_VERSION) { "Unsupported entitlement token version" }
        val issuedAt = payload.long("iat")
        if (serverTimeEpochSeconds != null) {
            require(issuedAt <= serverTimeEpochSeconds + MAX_FUTURE_IAT_SECONDS) { "Entitlement issued too far in the future" }
        }
        val featuresById = ProFeature.entries.associateBy(ProFeature::id)
        val features = payload.getValue("features").jsonArray.mapNotNull { item ->
            featuresById[item.jsonPrimitive.content]
        }.toSet()
        VerifiedEntitlement(
            state = EntitlementState.Pro(
                planId = payload.string("plan_id"),
                features = features,
                deviceId = deviceUuid,
                licenseId = payload.string("license_id"),
                licenseVersion = payload.long("license_version"),
                refreshAfter = Instant.ofEpochSecond(payload.long("refresh_after")),
            ),
            issuedAtEpochSeconds = issuedAt,
            tokenVersion = tokenVersion,
        )
    }

    private fun verifySignatureAndPayload(token: String, expectedType: String): JsonObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Token must use compact JWS serialization" }
        val header = parseObject(parts[0])
        require(header.keys == setOf("alg", "kid", "typ")) { "Unexpected token header" }
        require(header.string("alg") == "ES256") { "Unsupported token algorithm" }
        require(header.string("typ") == expectedType) { "Unexpected token type" }
        val key = keys[header.string("kid")] ?: error("Unknown signing key")
        val joseSignature = decodeBase64Url(parts[2])
        require(joseSignature.size == 64) { "Invalid ES256 signature length" }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        require(verifier.verify(joseToDer(joseSignature))) { "Invalid token signature" }
        return parseObject(parts[1])
    }

    private fun parseObject(encoded: String): JsonObject =
        json.parseToJsonElement(String(decodeBase64Url(encoded), StandardCharsets.UTF_8)) as JsonObject

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    companion object {
        const val ISSUER = "https://marstv.online"
        const val AUDIENCE = "tv.mars.app:direct"
        const val ENTITLEMENT_TYPE = "marstv-entitlement+jwt"
        const val REVOCATION_TYPE = "marstv-revocation+jwt"
        const val TOKEN_VERSION = 1
        const val MAX_FUTURE_IAT_SECONDS = 300L

        fun fromPem(pem: String, kid: String = "entitlement-2026-01"): EntitlementTokenVerifier {
            if (pem.isBlank()) return EntitlementTokenVerifier(emptyMap())
            val key = runCatching {
                val der = Base64.getDecoder().decode(
                    pem.replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .filterNot(Char::isWhitespace),
                )
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
            }.getOrNull() ?: return EntitlementTokenVerifier(emptyMap())
            return EntitlementTokenVerifier(mapOf(kid to key))
        }

        internal fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

        internal fun joseToDer(jose: ByteArray): ByteArray {
            require(jose.size == 64)
            fun integer(bytes: ByteArray): ByteArray {
                val unsigned = bytes.dropWhile { it == 0.toByte() }.toByteArray()
                val stripped = if (unsigned.isEmpty()) byteArrayOf(0) else unsigned
                return if (stripped[0].toInt() and 0x80 != 0) byteArrayOf(0) + stripped else stripped
            }
            val r = integer(jose.copyOfRange(0, 32))
            val s = integer(jose.copyOfRange(32, 64))
            val length = 2 + r.size + 2 + s.size
            require(length < 128)
            return byteArrayOf(0x30, length.toByte(), 0x02, r.size.toByte()) + r +
                byteArrayOf(0x02, s.size.toByte()) + s
        }
    }
}
