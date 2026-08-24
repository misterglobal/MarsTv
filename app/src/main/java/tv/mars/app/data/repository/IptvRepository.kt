package tv.mars.app.data.repository

import android.net.Uri
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.PlayerRequest
import tv.mars.app.core.Programme
import tv.mars.app.core.SeriesDetails
import tv.mars.app.core.SourceType
import tv.mars.app.data.network.M3uDocument
import tv.mars.app.data.network.M3uParser
import tv.mars.app.data.network.NetworkClient
import tv.mars.app.data.network.XmlTvParser
import tv.mars.app.data.network.XtreamClient
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class IptvRepository {
    private val network = NetworkClient()
    private val xmlTv = XmlTvParser()
    private val m3u = M3uParser()
    private val xtream = XtreamClient(network, xmlTv)
    private val m3uSeriesCache = ConcurrentHashMap<String, Map<String, SeriesDetails>>()

    suspend fun loadCatalog(account: IptvAccount): CatalogBundle = when (account.sourceType) {
        SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> xtream.loadCatalog(account)
        SourceType.M3U -> loadM3u(account)
    }

    suspend fun loadSeriesDetails(account: IptvAccount, series: MediaContent): SeriesDetails =
        when (account.sourceType) {
            SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> xtream.loadSeriesDetails(account, series)
            SourceType.M3U -> m3uSeriesCache[account.id]?.get(series.seriesId)
                ?: SeriesDetails(series, emptyMap())
        }

    fun liveRequest(channel: Channel): PlayerRequest = PlayerRequest(
        contentKey = channel.key,
        accountId = channel.accountId,
        title = channel.name,
        url = channel.playbackUrl,
        kind = ContentKind.LIVE,
        artworkUrl = channel.logoUrl,
    )

    fun catchUpRequest(account: IptvAccount, channel: Channel, programme: Programme): PlayerRequest? {
        if (!channel.supportsCatchUp || !programme.isPast) return null
        val durationMinutes = ((programme.endMs - programme.startMs) / 60_000L).coerceAtLeast(1)
        val startSeconds = programme.startMs / 1000L
        val endSeconds = programme.endMs / 1000L
        val url = when {
            account.sourceType == SourceType.M3U && channel.catchUpTemplate.isNotBlank() -> {
                val utc = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(programme.startMs))
                channel.catchUpTemplate
                    .replace("{utc}", startSeconds.toString())
                    .replace("{lutc}", startSeconds.toString())
                    .replace("{start}", startSeconds.toString())
                    .replace("{timestamp}", startSeconds.toString())
                    .replace("{end}", endSeconds.toString())
                    .replace("{duration}", (durationMinutes * 60L).toString())
                    .replace("{utc:YmdHMS}", utc)
            }
            account.sourceType != SourceType.M3U -> {
                val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(programme.startMs))
                "${account.serverUrl.trimEnd('/')}/timeshift/${Uri.encode(account.username)}/${Uri.encode(account.password)}/$durationMinutes/$start/${Uri.encode(channel.remoteId)}.ts"
            }
            else -> return null
        }
        return PlayerRequest(
            contentKey = "${channel.key}:catchup:${programme.startMs}",
            accountId = channel.accountId,
            title = programme.title,
            url = url,
            kind = ContentKind.LIVE,
            artworkUrl = channel.logoUrl,
        )
    }

    private suspend fun loadM3u(account: IptvAccount): CatalogBundle {
        var parsed: M3uDocument? = null
        network.getStream(account.m3uUrl) { stream ->
            parsed = m3u.parse(account, stream)
        }
        val doc = parsed ?: error("Failed to parse M3U playlist")
        m3uSeriesCache[account.id] = doc.seriesDetails
        val programmes = if (doc.epgUrl.isNotBlank()) {
            runCatching {
                val resolvedEpg = resolve(account.m3uUrl, doc.epgUrl)
                var map = emptyMap<String, List<Programme>>()
                network.getStream(resolvedEpg) { stream ->
                    map = xmlTv.parse(stream, doc.channels)
                }
                map
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        return CatalogBundle(
            accountId = account.id,
            liveCategories = doc.liveCategories,
            movieCategories = doc.movieCategories,
            seriesCategories = doc.seriesCategories,
            channels = doc.channels,
            movies = doc.movies,
            series = doc.series,
            programmesByEpgId = programmes,
        )
    }

    private fun resolve(base: String, candidate: String): String = runCatching {
        URI(base).resolve(candidate).toString()
    }.getOrDefault(candidate)
}
