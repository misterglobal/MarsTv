package tv.mars.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.SourceType

class M3uParserTest {
    private val account = IptvAccount(
        id = "account",
        name = "Test",
        sourceType = SourceType.M3U,
        m3uUrl = "https://example.com/list.m3u",
    )

    @Test
    fun parsesStreamWithoutDuplicatingCategoryStringsAndGroupsEpisodes() {
        val playlist = """
            #EXTM3U x-tvg-url="guide.xml.gz"
            #EXTINF:-1 tvg-id="news-one" group-title="News",News One
            https://example.com/live/1.ts
            #EXTINF:-1 tvg-id="news-two" group-title="News",News Two
            https://example.com/live/2.ts
            #EXTINF:-1 tvg-logo="show.jpg" group-title="Drama",Example Show S01E02
            https://example.com/series/12/2.mp4
            #EXTINF:-1 group-title="Drama",Example Show S01E01
            https://example.com/series/12/1.mp4
        """.trimIndent()

        val result = M3uParser().parse(account, playlist.byteInputStream())

        assertEquals("guide.xml.gz", result.epgUrl)
        assertEquals(2, result.channels.size)
        assertEquals(1, result.liveCategories.size)
        assertSame(result.channels[0].categoryKey, result.channels[1].categoryKey)
        assertSame(result.channels[0].categoryName, result.channels[1].categoryName)
        assertEquals(listOf(1, 2), result.seriesDetails.values.single().episodesBySeason[1]?.map { it.episodeNumber })
        assertEquals(4, result.stats.totalEntries)
        assertEquals(0, result.stats.unclassifiedEntries)
    }

    @Test
    fun classifiesGenericVodFilesAndNormalizesEquivalentCategoryNames() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="|EN| ✪  CLASSIC   4K",Classic One
            https://cdn.example.com/streams/101.MKV?token=abc
            #EXTINF:-1 group-title="|EN| ✪ CLASSIC 4K",Classic Two
            https://cdn.example.com/streams/102.mp4
            #EXTINF:-1,No Group Movie
            https://cdn.example.com/streams/103.avi
        """.trimIndent()

        val result = M3uParser().parse(account, playlist)

        assertEquals(3, result.movies.size)
        assertEquals(2, result.movieCategories.size)
        assertSame(result.movies[0].categoryKey, result.movies[1].categoryKey)
        assertEquals("|EN| ✪ CLASSIC 4K", result.movies[0].categoryName)
        assertTrue(result.movieCategories.any { it.name == "Uncategorized Movies" })
        assertEquals(0, result.stats.unclassifiedEntries)
    }

    @Test
    fun recognizesSpacedEpisodeNamesBeforeVodFileExtensions() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="Drama",Example Show S01 - E02
            https://cdn.example.com/generic/episode.mp4
        """.trimIndent()

        val result = M3uParser().parse(account, playlist)

        assertEquals(0, result.movies.size)
        assertEquals(1, result.series.size)
        assertEquals(1, result.stats.seriesEpisodes)
    }

    @Test
    fun normalizesCanonicallyEquivalentUnicodeCategoryKeys() {
        val composedGroup = "Caf\u00e9"
        val decomposedGroup = "Cafe\u0301"
        val playlist = """
            #EXTM3U
            #EXTINF:-1 group-title="$composedGroup",Movie One
            https://cdn.example.com/streams/201.mp4
            #EXTINF:-1 group-title="$decomposedGroup",Movie Two
            https://cdn.example.com/streams/202.mp4
        """.trimIndent()

        val result = M3uParser().parse(account, playlist)

        assertEquals(1, result.movieCategories.size)
        assertSame(result.movies[0].categoryKey, result.movies[1].categoryKey)
        assertEquals(composedGroup, result.movies[0].categoryName)
    }
}
