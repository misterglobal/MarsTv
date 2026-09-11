package tv.mars.app.updates

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.mars.app.entitlement.EntitlementTokenVerifier
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

@Serializable
data class UpdateManifest(
    val iss: String,
    val aud: String,
    val packageId: String,
    val signingCertificateSha256: String,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val minimumSupportedVersionCode: Long,
    val priority: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: List<String>,
    val publishedAt: String,
)

class UpdateManifestVerifier(pem: String, private val kid: String, private val certificate: String) {
    private val json = Json { ignoreUnknownKeys = false }
    private val key = runCatching {
        val der = Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "").filterNot(Char::isWhitespace))
        (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der)) as ECPublicKey).also {
            require(it.params.curve.field.fieldSize == 256)
            require(it.params.order == java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16))
        }
    }.getOrNull()
    val configured: Boolean get() = key != null && certificate.matches(HEX_256)

    fun verify(compact: String, now: Instant = Instant.now()): UpdateManifest {
        require(configured && compact.length in 1..MAX_MANIFEST_BYTES) { "Update verification is not configured" }
        val parts = compact.split('.')
        require(parts.size == 3 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        val header = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)) as JsonObject
        require(header.keys == setOf("alg", "kid", "typ"))
        require(header["alg"]?.jsonPrimitive?.content == "ES256")
        require(header["typ"]?.jsonPrimitive?.content == "marstv-update+jws")
        require(header["kid"]?.jsonPrimitive?.content == kid)
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(EntitlementTokenVerifier.joseToDer(Base64.getUrlDecoder().decode(parts[2])))) { "Invalid update signature" }
        val manifest = json.decodeFromString<UpdateManifest>(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        require(manifest.iss == "https://marstv.online" && manifest.aud == "tv.mars.app:direct-update")
        require(manifest.packageId == "tv.mars.app" && manifest.channel == "direct-stable")
        require(manifest.signingCertificateSha256.matches(HEX_256) && manifest.signingCertificateSha256.equals(certificate, true))
        require(manifest.versionCode in 1..Int.MAX_VALUE && manifest.minimumSupportedVersionCode in 1..manifest.versionCode)
        require(manifest.versionName.isNotBlank() && manifest.versionName.length <= 80)
        require(manifest.priority in setOf("optional", "recommended", "critical"))
        require(manifest.sha256.matches(HEX_256) && manifest.sizeBytes in 1..MAX_APK_BYTES)
        require(manifest.releaseNotes.size <= 30 && manifest.releaseNotes.all { it.length <= 500 })
        require(!Instant.parse(manifest.publishedAt).isAfter(now.plusSeconds(300)))
        require(validApkUrl(manifest.apkUrl)) { "Invalid update download location" }
        return manifest
    }

    companion object {
        const val MAX_MANIFEST_BYTES = 32768
        const val MAX_APK_BYTES = 250L * 1024 * 1024
        private val HEX_256 = Regex("[a-fA-F0-9]{64}")
        fun validApkUrl(url: String): Boolean = runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == "marstv.online" && uri.port in listOf(-1, 443) &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.matches(Regex("/downloads/[A-Za-z0-9][A-Za-z0-9._-]*\\.apk"))
        }.getOrDefault(false)
    }
}
