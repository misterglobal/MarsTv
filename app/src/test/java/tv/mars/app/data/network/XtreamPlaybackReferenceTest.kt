package tv.mars.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.SourceType

class XtreamPlaybackReferenceTest {
    private val account = IptvAccount(
        name = "Test",
        sourceType = SourceType.XTREAM,
        serverUrl = "https://provider.example",
        username = "user@example.com",
        password = "secret value",
    )

    @Test
    fun `reference excludes credentials and resolves only at playback`() {
        val reference = xtreamPlaybackReference("movie", "42", "mkv")

        assertFalse(reference.contains(account.username))
        assertFalse(reference.contains(account.password))
        assertEquals(
            "https://provider.example/movie/user%40example.com/secret%20value/42.mkv",
            resolveXtreamPlaybackReference(account, reference),
        )
    }

    @Test
    fun `ordinary M3U URL remains unchanged`() {
        val url = "https://playlist.example/channel.ts"

        assertEquals(url, resolveXtreamPlaybackReference(account, url))
    }
}
