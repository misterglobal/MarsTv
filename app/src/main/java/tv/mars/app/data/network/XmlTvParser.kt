package tv.mars.app.data.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import tv.mars.app.core.Channel
import tv.mars.app.core.Programme
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream

data class XmlTvChannelReference(val epgId: String, val name: String)

data class XmlTvParseStats(val programmeCount: Int)

class XmlTvParser {
    private val offsetPattern = Regex("([+-]\\d{4})")
    private val normalizationPattern = Regex("[^a-z0-9]")

    suspend fun parse(bytes: ByteArray, channels: List<Channel>): Map<String, List<Programme>> =
        parse(ByteArrayInputStream(bytes), channels)

    suspend fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Programme>> =
        parseReferences(input, channels.map { XmlTvChannelReference(it.epgId, it.name) })

    suspend fun parseReferences(
        input: InputStream,
        channels: List<XmlTvChannelReference>,
    ): Map<String, List<Programme>> {
        val programmes = mutableMapOf<String, MutableList<Programme>>()
        parseStreamingReferences(input, channels) { batch ->
            batch.forEach { programme ->
                programmes.getOrPut(programme.channelEpgId) { mutableListOf() } += programme
            }
        }
        programmes.values.forEach { it.sortBy(Programme::startMs) }
        return programmes
    }

    suspend fun parseStreamingReferences(
        input: InputStream,
        channels: List<XmlTvChannelReference>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        emit: suspend (List<Programme>) -> Unit,
    ): XmlTvParseStats {
        require(batchSize in 1..MAX_BATCH_SIZE) { "XMLTV batch size must be between 1 and $MAX_BATCH_SIZE" }
        if (channels.isEmpty()) return XmlTvParseStats(0)

        val bufferedInput = java.io.BufferedInputStream(input)
        val finalInput = if (isGzipped(bufferedInput)) GZIPInputStream(bufferedInput) else bufferedInput
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(finalInput, null) }
        val directIds = channels.map(XmlTvChannelReference::epgId).toSet()
        val nameToId = channels.associate { normalize(it.name) to it.epgId }
        val programmeLimitPerChannel = (MAX_PROGRAMMES_TOTAL / directIds.size.coerceAtLeast(1))
            .coerceIn(MIN_PROGRAMMES_PER_CHANNEL, MAX_PROGRAMMES_PER_CHANNEL)
        val idRemap = mutableMapOf<String, String>()
        val programmeCounts = mutableMapOf<String, Int>()
        val now = System.currentTimeMillis()
        val earliestProgrammeEnd = now - PAST_WINDOW_MS
        val latestProgrammeStart = now + FUTURE_WINDOW_MS
        var batch = ArrayList<Programme>(batchSize)
        var programmeCount = 0

        var event = parser.eventType
        var channelBlockId = ""
        var channelDisplayName = ""
        var programmeChannel = ""
        var programmeStart = 0L
        var programmeEnd = 0L
        var title = ""
        var description = ""
        var textTarget = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> {
                        channelBlockId = parser.getAttributeValue(null, "id").orEmpty()
                        channelDisplayName = ""
                    }
                    "display-name" -> textTarget = "display-name"
                    "programme" -> {
                        programmeChannel = parser.getAttributeValue(null, "channel").orEmpty()
                        programmeStart = parseTime(parser.getAttributeValue(null, "start").orEmpty())
                        programmeEnd = parseTime(parser.getAttributeValue(null, "stop").orEmpty())
                        title = ""
                        description = ""
                    }
                    "title" -> textTarget = "title"
                    "desc" -> textTarget = "desc"
                }

                XmlPullParser.TEXT -> when (textTarget) {
                    "display-name" -> channelDisplayName += parser.text.orEmpty()
                    "title" -> title += parser.text.orEmpty()
                    "desc" -> description += parser.text.orEmpty()
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "display-name", "title", "desc" -> textTarget = ""
                    "channel" -> {
                        val target = when {
                            channelBlockId in directIds -> channelBlockId
                            normalize(channelDisplayName) in nameToId -> nameToId.getValue(normalize(channelDisplayName))
                            else -> null
                        }
                        if (target != null) idRemap[channelBlockId] = target
                    }
                    "programme" -> {
                        val effectiveId = idRemap[programmeChannel] ?: programmeChannel.takeIf { it in directIds }
                        val channelCount = effectiveId?.let { programmeCounts[it] } ?: 0
                        if (
                            effectiveId != null && title.isNotBlank() &&
                            programmeStart > 0 && programmeEnd > programmeStart &&
                            programmeEnd >= earliestProgrammeEnd && programmeStart <= latestProgrammeStart &&
                            programmeCount < MAX_PROGRAMMES_TOTAL && channelCount < programmeLimitPerChannel
                        ) {
                            batch += Programme(
                                channelEpgId = effectiveId,
                                title = title.trim().take(MAX_TITLE_CHARS),
                                description = description.trim().take(MAX_DESCRIPTION_CHARS),
                                startMs = programmeStart,
                                endMs = programmeEnd,
                            )
                            programmeCounts[effectiveId] = channelCount + 1
                            programmeCount++
                            if (batch.size == batchSize) {
                                emit(batch)
                                batch = ArrayList(batchSize)
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (batch.isNotEmpty()) emit(batch)
        return XmlTvParseStats(programmeCount)
    }

    private fun isGzipped(input: java.io.BufferedInputStream): Boolean {
        input.mark(2)
        val header = ByteArray(2)
        val read = input.read(header)
        input.reset()
        return read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
    }

    private fun parseTime(raw: String): Long = runCatching {
        val digits = raw.take(14)
        val date = LocalDateTime.parse(digits, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val offsetMatch = offsetPattern.find(raw.drop(14))?.groupValues?.get(1)
        val offset = offsetMatch?.let {
            val sign = if (it.startsWith('-')) -1 else 1
            ZoneOffset.ofHoursMinutes(sign * it.substring(1, 3).toInt(), sign * it.substring(3, 5).toInt())
        } ?: ZoneOffset.UTC
        date.toInstant(offset).toEpochMilli()
    }.getOrDefault(0L)

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(normalizationPattern, "")

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val MAX_BATCH_SIZE = 500
        const val MAX_PROGRAMMES_TOTAL = 100_000
        const val MIN_PROGRAMMES_PER_CHANNEL = 8
        const val MAX_PROGRAMMES_PER_CHANNEL = 64
        const val MAX_TITLE_CHARS = 300
        const val MAX_DESCRIPTION_CHARS = 1_000
        const val PAST_WINDOW_MS = 7L * 24 * 3_600_000L
        const val FUTURE_WINDOW_MS = 14L * 24 * 3_600_000L
    }
}
