package tv.mars.app.data.network

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
    private val stringPool = mutableMapOf<String, String>()

    private fun intern(s: String?): String? {
        if (s == null) return null
        return stringPool.getOrPut(s) { s }
    }

    fun parse(account: IptvAccount, text: String): M3uDocument =
        parse(account, text.byteInputStream())

    fun parse(account: IptvAccount, inputStream: java.io.InputStream): M3uDocument {
        val reader = inputStream.bufferedReader()
        val line = reader.readLine()?.trimStart('\uFEFF')
        require(line?.startsWith("#EXTM3U", ignoreCase = true) == true) {
            "The URL did not return a valid M3U playlist"
        }

        val header = attributes(line)
        val epgUrl = header["x-tvg-url"].orEmpty()
            .ifBlank { header["url-tvg"].orEmpty() }
            .split(',')
            .firstOrNull()
            .orEmpty()

        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<MediaContent>()
        val seriesByTitle = linkedMapOf<String, SeriesAccumulator>()
        val liveCategories = linkedMapOf<String, Category>()
        val movieCategories = linkedMapOf<String, Category>()
        val seriesCategories = linkedMapOf<String, Category>()
        var itemIndex = 0
        var unclassifiedEntries = 0

        while (true) {
            val info = reader.readLine() ?: break
            if (info.isBlank()) continue
            if (!info.startsWith("#EXTINF", ignoreCase = true)) continue
            
            val url = reader.readLine()?.trim() ?: break
            if (url.startsWith('#')) continue 
            
            val resolvedUrl = resolve(account.m3uUrl, url)
            itemIndex++

            val attrs = attributes(info)
            val fallbackTitle = info.substringAfterLast(',', "Channel $itemIndex").trim()
            val title = attrs["tvg-name"].orEmpty().ifBlank { fallbackTitle }
            val rawGroup = normalizeGroup(attrs["group-title"].orEmpty())
            val remoteId = stableId(resolvedUrl, itemIndex)
            val classification = detectKind(resolvedUrl, rawGroup, title)
            val kind = classification.kind
            if (classification.recognized.not()) unclassifiedEntries++
            val group = rawGroup.ifBlank {
                when (kind) {
                    ContentKind.MOVIE -> "Uncategorized Movies"
                    ContentKind.SERIES, ContentKind.EPISODE -> "Uncategorized Series"
                    ContentKind.LIVE -> "Uncategorized"
                }
            }
            val categoryKey = intern("${kind.name.lowercase(Locale.US)}:${slug(group)}") ?: "unknown"
            val category = when (kind) {
                ContentKind.LIVE -> liveCategories
                ContentKind.MOVIE -> movieCategories
                ContentKind.SERIES, ContentKind.EPISODE -> seriesCategories
            }.getOrPut(categoryKey) {
                Category(
                    key = categoryKey,
                    remoteId = intern(categoryKey.substringAfter(':')) ?: "",
                    name = intern(group) ?: "Uncategorized",
                    kind = if (kind == ContentKind.EPISODE) ContentKind.SERIES else kind,
                )
            }

            when (kind) {
                ContentKind.LIVE -> channels += Channel(
                    key = intern("${account.id}:live:$remoteId") ?: "",
                    remoteId = intern(remoteId) ?: "",
                    accountId = account.id,
                    name = title,
                    categoryKey = category.key,
                    categoryName = category.name,
                    logoUrl = intern(attrs["tvg-logo"]) ?: "",
                    epgId = intern(attrs["tvg-id"].orEmpty().ifBlank { title }) ?: "",
                    playbackUrl = resolvedUrl,
                    supportsCatchUp = attrs["catchup"].orEmpty().isNotBlank() || attrs["catchup-source"].orEmpty().isNotBlank(),
                    catchUpDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
                    catchUpTemplate = attrs["catchup-source"].orEmpty(),
                )

                ContentKind.MOVIE -> movies += MediaContent(
                    key = intern("${account.id}:movie:$remoteId") ?: "",
                    remoteId = intern(remoteId) ?: "",
                    accountId = account.id,
                    title = title,
                    kind = ContentKind.MOVIE,
                    categoryKey = category.key,
                    categoryName = category.name,
                    artworkUrl = intern(attrs["tvg-logo"]) ?: "",
                    playbackUrl = resolvedUrl,
                )

                ContentKind.SERIES, ContentKind.EPISODE -> addSeriesEpisode(
                    account = account,
                    seriesByTitle = seriesByTitle,
                    remoteId = remoteId,
                    title = title,
                    category = category,
                    artworkUrl = intern(attrs["tvg-logo"]) ?: "",
                    playbackUrl = resolvedUrl,
                )
            }
        }
        stringPool.clear()

        val episodesBySeriesId = linkedMapOf<String, List<Episode>>()
        val series = seriesByTitle.values
            .sortedBy { it.media.title.lowercase(Locale.US) }
            .map { accumulator ->
                val allEpisodes = accumulator.episodesBySeason.values.flatten()
                    .sortedWith(compareBy(Episode::seasonNumber, Episode::episodeNumber, Episode::title))
                episodesBySeriesId[accumulator.media.seriesId] = allEpisodes
                accumulator.media
            }
        return M3uDocument(
            epgUrl = epgUrl,
            liveCategories = liveCategories.values.sortedBy { it.name.lowercase(Locale.US) },
            movieCategories = movieCategories.values.sortedBy { it.name.lowercase(Locale.US) },
            seriesCategories = seriesCategories.values.sortedBy { it.name.lowercase(Locale.US) },
            channels = channels,
            movies = movies,
            series = series,
            episodesBySeriesId = episodesBySeriesId,
            stats = M3uParseStats(
                totalEntries = itemIndex,
                liveEntries = channels.size,
                movieEntries = movies.size,
                seriesEpisodes = episodesBySeriesId.values.sumOf { it.size },
                unclassifiedEntries = unclassifiedEntries,
            ),
        )
    }

    private fun addSeriesEpisode(
        account: IptvAccount,
        seriesByTitle: MutableMap<String, SeriesAccumulator>,
        remoteId: String,
        title: String,
        category: Category,
        artworkUrl: String,
        playbackUrl: String,
    ) {
        val match = seasonEpisodePattern.find(title)
        val seriesTitle = match?.let { title.removeRange(it.range).trim(' ', '-', '.', '_') }
            ?.ifBlank { category.name }
            ?: category.name.ifBlank { title }
        val accumulator = seriesByTitle.getOrPut(seriesTitle) {
            val seriesId = intern("m3u-${slug(seriesTitle)}-${remoteId.takeLast(6)}") ?: ""
            SeriesAccumulator(
                media = MediaContent(
                    key = intern("${account.id}:series:$seriesId") ?: "",
                    remoteId = seriesId,
                    accountId = account.id,
                    title = intern(seriesTitle) ?: "",
                    kind = ContentKind.SERIES,
                    categoryKey = category.key,
                    categoryName = category.name,
                    artworkUrl = artworkUrl,
                    seriesId = seriesId,
                ),
            )
        }
        if (accumulator.media.artworkUrl.isBlank() && artworkUrl.isNotBlank()) {
            accumulator.media = accumulator.media.copy(artworkUrl = artworkUrl)
        }
        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: match?.groupValues?.getOrNull(3)?.toIntOrNull()
            ?: 1
        val episodes = accumulator.episodesBySeason.getOrPut(season) { mutableListOf() }
        val number = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: match?.groupValues?.getOrNull(4)?.toIntOrNull()
            ?: episodes.size + 1
        episodes += Episode(
            key = intern("${account.id}:episode:$remoteId") ?: "",
            remoteId = intern(remoteId) ?: "",
            accountId = account.id,
            title = intern(title) ?: "",
            seasonNumber = season,
            episodeNumber = number,
            playbackUrl = playbackUrl,
            artworkUrl = artworkUrl,
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

    private data class SeriesAccumulator(
        var media: MediaContent,
        val episodesBySeason: MutableMap<Int, MutableList<Episode>> = linkedMapOf(),
    )
}
