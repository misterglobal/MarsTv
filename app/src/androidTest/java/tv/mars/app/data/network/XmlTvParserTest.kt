package tv.mars.app.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class XmlTvParserTest {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    @Test
    fun streamingParserEmitsBoundedBatches() = runBlocking {
        val channels = List(100) { XmlTvChannelReference("channel-$it", "Channel $it") }
        val xml = fixture(channels, programmesPerChannel = 12)
        var count = 0
        var batches = 0
        var largestBatch = 0

        val result = XmlTvParser().parseStreamingReferences(
            input = xml.byteInputStream(),
            channels = channels,
            batchSize = 100,
        ) { batch ->
            batches++
            count += batch.size
            largestBatch = maxOf(largestBatch, batch.size)
        }

        assertEquals(1_200, result.programmeCount)
        assertEquals(1_200, count)
        assertTrue(batches > 1)
        assertTrue(largestBatch <= 100)
    }

    @Test
    fun streamingParserPropagatesConsumerCancellation() = runBlocking {
        val channels = List(20) { XmlTvChannelReference("channel-$it", "Channel $it") }
        var emittedBatches = 0

        try {
            XmlTvParser().parseStreamingReferences(
                input = fixture(channels, programmesPerChannel = 10).byteInputStream(),
                channels = channels,
                batchSize = 50,
            ) {
                emittedBatches++
                throw CancellationException("test cancellation")
            }
            throw AssertionError("Expected parsing to be cancelled")
        } catch (_: CancellationException) {
            assertEquals(1, emittedBatches)
        }
    }

    private fun fixture(channels: List<XmlTvChannelReference>, programmesPerChannel: Int): String {
        val start = Instant.now().minusSeconds(3_600)
        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<tv>")
            channels.forEach { channel ->
                appendLine("<channel id=\"${channel.epgId}\"><display-name>${channel.name}</display-name></channel>")
            }
            channels.forEach { channel ->
                repeat(programmesPerChannel) { index ->
                    val programmeStart = start.plusSeconds(index * 1_800L)
                    val programmeEnd = programmeStart.plusSeconds(1_800)
                    appendLine(
                        "<programme channel=\"${channel.epgId}\" start=\"${formatter.format(programmeStart)}\" " +
                            "stop=\"${formatter.format(programmeEnd)}\"><title>Programme $index</title></programme>",
                    )
                }
            }
            appendLine("</tv>")
        }
    }
}
