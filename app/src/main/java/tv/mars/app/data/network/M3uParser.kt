package tv.mars.app.data.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.SeriesDetails
import java.net.URI
import java.text.Normalizer
import java.util.Locale

data class M3uParseStats(
    val totalEntries: Int,
    val liveEntries: Int,
    val movieEntries: Int,
    val seriesEpisodes: Int,
    val unclassifiedEntries: Int,
)

data class M3uDocument(
    val epgUrl: String,
    val liveCategories: List<Category>,
    val movieCategories: List<Category>,
    val seriesCategories: List<Category>,
    val channels: List<Channel>,
    val movies: List<MediaContent>,
    val series: List<MediaContent>,
    val episodesBySeriesId: Map<String, List<Episode>>,
    val stats: M3uParseStats,
)

data class M3uBatch(
    val categories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val media: List<MediaContent> = emptyList(),
    val episodes: List<M3uEpisode> = emptyList(),
) {
    val rowCount: Int get() = categories.size + channels.size + media.size + episodes.size
}

data class M3uEpisode(
    val seriesId: String,
    val episode: Episode,
)

data class M3uStreamResult(
    val epgUrl: String,
    val stats: M3uParseStats,
)

class M3uParser {
    private val attributePattern = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val seasonEpisodePattern = Regex(
        pattern = "(?i)(?:S(\\d{1,2})\\s*[. _-]*E(\\d{1,3})|(?:Season|S)\\s*(\\d{1,2})\\s*(?:Episode|Ep|E)\\s*(\\d{1,3}))",
    )
    private val seriesPathPattern = Regex("(?i)/(?:series|shows?|episodes?)(?:/|$)")
    private val moviePathPattern = Regex("(?i)/(?:movies?|vod|video-on-demand)(?:/|$)")
    private val movieExtensionPattern = Regex("(?i)\\.(?:mp4|mkv|avi|mov|m4v|webm|wmv|flv)(?:$|[?#])")
    private val livePathPattern = Regex("(?i)/(?:live|channels?)(?:/|$)")
    private val liveExtensionPattern = Regex("(?i)\\.(?:m3u8|ts)(?:$|[?#])")
    private val whitespacePattern = Regex("\\s+")
    private val combiningMarkPattern = Regex("\\p{M}+")
    private val slugSeparatorPattern = Regex("[^a-z0-9]+")
    suspend fun parseStreaming(
        account: IptvAccount,
        inputStream: java.io.InputStream,
        batchSize: Int = DEFAULT_STREAM_BATCH_SIZE,
        emit: suspend (M3uBatch) -> Unit,
    ): M3uStreamResult {
        require(batchSize in 1..MAX_STREAM_BATCH_SIZE) { "M3U batch size must be between 1 and $MAX_STREAM_BATCH_SIZE" }
        val reader = inputStream.bufferedReader()
        val firstLine = reader.readLine()?.trimStart('\uFEFF')
        require(firstLine?.startsWith("#EXTM3U", ignoreCase = true) == true) {
            "The URL did not return a valid M3U playlist"
        }

        val header = attributes(firstLine)
        val epgUrl = header["x-tvg-url"].orEmpty()
            .ifBlank { header["url-tvg"].orEmpty() }
            .split(',')
            .firstOrNull()
            .orEmpty()
        val emitter = StreamingBatchEmitter(batchSize, emit)
        val categoriesByKey = HashMap<String, Category>()
        val seriesByTitle = HashMap<String, StreamingSeriesState>()
        var itemIndex = 0
        var liveEntries = 0
        var movieEntries = 0
        var seriesEpisodes = 0
        var unclassifiedEntries = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val info = reader.readLine() ?: break
            if (info.isBlank() || !info.startsWith("#EXTINF", ignoreCase = true)) continue
            val url = reader.readLine()?.trim() ?: break
            if (url.startsWith('#')) continue

            itemIndex++
            val resolvedUrl = resolve(account.m3uUrl, url)
            val attrs = attributes(info)
            val fallbackTitle = info.substringAfterLast(',', "Channel $itemIndex").trim()
            val title = attrs["tvg-name"].orEmpty().ifBlank { fallbackTitle }
            val rawGroup = normalizeGroup(attrs["group-title"].orEmpty())
            val remoteId = stableId(resolvedUrl, itemIndex)
            val classification = detectKind(resolvedUrl, rawGroup, title)
            if (!classification.recognized) unclassifiedEntries++
            val group = rawGroup.ifBlank {
                when (classification.kind) {
                    ContentKind.MOVIE -> "Uncategorized Movies"
                    ContentKind.SERIES, ContentKind.EPISODE -> "Uncategorized Series"
                    ContentKind.LIVE -> "Uncategorized"
                }
            }
            val categoryKind = if (classification.kind == ContentKind.LIVE) ContentKind.LIVE
                else if (classification.kind == ContentKind.MOVIE) ContentKind.MOVIE else ContentKind.SERIES
            val categoryKey = "${categoryKind.name.lowercase(Locale.US)}:${slug(group)}"
            val category = categoriesByKey[categoryKey] ?: Category(
                key = categoryKey,
                remoteId = categoryKey.substringAfter(':'),
                name = group,
                kind = categoryKind,
            ).also {
                categoriesByKey[categoryKey] = it
                emitter.addCategory(it)
            }

            when (classification.kind) {
                ContentKind.LIVE -> {
                    emitter.addChannel(
                        Channel(
                            key = "${account.id}:live:$remoteId",
                            remoteId = remoteId,
                            accountId = account.id,
                            name = title,
                            categoryKey = category.key,
                            categoryName = category.name,
                            logoUrl = attrs["tvg-logo"].orEmpty(),
                            epgId = attrs["tvg-id"].orEmpty().ifBlank { title },
                            playbackUrl = resolvedUrl,
                            supportsCatchUp = attrs["catchup"].orEmpty().isNotBlank() ||
                                attrs["catchup-source"].orEmpty().isNotBlank(),
                            catchUpDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
                            catchUpTemplate = attrs["catchup-source"].orEmpty(),
                        ),
                    )
                    liveEntries++
                }

                ContentKind.MOVIE -> {
                    emitter.addMedia(
                        MediaContent(
                            key = "${account.id}:movie:$remoteId",
                            remoteId = remoteId,
                            accountId = account.id,
                            title = title,
                            kind = ContentKind.MOVIE,
                            categoryKey = category.key,
                            categoryName = category.name,
                            artworkUrl = attrs["tvg-logo"].orEmpty(),
                            playbackUrl = resolvedUrl,
                        ),
                    )
                    movieEntries++
                }

                ContentKind.SERIES, ContentKind.EPISODE -> {
                    addStreamingEpisode(
                        account = account,
                        seriesByTitle = seriesByTitle,
                        remoteId = remoteId,
                        title = title,
                        category = category,
                        artworkUrl = attrs["tvg-logo"].orEmpty(),
                        playbackUrl = resolvedUrl,
                        emitter = emitter,
                    )
                    seriesEpisodes++
                }
            }
        }
        emitter.flush()
        return M3uStreamResult(
            epgUrl = epgUrl,
            stats = M3uParseStats(itemIndex, liveEntries, movieEntries, seriesEpisodes, unclassifiedEntries),
        )
    }

    suspend fun parse(account: IptvAccount, text: String): M3uDocument =
        parse(account, text.byteInputStream())

    suspend fun parse(account: IptvAccount, inputStream: java.io.InputStream): M3uDocument {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<MediaContent>()
        val seriesByKey = linkedMapOf<String, MediaContent>()
        val episodesBySeriesId = linkedMapOf<String, MutableList<Episode>>()
        val categoriesByKey = linkedMapOf<String, Category>()
        val result = parseStreaming(account, inputStream) { batch ->
            batch.categories.forEach { categoriesByKey[it.key] = it }
            channels += batch.channels
            batch.media.forEach { media ->
                if (media.kind == ContentKind.MOVIE) movies += media else seriesByKey[media.key] = media
            }
            batch.episodes.forEach { value ->
                episodesBySeriesId.getOrPut(value.seriesId) { mutableListOf() } += value.episode
            }
        }
        val liveCategories = categoriesByKey.values.filter { it.kind == ContentKind.LIVE }
        val movieCategories = categoriesByKey.values.filter { it.kind == ContentKind.MOVIE }
        val seriesCategories = categoriesByKey.values.filter { it.kind == ContentKind.SERIES }
        val sortedEpisodes = episodesBySeriesId.mapValues { (_, episodes) ->
            episodes.sortedWith(compareBy(Episode::seasonNumber, Episode::episodeNumber, Episode::title))
        }
        return M3uDocument(
            epgUrl = result.epgUrl,
            liveCategories = liveCategories.sortedBy { it.name.lowercase(Locale.US) },
            movieCategories = movieCategories.sortedBy { it.name.lowercase(Locale.US) },
            seriesCategories = seriesCategories.sortedBy { it.name.lowercase(Locale.US) },
            channels = channels,
            movies = movies,
            series = seriesByKey.values.sortedBy { it.title.lowercase(Locale.US) },
            episodesBySeriesId = sortedEpisodes,
            stats = result.stats,
        )
    }

    private suspend fun addStreamingEpisode(
        account: IptvAccount,
        seriesByTitle: MutableMap<String, StreamingSeriesState>,
        remoteId: String,
        title: String,
        category: Category,
        artworkUrl: String,
        playbackUrl: String,
        emitter: StreamingBatchEmitter,
    ) {
        val match = seasonEpisodePattern.find(title)
        val seriesTitle = match?.let { title.removeRange(it.range).trim(' ', '-', '.', '_') }
            ?.ifBlank { category.name }
            ?: category.name.ifBlank { title }
        val state = seriesByTitle[seriesTitle] ?: run {
            val seriesId = "m3u-${slug(seriesTitle)}-${remoteId.takeLast(6)}"
            StreamingSeriesState(
                media = MediaContent(
                    key = "${account.id}:series:$seriesId",
                    remoteId = seriesId,
                    accountId = account.id,
                    title = seriesTitle,
                    kind = ContentKind.SERIES,
                    categoryKey = category.key,
                    categoryName = category.name,
                    artworkUrl = artworkUrl,
                    seriesId = seriesId,
                ),
            ).also {
                seriesByTitle[seriesTitle] = it
                emitter.addMedia(it.media)
            }
        }
        if (state.media.artworkUrl.isBlank() && artworkUrl.isNotBlank()) {
            state.media = state.media.copy(artworkUrl = artworkUrl)
            emitter.addMedia(state.media)
        }
        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: match?.groupValues?.getOrNull(3)?.toIntOrNull()
            ?: 1
        val fallbackNumber = state.episodeCountsBySeason.merge(season, 1, Int::plus) ?: 1
        val number = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: match?.groupValues?.getOrNull(4)?.toIntOrNull()
            ?: fallbackNumber
        emitter.addEpisode(
            M3uEpisode(
                seriesId = state.media.seriesId,
                episode = Episode(
                    key = "${account.id}:episode:$remoteId",
                    remoteId = remoteId,
                    accountId = account.id,
                    title = title,
                    seasonNumber = season,
                    episodeNumber = number,
                    playbackUrl = playbackUrl,
                    artworkUrl = artworkUrl,
                ),
            ),
        )
    }

    private fun attributes(line: String): Map<String, String> = attributePattern.findAll(line)
        .associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }

    private fun detectKind(url: String, group: String, title: String): Classification {
        val normalizedGroup = group.lowercase(Locale.US)
        return when {
            seriesPathPattern.containsMatchIn(url) ||
                seasonEpisodePattern.containsMatchIn(title) ||
                containsAny(normalizedGroup, "series", "tv show", "episode") -> Classification(ContentKind.SERIES)

            moviePathPattern.containsMatchIn(url) ||
                movieExtensionPattern.containsMatchIn(url) ||
                containsAny(normalizedGroup, "vod", "movie", "film", "cinema") -> Classification(ContentKind.MOVIE)

            livePathPattern.containsMatchIn(url) ||
                liveExtensionPattern.containsMatchIn(url) ||
                url.startsWith("udp://", ignoreCase = true) ||
                url.startsWith("rtp://", ignoreCase = true) -> Classification(ContentKind.LIVE)

            else -> Classification(ContentKind.LIVE, recognized = false)
        }
    }

    private fun containsAny(value: String, vararg terms: String): Boolean = terms.any(value::contains)

    private fun stableId(url: String, index: Int): String =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { index.toString() }

    private fun resolve(base: String, candidate: String): String = runCatching {
        URI(base).resolve(candidate).toString()
    }.getOrDefault(candidate)

    private fun normalizeGroup(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(whitespacePattern, " ")
        .trim()

    private fun slug(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(combiningMarkPattern, "")
        .lowercase(Locale.US)
        .replace(slugSeparatorPattern, "-")
        .trim('-')
        .ifBlank { "uncategorized" }

    private data class Classification(
        val kind: ContentKind,
        val recognized: Boolean = true,
    )

    private data class StreamingSeriesState(
        var media: MediaContent,
        val episodeCountsBySeason: MutableMap<Int, Int> = HashMap(),
    )

    private class StreamingBatchEmitter(
        private val batchSize: Int,
        private val emit: suspend (M3uBatch) -> Unit,
    ) {
        private val categories = ArrayList<Category>()
        private val channels = ArrayList<Channel>()
        private val media = ArrayList<MediaContent>()
        private val episodes = ArrayList<M3uEpisode>()
        private var rowCount = 0

        suspend fun addCategory(value: Category) = add(categories, value)
        suspend fun addChannel(value: Channel) = add(channels, value)
        suspend fun addMedia(value: MediaContent) = add(media, value)
        suspend fun addEpisode(value: M3uEpisode) = add(episodes, value)

        private suspend fun <T> add(target: MutableList<T>, value: T) {
            target += value
            rowCount++
            if (rowCount == batchSize) flush()
        }

        suspend fun flush() {
            if (rowCount == 0) return
            emit(M3uBatch(categories.toList(), channels.toList(), media.toList(), episodes.toList()))
            categories.clear()
            channels.clear()
            media.clear()
            episodes.clear()
            rowCount = 0
        }
    }

    companion object {
        const val DEFAULT_STREAM_BATCH_SIZE = 400
        const val MAX_STREAM_BATCH_SIZE = 500
    }
}
