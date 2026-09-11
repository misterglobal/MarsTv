package tv.mars.app.data.network

import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.MediaContent
import tv.mars.app.core.SeriesDetails

data class XtreamCatalogStats(
    val liveEntries: Int,
    val movieEntries: Int,
    val seriesEntries: Int,
)

class XtreamClient(
    private val network: NetworkClient,
    private val xmlTvParser: XmlTvParser,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun streamCatalog(
        account: IptvAccount,
        emit: suspend (M3uBatch) -> Unit,
    ): XtreamCatalogStats {
        validate(account)

        val liveCategories = categories(account, "get_live_categories", ContentKind.LIVE)
        val movieCategories = categories(account, "get_vod_categories", ContentKind.MOVIE)
        val seriesCategories = categories(account, "get_series_categories", ContentKind.SERIES)
        val liveNames = liveCategories.associate { it.remoteId to it.name }
        val movieNames = movieCategories.associate { it.remoteId to it.name }
        val seriesNames = seriesCategories.associate { it.remoteId to it.name }
        val knownCategoryIds = mutableMapOf(
            ContentKind.LIVE to liveNames.keys.toMutableSet(),
            ContentKind.MOVIE to movieNames.keys.toMutableSet(),
            ContentKind.SERIES to seriesNames.keys.toMutableSet(),
        )
        val emitter = XtreamBatchEmitter(emit)
        (liveCategories + movieCategories + seriesCategories).forEach { emitter.addCategory(it) }

        var liveCount = 0
        forEachArray(account, "get_live_streams") { item ->
            val categoryId = item.string("category_id").ifBlank { "uncategorized" }
            emitter.ensureCategory(account, ContentKind.LIVE, categoryId, liveNames[categoryId], knownCategoryIds)
            streamChannel(account, item, categoryId, liveNames[categoryId])?.let {
                emitter.addChannel(it)
                liveCount++
            }
        }

        val movieCount = streamMoviesWithCategoryFallback(
            categoryIds = movieCategories.map { it.remoteId },
            fetch = { category, consume ->
                forEachArray(account, "get_vod_streams",
                    extra = category?.let { mapOf("category_id" to it) }.orEmpty(), consume = consume)
            },
        ) { item, requestedCategory ->
            val categoryId = item.string("category_id").ifBlank { requestedCategory ?: "uncategorized" }
            emitter.ensureCategory(account, ContentKind.MOVIE, categoryId, movieNames[categoryId], knownCategoryIds)
            streamMovie(account, item, categoryId, movieNames[categoryId])?.let { emitter.addMedia(it) }
        }

        var seriesCount = 0
        forEachArray(account, "get_series") { item ->
            val categoryId = item.string("category_id").ifBlank { "uncategorized" }
            emitter.ensureCategory(account, ContentKind.SERIES, categoryId, seriesNames[categoryId], knownCategoryIds)
            streamSeries(account, item, categoryId, seriesNames[categoryId])?.let {
                emitter.addMedia(it)
                seriesCount++
            }
        }
        emitter.flush()
        return XtreamCatalogStats(liveCount, movieCount, seriesCount)
    }

    suspend fun streamProgrammeGuide(
        account: IptvAccount,
        channels: List<XmlTvChannelReference>,
        emit: suspend (List<tv.mars.app.core.Programme>) -> Unit,
    ) {
        network.getStream(endpoint(account, "xmltv.php"), retryOnFailure = false) { stream ->
            xmlTvParser.parseStreamingReferences(stream, channels, emit = emit)
        }
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

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String): JsonElement {
        var element: JsonElement = JsonObject(emptyMap<String, JsonElement>())
        try {
            network.getStream(url) { stream ->
                element = json.decodeFromStream<JsonElement>(stream)
            }
        } catch (error: SourceHttpException) {
            val action = Uri.parse(url).getQueryParameter("action") ?: "account validation"
            throw java.io.IOException("Xtream $action: source returned HTTP ${error.statusCode}", error)
        }
        return element
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T : Any> mapArray(
        account: IptvAccount,
        action: String,
        transform: (JsonObject) -> T?,
    ): List<T> {
        val result = mutableListOf<T>()
        forEachArray(account, action) { item -> transform(item)?.let(result::add) }
        return result
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun forEachArray(
        account: IptvAccount,
        action: String,
        extra: Map<String, String> = emptyMap(),
        consume: suspend (JsonObject) -> Unit,
    ) {
        try {
        network.getStream(
            endpoint(account, "player_api.php", mapOf("action" to action) + extra),
            retryOnFailure = false,
        ) { stream ->
            val iterator = json.decodeToSequence<JsonObject>(stream, DecodeSequenceMode.ARRAY_WRAPPED).iterator()
            while (iterator.hasNext()) {
                currentCoroutineContext().ensureActive()
                consume(iterator.next())
            }
        }
        } catch (error: SourceHttpException) {
            throw java.io.IOException("Xtream $action: source returned HTTP ${error.statusCode}", error)
        }
    }

    private fun streamChannel(
        account: IptvAccount,
        item: JsonObject,
        categoryId: String,
        categoryName: String?,
    ): Channel? {
        val id = item.string("stream_id").takeIf(String::isNotBlank) ?: return null
        val extension = item.string("container_extension").ifBlank { "ts" }
        val name = item.string("name").ifBlank { "Channel $id" }
        return Channel(
            key = "${account.id}:live:$id",
            remoteId = id,
            accountId = account.id,
            name = name,
            categoryKey = categoryKey(account, ContentKind.LIVE, categoryId),
            categoryName = categoryName ?: "Uncategorized",
            logoUrl = item.string("stream_icon"),
            epgId = item.string("epg_channel_id").ifBlank { name },
            playbackUrl = xtreamPlaybackReference("live", id, extension),
            supportsCatchUp = item.string("tv_archive") == "1",
            catchUpDays = item.int("tv_archive_duration"),
        )
    }

    private fun streamMovie(
        account: IptvAccount,
        item: JsonObject,
        categoryId: String,
        categoryName: String?,
    ): MediaContent? {
        val id = item.string("stream_id").takeIf(String::isNotBlank) ?: return null
        val extension = item.string("container_extension").ifBlank { "mp4" }
        return MediaContent(
            key = "${account.id}:movie:$id",
            remoteId = id,
            accountId = account.id,
            title = item.string("name").ifBlank { "Movie $id" },
            kind = ContentKind.MOVIE,
            categoryKey = categoryKey(account, ContentKind.MOVIE, categoryId),
            categoryName = categoryName ?: "Uncategorized",
            artworkUrl = item.string("stream_icon"),
            playbackUrl = xtreamPlaybackReference("movie", id, extension),
            rating = item.string("rating_5based").ifBlank { item.string("rating") },
            year = item.string("year").ifBlank { item.string("release_date").take(4) },
        )
    }

    private fun streamSeries(
        account: IptvAccount,
        item: JsonObject,
        categoryId: String,
        categoryName: String?,
    ): MediaContent? {
        val id = item.string("series_id").takeIf(String::isNotBlank) ?: return null
        return MediaContent(
            key = "${account.id}:series:$id",
            remoteId = id,
            accountId = account.id,
            title = item.string("name").ifBlank { "Series $id" },
            kind = ContentKind.SERIES,
            categoryKey = categoryKey(account, ContentKind.SERIES, categoryId),
            categoryName = categoryName ?: "Uncategorized",
            artworkUrl = item.string("cover"),
            backdropUrl = item.array("backdrop_path").firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty(),
            description = item.string("plot"),
            rating = item.string("rating_5based").ifBlank { item.string("rating") },
            year = item.string("releaseDate").take(4),
            seriesId = id,
        )
    }

    private fun categoryKey(account: IptvAccount, kind: ContentKind, categoryId: String): String =
        "${account.id}:${kind.name.lowercase()}:category:$categoryId"

    private suspend fun categories(
        account: IptvAccount,
        action: String,
        kind: ContentKind,
    ): List<Category> = mapArray(account, action) { item ->
        val id = item.string("category_id")
        val name = item.string("category_name")
        if (id.isBlank() || name.isBlank()) null else Category(
            key = categoryKey(account, kind, id),
            remoteId = id,
            name = name,
            kind = kind,
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
            playbackUrl = xtreamPlaybackReference("series", id, extension),
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

    private class XtreamBatchEmitter(
        private val emit: suspend (M3uBatch) -> Unit,
    ) {
        private var categories = ArrayList<Category>()
        private var channels = ArrayList<Channel>()
        private var media = ArrayList<MediaContent>()
        private var rowCount = 0

        suspend fun ensureCategory(
            account: IptvAccount,
            kind: ContentKind,
            categoryId: String,
            categoryName: String?,
            knownIds: MutableMap<ContentKind, MutableSet<String>>,
        ) {
            val ids = knownIds.getOrPut(kind) { mutableSetOf() }
            if (!ids.add(categoryId)) return
            addCategory(
                Category(
                    key = "${account.id}:${kind.name.lowercase()}:category:$categoryId",
                    remoteId = categoryId,
                    name = categoryName ?: "Uncategorized",
                    kind = kind,
                ),
            )
        }

        suspend fun addCategory(value: Category) {
            categories += value
            addedRow()
        }

        suspend fun addChannel(value: Channel) {
            channels += value
            addedRow()
        }

        suspend fun addMedia(value: MediaContent) {
            media += value
            addedRow()
        }

        private suspend fun addedRow() {
            rowCount++
            if (rowCount == M3uParser.MAX_STREAM_BATCH_SIZE) flush()
        }

        suspend fun flush() {
            if (rowCount == 0) return
            val batch = M3uBatch(categories = categories, channels = channels, media = media)
            categories = ArrayList()
            channels = ArrayList()
            media = ArrayList()
            rowCount = 0
            emit(batch)
        }
    }

    private fun JsonElement.asArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String, fallback: Int = 0): Int = string(key).toIntOrNull() ?: fallback
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
}
