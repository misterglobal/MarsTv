package tv.mars.app.data.network

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

class XmlTvParser {
    fun parse(bytes: ByteArray, channels: List<Channel>): Map<String, List<Programme>> =
        parse(ByteArrayInputStream(bytes), channels)

    fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Programme>> {
        if (channels.isEmpty()) return emptyMap()
        
        val bufferedInput = java.io.BufferedInputStream(input)
        val finalInput = if (isGzipped(bufferedInput)) GZIPInputStream(bufferedInput) else bufferedInput
        
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(finalInput, null)
        }

        val directIds = channels.map(Channel::epgId).toSet()
        val nameToId = channels.associate { normalize(it.name) to it.epgId }
        val idRemap = mutableMapOf<String, String>()
        val programmes = mutableMapOf<String, MutableList<Programme>>()

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
                        val effectiveId = idRemap[programmeChannel]
                            ?: programmeChannel.takeIf { it in directIds }
                        val now = System.currentTimeMillis()
                        if (
                            effectiveId != null && title.isNotBlank() &&
                            programmeStart > 0 && programmeEnd > programmeStart &&
                            programmeEnd >= now - 14L * 86_400_000L && programmeStart <= now + 8L * 86_400_000L
                        ) {
                            programmes.getOrPut(effectiveId) { mutableListOf() } += Programme(
                                channelEpgId = effectiveId,
                                title = title.trim(),
                                description = description.trim(),
                                startMs = programmeStart,
                                endMs = programmeEnd,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }

        input.close()
        return programmes.mapValues { (_, values) -> values.sortedBy(Programme::startMs) }
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
        val offsetMatch = Regex("([+-]\\d{4})").find(raw.drop(14))?.groupValues?.get(1)
        val offset = offsetMatch?.let {
            val sign = if (it.startsWith('-')) -1 else 1
            ZoneOffset.ofHoursMinutes(sign * it.substring(1, 3).toInt(), sign * it.substring(3, 5).toInt())
        } ?: ZoneOffset.UTC
        date.toInstant(offset).toEpochMilli()
    }.getOrDefault(0L)

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"), "")
}
