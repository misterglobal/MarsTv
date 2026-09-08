package tv.mars.app.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.mars.app.data.network.ActivationSession
import java.time.Instant

class ActivationSessionTest {
    @Test
    fun `valid session exposes only public activation details`() {
        val ready = session().toActivationState(DEVICE_ID)

        assertEquals("MARS-72Q8", ready.deviceCode)
        assertEquals("ABCD-2345", ready.activationCode)
        assertEquals("https://marstv.online/activate", ready.activationUrl)
    }

    @Test
    fun `session for another device is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { session().toActivationState("other-device") }
    }

    @Test
    fun `non MarsTV activation links are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            session(activationUrl = "https://evil.example/activate").toActivationState(DEVICE_ID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            session(qrPayload = "http://marstv.online/activate?s=secret").toActivationState(DEVICE_ID)
        }
    }

    @Test
    fun `expired session is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            session(expiresAt = Instant.now().minusSeconds(1).toString()).toActivationState(DEVICE_ID)
        }
    }

    private fun session(
        activationUrl: String = "https://marstv.online/activate",
        qrPayload: String = "https://marstv.online/activate?s=single-use-secret",
        expiresAt: String = Instant.now().plusSeconds(600).toString(),
    ) = ActivationSession(
        deviceId = DEVICE_ID,
        deviceCode = "MARS-72Q8",
        activationSessionId = "session-secret-not-exposed",
        activationCode = "ABCD-2345",
        activationUrl = activationUrl,
        qrPayload = qrPayload,
        expiresAt = expiresAt,
    )

    private companion object { const val DEVICE_ID = "f63573f4-6453-47cc-8167-c9595959c771" }
}
