package tv.mars.app.entitlement

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.charset.StandardCharsets

class DeviceChallengeTest {
    @Test
    fun `challenge bytes use exact line order without trailing newline`() {
        val challenge = DeviceChallenge("challenge", "nonce", "device", "POST", "/api/v1/status", "body", 123)
        val expected = "MARSTV_DEVICE_AUTH_V1\nchallenge\nnonce\ndevice\nPOST\n/api/v1/status\nbody\n123"

        assertArrayEquals(expected.toByteArray(StandardCharsets.UTF_8), challenge.signingBytes())
        assertFalse(challenge.signingBytes().last() == '\n'.code.toByte())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `challenge rejects non-canonical path`() {
        DeviceChallenge("challenge", "nonce", "device", "POST", "https://evil.test/status", "body", 123)
    }
}
