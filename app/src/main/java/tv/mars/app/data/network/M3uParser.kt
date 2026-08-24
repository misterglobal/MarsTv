package tv.mars.app.data.network

import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.SeriesDetails
import java.net.URI
import java.util.Locale

data class M3uDocument(
    val epgUrl: String,
    val liveCategories: List<Category>,
    val movieCategories: List<Category>,
    val seriesCategories: List<Category>,
    val channels: List<Channel>,
    val movies: List<MediaContent>,
    val series: List<MediaContent>,
    val seriesDetails: Map<String, SeriesDetails>,
)

class M3uParser {
    private val attributePattern = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val seasonEpisodePattern = Regex(
        pattern = "(?i)(?:S(\\d{1,2})\\s*[. _-]*E(\\d{1,3})|(?:Season|S)\\s*(\\d{1,2})\\s*(?:Episode|Ep|E)\\s*(\\d{1,3}))",
    )

    fun parse(account: IptvAccount, text: String): M3uDocument =
        parse(account, text.byteInputStream())

    fun parse(account: IptvAccount, inputStream: java.io.InputStream): M3uDocument {
        val reader = inputStream.bufferedReader()
        var line = reader.readLine()?.trimStart('\uFEFF')
        require(line?.startsWith("#EXTM3U", ignoreCase = true) == true) {
            "The URL did not return a valid M3U playlist"
        }

        val header = attributes(line!!)
        val epgUrl = header["x-tvg-url"].orEmpty()
            .ifBlank { header["url-tvg"].orEmpty() }
            .split(',')
            .firstOrNull()
            .orEmpty()

        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<MediaContent>()
        val rawSeriesEpisodes = mutableListOf<RawSeriesEpisode>()
        var itemIndex = 0

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
            val group = attrs["group-title"].orEmpty().ifBlank { "Uncategorized" }
            val remoteId = stableId(resolvedUrl, itemIndex)
            val kind = detectKind(resolvedUrl, group, title)
            val categoryKey = "${kind.name.lowercase(Locale.US)}:${slug(group)}"

            when (kind) {
                ContentKind.LIVE -> channels += Channel(
                    key = "${account.id}:live:$remoteId",
                    remoteId = remoteId,
                    accountId = account.id,
                    name = title,
                    categoryKey = categoryKey,
                    categoryName = group,
                    logoUrl = attrs["tvg-logo"].orEmpty(),
                    epgId = attrs["tvg-id"].orEmpty().ifBlank { title },
                    playbackUrl = resolvedUrl,
                    supportsCatchUp = attrs["catchup"].orEmpty().isNotBlank() || attrs["catchup-source"].orEmpty().isNotBlank(),
                    catchUpDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
                    catchUpTemplate = attrs["catchup-source"].orEmpty(),
                )

                ContentKind.MOVIE -> movies += MediaContent(
                    key = "${account.id}:movie:$remoteId",
                    remoteId = remoteId,
                    accountId = account.id,
                    title = title,
                    kind = ContentKind.MOVIE,
                    categoryKey = categoryKey,
                    categoryName = group,
                    artworkUrl = attrs["tvg-logo"].orEmpty(),
                    playbackUrl = resolvedUrl,
                )

                ContentKind.SERIES, ContentKind.EPISODE -> rawSeriesEpisodes += RawSeriesEpisode(
                    remoteId = remoteId,
                    title = title,
                    group = group,
                    categoryKey = categoryKey,
                    artworkUrl = attrs["tvg-logo"].orEmpty(),
                    playbackUrl = resolvedUrl,
                )
            }
        }

