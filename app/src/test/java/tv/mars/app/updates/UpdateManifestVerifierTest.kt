package tv.mars.app.updates

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

class UpdateManifestVerifierTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pem = "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(keys.public.encoded)}\n-----END PUBLIC KEY-----"
    private val certificate = "ab".repeat(32)
    private val release = UpdateManifest("https://marstv.online", "tv.mars.app:direct-update", "tv.mars.app", certificate,
        "direct-stable", 3, "0.3.0", 1, "recommended", "https://marstv.online/downloads/MarsTV-0.3.0.apk",
        "cd".repeat(32), 1000, listOf("Update support"), "2026-09-10T00:00:00Z")
    private val now = Instant.parse("2026-09-11T00:00:00Z")
    private fun verifier() = UpdateManifestVerifier(pem, "release-2026-01", certificate)

    @Test fun `valid signed release is accepted`() { assertEquals(release, verifier().verify(token(), now)) }
    @Test fun `Python release tool signatures verify in the Android client`() {
        fun resource(name: String) = javaClass.getResourceAsStream("/updates/$name")!!.bufferedReader().use { it.readText() }
        val result = UpdateManifestVerifier(resource("python-public-key.txt"), "release-2026-01", certificate)
            .verify(resource("python-signed.jws"), now)
        assertEquals(3L, result.versionCode)
        assertEquals(listOf("Télévision update"), result.releaseNotes)
    }
    @Test fun `tampered bytes are rejected`() {
        val parts = token().split('.')
        reject("${parts[0]}.${b64(Json.encodeToString(release.copy(versionCode = 4)).toByteArray())}.${parts[2]}")
    }
    @Test fun `wrong audience package channel and certificate are rejected`() {
        listOf(release.copy(aud = "tv.mars.app:direct"), release.copy(packageId = "other.app"),
            release.copy(channel = "play"), release.copy(signingCertificateSha256 = "ef".repeat(32))).forEach { reject(token(it)) }
    }
    @Test fun `invalid header algorithm type and kid are rejected`() {
        listOf("""{"alg":"none","kid":"release-2026-01","typ":"marstv-update+jws"}""",
            """{"alg":"ES256","kid":"other","typ":"marstv-update+jws"}""",
            """{"alg":"ES256","kid":"release-2026-01","typ":"marstv-entitlement+jwt"}""").forEach { reject(token(header = it)) }
    }
    @Test fun `untrusted download locations are rejected`() {
        listOf("http://marstv.online/downloads/a.apk", "https://evil.example/a.apk", "https://marstv.online:444/downloads/a.apk",
            "https://marstv.online/downloads/../a.apk", "https://user@marstv.online/downloads/a.apk",
            "https://marstv.online/downloads/a.apk?redirect=1").forEach { reject(token(release.copy(apkUrl = it))) }
    }
    @Test fun `oversized future and malformed releases are rejected`() {
        listOf(release.copy(sizeBytes = UpdateManifestVerifier.MAX_APK_BYTES + 1), release.copy(sizeBytes = 0),
            release.copy(versionCode = 0), release.copy(minimumSupportedVersionCode = 4), release.copy(sha256 = "bad"),
            release.copy(publishedAt = now.plusSeconds(301).toString())).forEach { reject(token(it)) }
    }
    @Test fun `missing key fails closed`() { assertFalse(UpdateManifestVerifier("", "release-2026-01", certificate).configured) }
    @Test fun `malformed or oversized compact token is rejected`() { listOf("abc.def", "a".repeat(32769), "a.b.c=").forEach(::reject) }

    private fun reject(value: String) { assertTrue(runCatching { verifier().verify(value, now) }.isFailure) }
    private fun token(manifest: UpdateManifest = release, header: String = """{"alg":"ES256","kid":"release-2026-01","typ":"marstv-update+jws"}"""): String {
        val input = b64(header.toByteArray()) + "." + b64(Json.encodeToString(manifest).toByteArray())
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(input.toByteArray()) }
        val der = signer.sign()
        var offset = 2
        require(der[offset++] == 2.toByte())
        val rSize = der[offset++].toInt()
        val r = der.copyOfRange(offset, offset + rSize).dropWhile { it == 0.toByte() }.toByteArray()
        offset += rSize
        require(der[offset++] == 2.toByte())
        val sSize = der[offset++].toInt()
        val s = der.copyOfRange(offset, offset + sSize).dropWhile { it == 0.toByte() }.toByteArray()
        val raw = ByteArray(64)
        r.copyInto(raw, 32 - r.size); s.copyInto(raw, 64 - s.size)
        return "$input.${b64(raw)}"
    }
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
