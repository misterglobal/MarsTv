package tv.mars.app.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class MarsBackendClientTest {
    @Test(expected = IllegalArgumentException::class)
    fun `backend rejects cleartext base URL`() {
        MarsBackendClient("http://marstv.online/api/v1/")
    }

    @Test
    fun `backend disables redirects and stays on configured HTTPS origin`() {
        val backend = MarsBackendClient("https://marstv.online/api/v1/")
        val request = backend.request("/api/v1/devices/register").build()

        assertTrue(request.url.isHttps)
        assertTrue(request.url.host == "marstv.online")
        assertFalse(backend.client.followRedirects)
        assertFalse(backend.client.followSslRedirects)
    }

    @Test
    fun `bounded response reader accepts a body smaller than its limit`() {
        val body = "{\"status\":\"ok\"}".toByteArray()

        assertArrayEquals(body, Buffer().write(body).readLimitedByteArray(16 * 1024))
    }

    @Test(expected = java.io.IOException::class)
    fun `bounded response reader rejects a body larger than its limit`() {
        Buffer().write(ByteArray(17)).readLimitedByteArray(16)
    }
}
