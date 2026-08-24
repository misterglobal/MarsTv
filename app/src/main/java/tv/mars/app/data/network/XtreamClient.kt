package tv.mars.app.data.network

import android.net.Uri
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.SeriesDetails

class XtreamClient(
    private val network: NetworkClient,
    private val xmlTvParser: XmlTvParser,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCatalog(account: IptvAccount): CatalogBundle = coroutineScope {
        validate(account)

        val liveCategoriesJson = array(account, "get_live_categories")
        val movieCategoriesJson = array(account, "get_vod_categories")
        val seriesCategoriesJson = array(account, "get_series_categories")
        val liveStreamsJson = array(account, "get_live_streams")
        val moviesJson = array(account, "get_vod_streams")
        val seriesJson = array(account, "get_series")

        val liveCategories = categories(account, liveCategoriesJson, ContentKind.LIVE)
        val movieCategories = categories(account, movieCategoriesJson, ContentKind.MOVIE)
        val seriesCategories = categories(account, seriesCategoriesJson, ContentKind.SERIES)
        val liveNames = liveCategories.associate { it.remoteId to it.name }
        val movieNames = movieCategories.associate { it.remoteId to it.name }
        val seriesNames = seriesCategories.associate { it.remoteId to it.name }

        val channels = channels(account, liveStreamsJson, liveNames)
        val movies = movies(account, moviesJson, movieNames)
        val series = series(account, seriesJson, seriesNames)
        val programmes = runCatching {
            var epgMap = emptyMap<String, List<tv.mars.app.core.Programme>>()
            network.getStream(endpoint(account, "xmltv.php")) { stream ->
                epgMap = xmlTvParser.parse(stream, channels)
            }
            epgMap
        }.getOrDefault(emptyMap())

        CatalogBundle(
            accountId = account.id,
            liveCategories = fillMissingCategories(liveCategories, channels.map { it.categoryKey to it.categoryName }, ContentKind.LIVE),
            movieCategories = fillMissingCategories(movieCategories, movies.map { it.categoryKey to it.categoryName }, ContentKind.MOVIE),
            seriesCategories = fillMissingCategories(seriesCategories, series.map { it.categoryKey to it.categoryName }, ContentKind.SERIES),
            channels = channels,
            movies = movies,
            series = series,
            programmesByEpgId = programmes,
        )
    }

    suspend fun loadSeriesDetails(account: IptvAccount, series: MediaContent): SeriesDetails {
        val root = getJson(endpoint(account, "player_api.php", mapOf("action" to "get_series_info", "series_id" to series.seriesId)))
            .jsonObject
        val episodeRoot = root["episodes"]
        val episodes = mutableListOf<Episode>()

        when (episodeRoot) {
            is JsonObject -> episodeRoot.forEach { (seasonKey, value) ->
                value.asArrayOrEmpty().forEachIndexed { index, element ->
                    episodes += episode(account, seasonKey.toIntOrNull() ?: 1, index + 1, element.jsonObject)
                }
            }
            is JsonArray -> episodeRoot.forEachIndexed { index, element ->
                val item = element.jsonObject
                episodes += episode(account, item.int("season", 1), index + 1, item)
            }
            else -> Unit
        }

        return SeriesDetails(
            series = series.copy(
                description = root.obj("info")?.string("plot").orEmpty().ifBlank { series.description },
                backdropUrl = root.obj("info")?.array("backdrop_path")?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty(),
            ),
            episodesBySeason = episodes.sortedWith(compareBy(Episode::seasonNumber, Episode::episodeNumber))
                .groupBy(Episode::seasonNumber),
        )
    }

    private suspend fun validate(account: IptvAccount) {
        val root = getJson(endpoint(account, "player_api.php")).jsonObject
        val userInfo = root.obj("user_info") ?: error("The server did not return Xtream account information")
        val authenticated = userInfo.string("auth") == "1" || userInfo.string("auth").equals("true", true)
        require(authenticated) { userInfo.string("message").ifBlank { "The Xtream username or password was rejected" } }
    }

    private suspend fun array(account: IptvAccount, action: String): JsonArray =
        getJson(endpoint(account, "player_api.php", mapOf("action" to action))).asArrayOrEmpty()

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String): JsonElement {
        var element: JsonElement = JsonObject(emptyMap<String, JsonElement>())
        network.getStream(url) { stream ->
            element = json.decodeFromStream<JsonElement>(stream)
        }
        return element
    }

    private fun categories(account: IptvAccount, values: JsonArray, kind: ContentKind): List<Category> = values.mapNotNull { element ->
        val item = element.jsonObject
        val id = item.string("category_id")
        val name = item.string("category_name")
        if (id.isBlank() || name.isBlank()) null else Category(
            key = "${account.id}:${kind.name.lowercase()}:category:$id",
            remoteId = id,
            name = name,
            kind = kind,
        )
    }

    private fun channels(
        account: IptvAccount,
        values: JsonArray,
        categoryNames: Map<String, String>,
    ): List<Channel> = values.mapNotNull { element ->
        val item = element.jsonObject
        val id = item.string("stream_id")
        if (id.isBlank()) return@mapNotNull null
        val categoryId = item.string("category_id").ifBlank { "uncategorized" }
        val extension = item.string("container_extension").ifBlank { "ts" }
        Channel(
            key = "${account.id}:live:$id",
            remoteId = id,
            accountId = account.id,
            name = item.string("name").ifBlank { "Channel $id" },
            categoryKey = "${account.id}:live:category:$categoryId",
            categoryName = categoryNames[categoryId] ?: "Uncategorized",
            logoUrl = item.string("stream_icon"),
            epgId = item.string("epg_channel_id").ifBlank { item.string("name") },
            playbackUrl = streamUrl(account, "live", id, extension),
            supportsCatchUp = item.string("tv_archive") == "1",
            catchUpDays = item.int("tv_archive_duration"),
        )
    }

    private fun movies(
        account: IptvAccount,
        values: JsonArray,
        categoryNames: Map<String, String>,
    ): List<MediaContent> = values.mapNotNull { element ->
        val item = element.jsonObject
        val id = item.string("stream_id")
        if (id.isBlank()) return@mapNotNull null
        val categoryId = item.string("category_id").ifBlank { "uncategorized" }
        val extension = item.string("container_extension").ifBlank { "mp4" }
        MediaContent(
            key = "${account.id}:movie:$id",
            remoteId = id,
            accountId = account.id,
            title = item.string("name").ifBlank { "Movie $id" },
            kind = ContentKind.MOVIE,
            categoryKey = "${account.id}:movie:category:$categoryId",
            categoryName = categoryNames[categoryId] ?: "Uncategorized",
            artworkUrl = item.string("stream_icon"),
            playbackUrl = streamUrl(account, "movie", id, extension),
            rating = item.string("rating_5based").ifBlank { item.string("rating") },
            year = item.string("year").ifBlank { item.string("release_date").take(4) },
        )
    }

    private fun series(
        account: IptvAccount,
        values: JsonArray,
        categoryNames: Map<String, String>,
    ): List<MediaContent> = values.mapNotNull { element ->
        val item = element.jsonObject
        val id = item.string("series_id")
        if (id.isBlank()) return@mapNotNull null
        val categoryId = item.string("category_id").ifBlank { "uncategorized" }
        MediaContent(
            key = "${account.id}:series:$id",
            remoteId = id,
            accountId = account.id,
            title = item.string("name").ifBlank { "Series $id" },
            kind = ContentKind.SERIES,
            categoryKey = "${account.id}:series:category:$categoryId",
            categoryName = categoryNames[categoryId] ?: "Uncategorized",
            artworkUrl = item.string("cover"),
            backdropUrl = item.array("backdrop_path").firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty(),
            description = item.string("plot"),
            rating = item.string("rating_5based").ifBlank { item.string("rating") },
            year = item.string("releaseDate").take(4),
            seriesId = id,
        )
    }

    private fun episode(
        account: IptvAccount,
        fallbackSeason: Int,
        fallbackEpisode: Int,
        item: JsonObject,
    ): Episode {
        val id = item.string("id").ifBlank { item.string("stream_id") }
        val extension = item.string("container_extension").ifBlank { "mp4" }
        val info = item.obj("info")
        return Episode(
            key = "${account.id}:episode:$id",
            remoteId = id,
            accountId = account.id,
            title = item.string("title").ifBlank { "Episode ${item.int("episode_num", fallbackEpisode)}" },
            seasonNumber = item.int("season", fallbackSeason),
            episodeNumber = item.int("episode_num", fallbackEpisode),
            playbackUrl = streamUrl(account, "series", id, extension),
            artworkUrl = info?.string("movie_image").orEmpty(),
            description = info?.string("plot").orEmpty(),
            durationText = info?.string("duration").orEmpty(),
        )
    }

    private fun endpoint(
        account: IptvAccount,
        path: String,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val builder = Uri.parse("${account.serverUrl.trimEnd('/')}/$path").buildUpon()
            .appendQueryParameter("username", account.username)
            .appendQueryParameter("password", account.password)
        extra.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun streamUrl(account: IptvAccount, section: String, id: String, extension: String): String =
        "${account.serverUrl.trimEnd('/')}/$section/${Uri.encode(account.username)}/${Uri.encode(account.password)}/${Uri.encode(id)}.${extension.ifBlank { "ts" }}"

    private fun fillMissingCategories(
        existing: List<Category>,
        used: List<Pair<String, String>>,
        kind: ContentKind,
    ): List<Category> {
        val currentKeys = existing.map(Category::key).toSet()
        val missing = used.distinctBy { it.first }.filterNot { it.first in currentKeys }.map { (key, name) ->
            Category(key = key, remoteId = key.substringAfterLast(':'), name = name, kind = kind)
        }
        return (existing + missing).sortedBy { it.name.lowercase() }
    }

    private fun JsonElement.asArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String, fallback: Int = 0): Int = string(key).toIntOrNull() ?: fallback
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}
