package tv.mars.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.SourceType
import java.nio.file.Files

class M3uParserTest {
    private val account = IptvAccount(
        id = "account",
        name = "Test",
        sourceType = SourceType.M3U,
        m3uUrl = "https://example.com/list.m3u",
    )

    @Test
    fun parsesStreamWithoutDuplicatingCategoryStringsAndGroupsEpisodes() = runBlocking {
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
        assertEquals(listOf(1, 2), result.episodesBySeriesId.values.single().map { it.episodeNumber })
        assertEquals(4, result.stats.totalEntries)
        assertEquals(0, result.stats.unclassifiedEntries)
    }

    @Test
    fun classifiesGenericVodFilesAndNormalizesEquivalentCategoryNames() = runBlocking {
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
    fun recognizesSpacedEpisodeNamesBeforeVodFileExtensions() = runBlocking {
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
    fun normalizesCanonicallyEquivalentUnicodeCategoryKeys() = runBlocking {
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

    @Test
    fun streamsOneHundredThousandEntriesInBoundedBatches() = runBlocking {
        val fixture = Files.createTempFile("marstv-100k-", ".m3u")
        try {
            Files.newBufferedWriter(fixture).use { writer ->
                writer.appendLine("#EXTM3U")
                repeat(15_000) { index ->
                    writer.appendLine("#EXTINF:-1 group-title=\"Live\",Channel $index")
                    writer.appendLine("https://example.com/live/$index.ts")
                }
                repeat(45_000) { index ->
                    writer.appendLine("#EXTINF:-1 group-title=\"Movies\",Movie $index")
                    writer.appendLine("https://example.com/movies/$index.mp4")
                }
                repeat(40_000) { index ->
                    val show = index % 1_000
                    val episode = index / 1_000 + 1
                    writer.appendLine("#EXTINF:-1 group-title=\"Series\",Show $show S01E$episode")
                    writer.appendLine("https://example.com/series/$show/$index.mp4")
                }
            }

            var batches = 0
            var rows = 0
            var largestBatch = 0
            val result = Files.newInputStream(fixture).use { input ->
                M3uParser().parseStreaming(account, input) { batch ->
                    batches++
                    rows += batch.rowCount
                    largestBatch = maxOf(largestBatch, batch.rowCount)
                }
            }

            assertEquals(100_000, result.stats.totalEntries)
            assertEquals(15_000, result.stats.liveEntries)
            assertEquals(45_000, result.stats.movieEntries)
            assertEquals(40_000, result.stats.seriesEpisodes)
            assertTrue(batches > 1)
            assertEquals(101_003, rows)
            assertTrue(largestBatch <= M3uParser.MAX_STREAM_BATCH_SIZE)
        } finally {
            Files.deleteIfExists(fixture)
        }
    }

    @Test
    fun streamingParserStopsWhenBatchConsumerCancels() = runBlocking {
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(2_000) { index ->
                appendLine("#EXTINF:-1 group-title=\"Live\",Channel $index")
                appendLine("https://example.com/live/$index.ts")
            }
        }
        var emittedBatches = 0

        try {
            M3uParser().parseStreaming(account, playlist.byteInputStream(), batchSize = 100) {
                emittedBatches++
                throw CancellationException("test cancellation")
            }
            throw AssertionError("Expected parsing to be cancelled")
        } catch (_: CancellationException) {
            assertEquals(1, emittedBatches)
        }
    }
}
