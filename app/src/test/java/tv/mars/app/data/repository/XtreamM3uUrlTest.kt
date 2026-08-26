package tv.mars.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.SourceType

class XtreamM3uUrlTest {
    @Test
    fun extractsXtreamCredentialsFromGetPhpUrl() {
        val account = IptvAccount(
            id = "account",
            name = "Test",
            sourceType = SourceType.M3U,
            m3uUrl = "http://example.com:8080/iptv/get.php?username=user%40mail.com&password=p%2Bass&type=m3u_plus&output=ts",
        )

        val result = account.xtreamAccountFromM3u()!!

        assertEquals(SourceType.XTREAM, result.sourceType)
        assertEquals("http://example.com:8080/iptv", result.serverUrl)
        assertEquals("user@mail.com", result.username)
        assertEquals("p+ass", result.password)
        assertEquals(account.id, result.id)
    }

    @Test
    fun ignoresGenericM3uUrls() {
        val account = IptvAccount(
            name = "Test",
            sourceType = SourceType.M3U,
            m3uUrl = "https://example.com/playlist.m3u",
        )

        assertNull(account.xtreamAccountFromM3u())
    }
}
