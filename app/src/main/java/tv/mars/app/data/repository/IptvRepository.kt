package tv.mars.app.data.repository

import android.net.Uri
import android.util.Log
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
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

class IptvRepository(
    private val persistence: tv.mars.app.data.local.CatalogPersistence
) {
    private companion object {
        const val TAG = "IptvRepository"
    }

    private val network = NetworkClient()
    private val xmlTv = XmlTvParser()
    private val m3u = M3uParser()
    private val xtream = XtreamClient(network, xmlTv)

    // We use a limited cache for M3U series episodes to prevent OOM on massive playlists.
    // The key is accountId, then seriesId -> List<Episode>.
    private val m3uSeriesCache = ConcurrentHashMap<String, Map<String, List<tv.mars.app.core.Episode>>>()

    suspend fun loadCatalog(account: IptvAccount): CatalogBundle = when (account.sourceType) {
        SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> {
            clearCache(account.id) // Clear M3U cache if we switch to Xtream
            xtream.loadCatalog(account)
        }
        SourceType.M3U -> loadM3u(account)
    }

    suspend fun loadSeriesDetails(account: IptvAccount, series: MediaContent): SeriesDetails =
        when (account.sourceType) {
            SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> xtream.loadSeriesDetails(account, series)
            SourceType.M3U -> {
                val xtreamAccount = account.xtreamAccountFromM3u()
                if (xtreamAccount != null && series.seriesId.startsWith("m3u-").not()) {
                    xtream.loadSeriesDetails(xtreamAccount, series)
                } else {
                    val accountEpisodes = m3uSeriesCache[account.id]
                        ?: persistence.loadEpisodes(account.id)?.also { m3uSeriesCache[account.id] = it }
                    val episodes = accountEpisodes?.get(series.seriesId)
                    SeriesDetails(series, episodes?.groupBy { it.seasonNumber }.orEmpty())
                }
            }
        }

    fun clearCache(accountId: String) {
        m3uSeriesCache.remove(accountId)
    }

    fun clearAllCaches() {
        m3uSeriesCache.clear()
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
        // A refresh must not retain the previous episode graph while a new one is built.
        m3uSeriesCache.remove(account.id)
        account.xtreamAccountFromM3u()?.let { xtreamAccount ->
            val xtreamResult = runCatching { xtream.loadCatalog(xtreamAccount) }
            if (xtreamResult.isSuccess) {
                val catalog = xtreamResult.getOrThrow()
                Log.i(
                    TAG,
                    "Xtream catalog: Live: ${catalog.channels.size}; " +
                        "Movies: ${catalog.movies.size}; Series: ${catalog.series.size}",
                )
                return catalog
            }
            Log.w(TAG, "Xtream discovery failed; using M3U classification fallback")
        }

        var parsed: M3uDocument? = null
        network.getStream(account.m3uUrl) { stream ->
            parsed = m3u.parse(account, stream)
        }
        val doc = parsed ?: error("Failed to parse M3U playlist")
        Log.i(
            TAG,
            "Playlist entries: ${doc.stats.totalEntries}; Live: ${doc.stats.liveEntries}; " +
                "Movies: ${doc.stats.movieEntries}; Series episodes: ${doc.stats.seriesEpisodes}; " +
                "Unclassified: ${doc.stats.unclassifiedEntries}",
        )
        persistence.saveEpisodes(account.id, doc.episodesBySeriesId)
        m3uSeriesCache[account.id] = doc.episodesBySeriesId
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

internal fun IptvAccount.xtreamAccountFromM3u(): IptvAccount? {
    if (sourceType != SourceType.M3U) return null
    val uri = runCatching { URI(m3uUrl) }.getOrNull() ?: return null
    val supportedScheme = uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
    if (supportedScheme.not() || uri.rawAuthority.isNullOrBlank()) return null
    if (uri.rawPath.substringAfterLast('/').equals("get.php", true).not()) return null

    val parameters = runCatching {
        uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore('=').urlDecode().lowercase()
                val value = part.substringAfter('=', "").urlDecode()
                key to value
            }
            .toMap()
    }.getOrNull() ?: return null
    val extractedUsername = parameters["username"].orEmpty()
    val extractedPassword = parameters["password"].orEmpty()
    if (extractedUsername.isBlank() || extractedPassword.isBlank()) return null

    val directory = uri.rawPath.substringBeforeLast('/', "").trimEnd('/')
    return copy(
        sourceType = SourceType.XTREAM,
        serverUrl = "${uri.scheme}://${uri.rawAuthority}$directory",
        username = extractedUsername,
        password = extractedPassword,
    )
}

private fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())
