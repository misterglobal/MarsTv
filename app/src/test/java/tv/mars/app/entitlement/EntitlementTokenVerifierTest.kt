package tv.mars.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class EntitlementTokenVerifierTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val publicPem = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.public.encoded) +
        "\n-----END PUBLIC KEY-----"

    @Test
    fun `valid device-bound lifetime entitlement verifies`() {
        val result = verifier().verifyReceived(token(), DEVICE, THUMBPRINT, serverTimeEpochSeconds = 1_000)

        assertTrue(result.isSuccess)
        assertEquals(7, result.getOrThrow().state.licenseVersion)
        assertTrue(ProFeature.FULL_EPG in result.getOrThrow().state.features)
    }

    @Test
    fun `copied token and future token fail safely`() {
        assertTrue(verifier().verifyReceived(token(), "other-device", THUMBPRINT, 1_000).isFailure)
        assertTrue(verifier().verifyReceived(token(iat = 1_301), DEVICE, THUMBPRINT, 1_000).isFailure)
        assertTrue(verifier().verifyReceived(token(iat = 1_300), DEVICE, THUMBPRINT, 1_000).isSuccess)
    }

    @Test
    fun `cached lifetime token has no wall clock age check`() {
        assertTrue(verifier().verifyCached(token(iat = 1), DEVICE, THUMBPRINT).isSuccess)
    }

    @Test
    fun `signed revocation is device bound and uses a distinct type`() {
        val payload = """{"iss":"https://marstv.online","aud":"tv.mars.app:direct","sub":"$DEVICE","license_id":"license","license_version":8,"status":"revoked","iat":900,"reason_code":"refund","token_version":1}"""
        val revocation = signedToken("marstv-revocation+jwt", payload)

        assertEquals(8, verifier().verifyRevocation(revocation, DEVICE, 1_000).getOrThrow().licenseVersion)
        assertTrue(verifier().verifyRevocation(revocation, "other-device", 1_000).isFailure)
        assertTrue(verifier().verifyReceived(revocation, DEVICE, THUMBPRINT, 1_000).isFailure)
    }

    private fun verifier() = EntitlementTokenVerifier.fromPem(publicPem)

    private fun token(iat: Long = 900): String {
        val payload = """{"iss":"https://marstv.online","aud":"tv.mars.app:direct","sub":"$DEVICE","license_id":"license","license_version":7,"device_key_thumbprint":"$THUMBPRINT","plan_id":"pro_lifetime_v1","features":["full_epg"],"iat":$iat,"refresh_after":2000,"token_version":1}"""
        return signedToken("marstv-entitlement+jwt", payload)
    }

    private fun signedToken(type: String, payload: String): String {
        val header = """{"alg":"ES256","kid":"entitlement-2026-01","typ":"$type"}"""
        val signingInput = "${b64(header.toByteArray())}.${b64(payload.toByteArray())}"
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(signingInput.toByteArray(StandardCharsets.US_ASCII))
        return "$signingInput.${b64(derToJose(signer.sign()))}"
    }

    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 2
        require(der[0] == 0x30.toByte())
        require(der[offset++] == 0x02.toByte())
        val rLength = der[offset++].toInt()
        val r = der.copyOfRange(offset, offset + rLength).dropWhile { it == 0.toByte() }.toByteArray()
        offset += rLength
        require(der[offset++] == 0x02.toByte())
        val sLength = der[offset++].toInt()
        val s = der.copyOfRange(offset, offset + sLength).dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(64).also {
            r.copyInto(it, 32 - r.size)
            s.copyInto(it, 64 - s.size)
        }
    }

    private fun b64(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private companion object {
        const val DEVICE = "9c0f6d3b-6ea7-4d9f-a81c-4d7f7e843d4b"
        const val THUMBPRINT = "test-thumbprint"
    }
}
