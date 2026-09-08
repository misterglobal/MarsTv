package tv.mars.app.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
