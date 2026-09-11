package tv.mars.app.data.repository

import android.net.Uri
import android.util.Log
import androidx.paging.PagingData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.CatalogLookup
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.PlayerRequest
import tv.mars.app.core.Programme
import tv.mars.app.core.SeriesDetails
import tv.mars.app.core.SourceType
import tv.mars.app.data.network.M3uParser
import tv.mars.app.data.network.NetworkClient
import tv.mars.app.data.network.XmlTvParser
import tv.mars.app.data.network.XtreamClient
import tv.mars.app.data.network.resolveXtreamPlaybackReference
import tv.mars.app.data.local.RoomCatalogStore
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class IptvRepository(
    private val persistence: tv.mars.app.data.local.CatalogPersistence,
    private val roomCatalog: RoomCatalogStore,
) {
    private companion object {
        const val TAG = "IptvRepository"
    }

    private val network = NetworkClient()
    private val xmlTv = XmlTvParser()
    private val m3u = M3uParser()
    private val xtream = XtreamClient(network, xmlTv)
    private val m3uRoomImporter = M3uRoomImporter(m3u, roomCatalog)
    private val xtreamRoomImporter = XtreamRoomImporter(xtream, roomCatalog)

    suspend fun refreshCatalog(
        account: IptvAccount,
        onCatalogReady: suspend (Long) -> Unit = {},
        onInitialCatalogAvailable: suspend (Long) -> Unit = {},
    ): Long = when (account.sourceType) {
        SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> loadXtream(account, onInitialCatalogAvailable, onCatalogReady)
        SourceType.M3U -> loadM3u(account, onInitialCatalogAvailable, onCatalogReady)
    }

    suspend fun catalogLoadedAt(accountId: String): Long? = roomCatalog.catalogLoadedAt(accountId)

    suspend fun loadSeriesDetails(account: IptvAccount, series: MediaContent): SeriesDetails =
        when (account.sourceType) {
            SourceType.PRIVATE_XTREAM, SourceType.XTREAM -> xtream.loadSeriesDetails(account, series)
            SourceType.M3U -> {
                val xtreamAccount = account.xtreamAccountFromM3u()
                if (xtreamAccount != null && series.seriesId.startsWith("m3u-").not()) {
                    xtream.loadSeriesDetails(xtreamAccount, series)
                } else {
                    SeriesDetails(series, roomCatalog.episodes(account.id, series.seriesId).groupBy { it.seasonNumber })
                }
            }
        }

    fun clearCache(accountId: String) = Unit

    fun clearAllCaches() {
        // Paging owns bounded UI caches; there is no repository catalog cache.
    }

    fun observeCategories(accountId: String, kind: ContentKind): Flow<List<tv.mars.app.core.Category>> =
        roomCatalog.observeCategories(accountId, kind)

    fun pagedMedia(
        accountId: String,
        kind: ContentKind,
        categoryKey: String?,
        blockedCategoryKeys: Set<String>,
    ): Flow<PagingData<MediaContent>> = roomCatalog.pagedMedia(accountId, kind, categoryKey, blockedCategoryKeys)

    fun pagedChannels(
        accountId: String,
        categoryKey: String?,
        blockedCategoryKeys: Set<String>,
    ): Flow<PagingData<Channel>> = roomCatalog.pagedChannels(accountId, categoryKey, blockedCategoryKeys)

    fun programmes(
        accountId: String,
        channelEpgId: String,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<Programme>> = roomCatalog.programmes(accountId, channelEpgId, windowStart, windowEnd)

    suspend fun searchCatalog(
        accountId: String,
        query: String,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup = roomCatalog.search(accountId, query, blockedCategoryKeys)

    suspend fun favouriteCatalog(
        accountId: String,
        favouriteKeys: Set<String>,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup = roomCatalog.favourites(accountId, favouriteKeys, blockedCategoryKeys)

    suspend fun seedRoomFromLegacyCache(catalog: CatalogBundle) {
        if (roomCatalog.hasCatalog(catalog.accountId)) return
        val episodes = persistence.loadEpisodes(catalog.accountId).orEmpty()
        roomCatalog.replaceCatalog(catalog, episodes)
    }

    suspend fun clearStoredCatalog(accountId: String) {
        roomCatalog.clearAccount(accountId)
        persistence.clear(accountId)
    }

    fun liveRequest(account: IptvAccount, channel: Channel): PlayerRequest = PlayerRequest(
        contentKey = channel.key,
        accountId = channel.accountId,
        title = channel.name,
        url = playbackUrl(account, channel.playbackUrl),
        kind = ContentKind.LIVE,
        artworkUrl = channel.logoUrl,
    )

    fun playbackUrl(account: IptvAccount, storedValue: String): String {
        val playbackAccount = if (account.sourceType == SourceType.M3U) {
            account.xtreamAccountFromM3u() ?: account
        } else {
            account
        }
        return resolveXtreamPlaybackReference(playbackAccount, storedValue)
    }

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

    private suspend fun loadM3u(
        account: IptvAccount,
        onInitialCatalogAvailable: suspend (Long) -> Unit,
        onCatalogReady: suspend (Long) -> Unit,
    ): Long {
        // A refresh must not retain the previous episode graph while a new one is built.
        account.xtreamAccountFromM3u()?.let { xtreamAccount ->
            val xtreamResult = runCatching { loadXtream(xtreamAccount, onInitialCatalogAvailable, onCatalogReady) }
            if (xtreamResult.isSuccess) {
                return xtreamResult.getOrThrow()
            }
            Log.w(TAG, "Xtream discovery failed; using M3U classification fallback")
        }

        val publishEarly = !roomCatalog.hasCatalog(account.id)
        var parsed: tv.mars.app.data.network.M3uStreamResult? = null
        network.getStream(account.m3uUrl) { stream ->
            parsed = m3uRoomImporter.import(
                account = account,
                inputStream = stream,
                publishEarly = publishEarly,
                onFirstBatchCommitted = { onInitialCatalogAvailable(System.currentTimeMillis()) },
            )
        }
        val result = parsed ?: error("Failed to parse M3U playlist")
        Log.i(
            TAG,
            "Playlist entries: ${result.stats.totalEntries}; Live: ${result.stats.liveEntries}; " +
                "Movies: ${result.stats.movieEntries}; Series episodes: ${result.stats.seriesEpisodes}; " +
                "Unclassified: ${result.stats.unclassifiedEntries}",
        )
        onCatalogReady(roomCatalog.catalogLoadedAt(account.id) ?: System.currentTimeMillis())
        if (result.epgUrl.isNotBlank()) {
            runCatching {
                val resolvedEpg = resolve(account.m3uUrl, result.epgUrl)
                val references = roomCatalog.channelReferences(account.id)
                importProgrammes(account.id) { write ->
                    network.getStream(resolvedEpg) { stream ->
                        xmlTv.parseStreamingReferences(stream, references, emit = write)
                    }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Could not refresh the M3U programme guide", it)
            }
        }
        return roomCatalog.catalogLoadedAt(account.id) ?: System.currentTimeMillis()
    }

    private suspend fun loadXtream(
        account: IptvAccount,
        onInitialCatalogAvailable: suspend (Long) -> Unit,
        onCatalogReady: suspend (Long) -> Unit,
    ): Long {
        val publishEarly = !roomCatalog.hasCatalog(account.id)
        val stats = xtreamRoomImporter.import(
            account = account,
            publishEarly = publishEarly,
            onFirstContentBatchCommitted = { onInitialCatalogAvailable(System.currentTimeMillis()) },
        )
        Log.i(
            TAG,
            "Xtream catalog: Live: ${stats.liveEntries}; Movies: ${stats.movieEntries}; " +
                "Series: ${stats.seriesEntries}",
        )
        onCatalogReady(roomCatalog.catalogLoadedAt(account.id) ?: System.currentTimeMillis())
        runCatching {
            val references = roomCatalog.channelReferences(account.id)
            importProgrammes(account.id) { write -> xtream.streamProgrammeGuide(account, references, write) }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Could not refresh the Xtream programme guide", it)
        }
        return roomCatalog.catalogLoadedAt(account.id) ?: System.currentTimeMillis()
    }

    private suspend fun importProgrammes(
        accountId: String,
        parse: suspend (write: suspend (List<Programme>) -> Unit) -> Unit,
    ) {
        val session = roomCatalog.beginProgrammeImport(accountId) ?: return
        try {
            parse(session::write)
            session.commit()
        } catch (error: Throwable) {
            withContext(NonCancellable) { session.discard() }
            throw error
        }
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