        val seriesBuild = buildSeries(account, rawSeriesEpisodes)
        return M3uDocument(
            epgUrl = epgUrl,
            liveCategories = categories(channels.map { it.categoryKey to it.categoryName }, ContentKind.LIVE),
            movieCategories = categories(movies.map { it.categoryKey to it.categoryName }, ContentKind.MOVIE),
            seriesCategories = categories(seriesBuild.first.map { it.categoryKey to it.categoryName }, ContentKind.SERIES),
            channels = channels,
            movies = movies,
            series = seriesBuild.first,
            seriesDetails = seriesBuild.second,
        )
    }

    private fun buildSeries(
        account: IptvAccount,
        raw: List<RawSeriesEpisode>,
    ): Pair<List<MediaContent>, Map<String, SeriesDetails>> {
        val grouped = raw.groupBy { episode ->
            val match = seasonEpisodePattern.find(episode.title)
            match?.let { episode.title.removeRange(it.range).trim(' ', '-', '.', '_') }
                ?.ifBlank { episode.group }
                ?: episode.group.ifBlank { episode.title }
        }

        val series = mutableListOf<MediaContent>()
        val details = mutableMapOf<String, SeriesDetails>()
        grouped.entries.sortedBy { it.key.lowercase(Locale.US) }.forEach { (seriesTitle, items) ->
            val seriesId = "m3u-${slug(seriesTitle)}-${items.first().remoteId.takeLast(6)}"
            val first = items.first()
            val media = MediaContent(
                key = "${account.id}:series:$seriesId",
                remoteId = seriesId,
                accountId = account.id,
                title = seriesTitle,
                kind = ContentKind.SERIES,
                categoryKey = "series:${slug(first.group)}",
                categoryName = first.group,
                artworkUrl = items.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty(),
                seriesId = seriesId,
            )
            val episodes = items.mapIndexed { episodeIndex, item ->
                val match = seasonEpisodePattern.find(item.title)
                val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: match?.groupValues?.getOrNull(3)?.toIntOrNull()
                    ?: 1
                val number = match?.groupValues?.getOrNull(2)?.toIntOrNull()
                    ?: match?.groupValues?.getOrNull(4)?.toIntOrNull()
                    ?: episodeIndex + 1
                Episode(
                    key = "${account.id}:episode:${item.remoteId}",
                    remoteId = item.remoteId,
                    accountId = account.id,
                    title = item.title,
                    seasonNumber = season,
                    episodeNumber = number,
                    playbackUrl = item.playbackUrl,
                    artworkUrl = item.artworkUrl,
                )
            }.sortedWith(compareBy(Episode::seasonNumber, Episode::episodeNumber))
            series += media
            details[seriesId] = SeriesDetails(media, episodes.groupBy(Episode::seasonNumber))
        }
        return series to details
    }

    private fun categories(values: List<Pair<String, String>>, kind: ContentKind): List<Category> =
        values.distinctBy { it.first }.map { (key, name) ->
            Category(key = key, remoteId = key.substringAfter(':'), name = name, kind = kind)
        }.sortedBy { it.name.lowercase(Locale.US) }

    private fun attributes(line: String): Map<String, String> = attributePattern.findAll(line)
        .associate { it.groupValues[1].lowercase(Locale.US) to it.groupValues[2] }

    private fun detectKind(url: String, group: String, title: String): ContentKind {
        val searchable = "$url $group $title".lowercase(Locale.US)
        return when {
            "/series/" in searchable || Regex("(?i)S\\d{1,2}E\\d{1,3}").containsMatchIn(title) -> ContentKind.SERIES
            "/movie/" in searchable || "vod" in group.lowercase(Locale.US) || "movie" in group.lowercase(Locale.US) -> ContentKind.MOVIE
            else -> ContentKind.LIVE
        }
    }

    private fun stableId(url: String, index: Int): String =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { index.toString() }

    private fun resolve(base: String, candidate: String): String = runCatching {
        URI(base).resolve(candidate).toString()
    }.getOrDefault(candidate)

    private fun slug(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "uncategorized" }

    private data class RawSeriesEpisode(
        val remoteId: String,
        val title: String,
        val group: String,
        val categoryKey: String,
        val artworkUrl: String,
        val playbackUrl: String,
    )
}
