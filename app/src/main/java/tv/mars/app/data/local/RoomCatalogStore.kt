package tv.mars.app.data.local

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.CatalogLookup
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.MediaContent
import tv.mars.app.core.Programme
import tv.mars.app.data.network.M3uBatch
import tv.mars.app.data.network.XmlTvChannelReference
import java.util.UUID

class RoomCatalogStore(private val database: MarsTvDatabase) {
    private val dao = database.catalogDao()

    suspend fun replaceCatalog(
        catalog: CatalogBundle,
        episodesBySeriesId: Map<String, List<Episode>> = emptyMap(),
    ) {
        val generation = UUID.randomUUID().toString()
        val accountId = catalog.accountId
        var activated = false
        try {
            writeBatches(
                sequenceOf(catalog.liveCategories, catalog.movieCategories, catalog.seriesCategories)
                    .flatMap { it.asSequence() }
                    .map { it.toEntity(accountId, generation) },
                dao::insertCategories,
            )
            writeBatches(
                catalog.channels.asSequence().map { it.toEntity(generation) } +
                    catalog.movies.asSequence().map { it.toEntity(generation) } +
                    catalog.series.asSequence().map { it.toEntity(generation) },
                dao::insertItems,
            )
            writeBatches(
                episodesBySeriesId.asSequence().flatMap { (seriesId, episodes) ->
                    episodes.asSequence().map { it.toEntity(generation, seriesId) }
                },
                dao::insertEpisodes,
            )
            writeBatches(
                catalog.programmesByEpgId.retainedProgrammeEntities(accountId, generation),
                dao::insertProgrammes,
            )

            currentCoroutineContext().ensureActive()
            database.withTransaction {
                dao.insertImport(CatalogImportEntity(accountId, generation, catalog.loadedAt))
            }
            activated = true
            runCatching { deleteObsoleteRows(accountId, generation) }
                .onFailure { Log.w(TAG, "Committed catalog but could not remove an obsolete generation", it) }
        } catch (error: Throwable) {
            if (!activated) withContext(NonCancellable) {
                runCatching { deleteGeneration(accountId, generation) }
                    .onFailure { Log.w(TAG, "Could not remove an uncommitted catalog generation", it) }
            }
            throw error
        }
    }

    fun beginImport(
        accountId: String,
        loadedAt: Long = System.currentTimeMillis(),
        publishEarly: Boolean = false,
    ): ImportSession = ImportSession(accountId, UUID.randomUUID().toString(), loadedAt, publishEarly)

    suspend fun hasCatalog(accountId: String): Boolean = dao.hasActiveImport(accountId)

    suspend fun catalogLoadedAt(accountId: String): Long? = dao.catalogLoadedAt(accountId)

    suspend fun channelReferences(accountId: String): List<XmlTvChannelReference> =
        dao.channelReferences(accountId).map { XmlTvChannelReference(it.epgId, it.title) }

    inner class ImportSession internal constructor(
        private val accountId: String,
        private val generation: String,
        private val loadedAt: Long,
        private val publishEarly: Boolean,
    ) {
        private var finished = false
        private var publishedEarly = false

        suspend fun write(batch: M3uBatch) {
            check(!finished) { "Catalog import session is already finished" }
            require(batch.rowCount <= IMPORT_BATCH_SIZE) { "Catalog import batch exceeds $IMPORT_BATCH_SIZE rows" }
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                if (batch.categories.isNotEmpty()) {
                    dao.insertCategories(batch.categories.map { it.toEntity(accountId, generation) })
                }
                val items = ArrayList<CatalogItemEntity>(batch.channels.size + batch.media.size)
                batch.channels.forEach { items += it.toEntity(generation) }
                batch.media.forEach { items += it.toEntity(generation) }
                if (items.isNotEmpty()) dao.insertItems(items)
                if (batch.episodes.isNotEmpty()) {
                    dao.insertEpisodes(batch.episodes.map { it.episode.toEntity(generation, it.seriesId) })
                }
                if (publishEarly && !publishedEarly) {
                    // A zero timestamp makes an interrupted preview refresh on the next launch.
                    dao.insertImport(CatalogImportEntity(accountId, generation, 0L))
                }
            }
            if (publishEarly) publishedEarly = true
        }

        suspend fun commit() {
            check(!finished) { "Catalog import session is already finished" }
            currentCoroutineContext().ensureActive()
            database.withTransaction {
                dao.insertImport(CatalogImportEntity(accountId, generation, loadedAt))
            }
            finished = true
            runCatching { deleteObsoleteRows(accountId, generation) }
                .onFailure { Log.w(TAG, "Committed catalog but could not remove an obsolete generation", it) }
        }

        suspend fun discard() {
            if (finished) return
            deleteGeneration(accountId, generation)
            if (publishedEarly) database.withTransaction {
                dao.deleteImportGeneration(accountId, generation)
            }
            finished = true
        }
    }

    fun observeCategories(accountId: String, kind: ContentKind): Flow<List<Category>> =
        dao.observeCategories(accountId, kind.name).map { values -> values.map(CatalogCategoryEntity::toModel) }

    fun pagedChannels(
        accountId: String,
        categoryKey: String? = null,
        blockedCategoryKeys: Set<String> = emptySet(),
    ): Flow<PagingData<Channel>> = Pager(
        config = pagingConfig(),
        pagingSourceFactory = {
            if (blockedCategoryKeys.isEmpty()) dao.pagingItems(accountId, ContentKind.LIVE.name, categoryKey)
            else dao.pagingItemsExcluding(accountId, ContentKind.LIVE.name, categoryKey, blockedCategoryKeys.toList())
        },
    ).flow.map { page -> page.map(CatalogItemEntity::toChannel) }

    fun pagedMedia(
        accountId: String,
        kind: ContentKind,
        categoryKey: String? = null,
        blockedCategoryKeys: Set<String> = emptySet(),
    ): Flow<PagingData<MediaContent>> {
        require(kind == ContentKind.MOVIE || kind == ContentKind.SERIES)
        return Pager(
            config = pagingConfig(),
            pagingSourceFactory = {
                if (blockedCategoryKeys.isEmpty()) dao.pagingItems(accountId, kind.name, categoryKey)
                else dao.pagingItemsExcluding(accountId, kind.name, categoryKey, blockedCategoryKeys.toList())
            },
        ).flow.map { page -> page.map(CatalogItemEntity::toMedia) }
    }

    suspend fun search(
        accountId: String,
        query: String,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup {
        val escapedQuery = query.escapeLikePattern()
        val blocked = blockedCategoryKeys.forSqlNotIn()
        return CatalogLookup(
            channels = dao.searchChannels(accountId, escapedQuery, blocked, SEARCH_CHANNEL_LIMIT)
                .map(CatalogItemEntity::toChannel),
            media = dao.searchMedia(accountId, escapedQuery, blocked, SEARCH_MEDIA_LIMIT)
                .map(CatalogItemEntity::toMedia),
        )
    }

    suspend fun favourites(
        accountId: String,
        favouriteKeys: Set<String>,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup {
        if (favouriteKeys.isEmpty()) return CatalogLookup()
        val items = dao.favouriteItems(
            accountId = accountId,
            itemKeys = favouriteKeys.take(MAX_FAVOURITE_KEYS),
            blockedCategoryKeys = blockedCategoryKeys.forSqlNotIn(),
            limit = MAX_FAVOURITE_KEYS,
        )
        return CatalogLookup(
            channels = items.filter { it.kind == ContentKind.LIVE.name }.map(CatalogItemEntity::toChannel),
            media = items.filter { it.kind == ContentKind.MOVIE.name || it.kind == ContentKind.SERIES.name }
                .map(CatalogItemEntity::toMedia),
        )
    }

    suspend fun episodes(accountId: String, seriesId: String): List<Episode> =
        dao.episodes(accountId, seriesId).map(CatalogEpisodeEntity::toModel)

    fun programmes(
        accountId: String,
        channelEpgId: String,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<Programme>> = dao.programmes(
        accountId = accountId,
        channelEpgId = channelEpgId,
        windowStart = windowStart,
        windowEnd = windowEnd,
        limit = MAX_PROGRAMMES_PER_WINDOW,
    ).map { values -> values.map(CatalogProgrammeEntity::toModel) }

    suspend fun replaceProgrammes(accountId: String, programmesByEpgId: Map<String, List<Programme>>) {
        val generation = dao.activeGeneration(accountId) ?: return
        deleteBatches(
            load = { dao.activeProgrammes(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteProgrammes,
        )
        writeBatches(
            programmesByEpgId.retainedProgrammeEntities(accountId, generation),
            dao::insertProgrammes,
        )
    }

    suspend fun beginProgrammeImport(accountId: String): ProgrammeImportSession? {
        val generation = dao.activeGeneration(accountId) ?: return null
        deleteBatches(
            load = { dao.activeProgrammes(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteProgrammes,
        )
        return ProgrammeImportSession(accountId, generation)
    }

    inner class ProgrammeImportSession internal constructor(
        private val accountId: String,
        private val generation: String,
    ) {
        suspend fun write(programmes: List<Programme>) {
            require(programmes.size <= IMPORT_BATCH_SIZE) { "EPG import batch exceeds $IMPORT_BATCH_SIZE rows" }
            currentCoroutineContext().ensureActive()
            val entities = programmes.asSequence()
                .filterRetainedProgrammes()
                .map { it.toEntity(accountId, generation) }
                .toList()
            if (entities.isNotEmpty()) database.withTransaction { dao.insertProgrammes(entities) }
        }
    }

    suspend fun clearAccount(accountId: String) {
        deleteBatches(
            load = { dao.programmesForAccount(accountId, IMPORT_BATCH_SIZE) },
            delete = dao::deleteProgrammes,
        )
        deleteBatches(
            load = { dao.episodesForAccount(accountId, IMPORT_BATCH_SIZE) },
            delete = dao::deleteEpisodes,
        )
        deleteBatches(
            load = { dao.itemsForAccount(accountId, IMPORT_BATCH_SIZE) },
            delete = dao::deleteItems,
        )
        deleteBatches(
            load = { dao.categoriesForAccount(accountId, IMPORT_BATCH_SIZE) },
            delete = dao::deleteCategories,
        )
        database.withTransaction { dao.deleteImport(accountId) }
    }

    private suspend fun <T> writeBatches(values: Sequence<T>, insert: suspend (List<T>) -> Unit) {
        values.forEachDatabaseBatch { batch ->
            database.withTransaction { insert(batch) }
        }
    }

    private suspend fun deleteObsoleteRows(accountId: String, activeGeneration: String) {
        deleteBatches(
            load = { dao.obsoleteProgrammes(accountId, activeGeneration, IMPORT_BATCH_SIZE) },
            delete = dao::deleteProgrammes,
        )
        deleteBatches(
            load = { dao.obsoleteEpisodes(accountId, activeGeneration, IMPORT_BATCH_SIZE) },
            delete = dao::deleteEpisodes,
        )
        deleteBatches(
            load = { dao.obsoleteItems(accountId, activeGeneration, IMPORT_BATCH_SIZE) },
            delete = dao::deleteItems,
        )
        deleteBatches(
            load = { dao.obsoleteCategories(accountId, activeGeneration, IMPORT_BATCH_SIZE) },
            delete = dao::deleteCategories,
        )
    }

    private suspend fun deleteGeneration(accountId: String, generation: String) {
        deleteBatches(
            load = { dao.programmesInGeneration(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteProgrammes,
        )
        deleteBatches(
            load = { dao.episodesInGeneration(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteEpisodes,
        )
        deleteBatches(
            load = { dao.itemsInGeneration(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteItems,
        )
        deleteBatches(
            load = { dao.categoriesInGeneration(accountId, generation, IMPORT_BATCH_SIZE) },
            delete = dao::deleteCategories,
        )
    }

    private suspend fun <T> deleteBatches(
        load: suspend () -> List<T>,
        delete: suspend (List<T>) -> Unit,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = load()
            if (batch.isEmpty()) return
            database.withTransaction { delete(batch) }
        }
    }

    private fun pagingConfig() = PagingConfig(
        pageSize = PAGE_SIZE,
        initialLoadSize = INITIAL_LOAD_SIZE,
        maxSize = MAX_PAGING_CACHE_SIZE,
        enablePlaceholders = false,
    )

    companion object {
        private const val TAG = "RoomCatalogStore"
        internal const val IMPORT_BATCH_SIZE = 500
        internal const val INITIAL_LOAD_SIZE = 100
        internal const val PAGE_SIZE = 50
        internal const val MAX_PAGING_CACHE_SIZE = 300
        private const val MAX_PROGRAMMES_PER_WINDOW = 50
        internal const val PAST_EPG_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        internal const val FUTURE_EPG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private const val SEARCH_CHANNEL_LIMIT = 30
        private const val SEARCH_MEDIA_LIMIT = 60
        private const val MAX_FAVOURITE_KEYS = 500
    }
}

private fun Map<String, List<Programme>>.retainedProgrammeEntities(
    accountId: String,
    generation: String,
): Sequence<CatalogProgrammeEntity> {
    return asSequence().flatMap { (_, programmes) ->
        programmes.asSequence().filterRetainedProgrammes()
            .map { it.toEntity(accountId, generation) }
    }
}

private fun Sequence<Programme>.filterRetainedProgrammes(): Sequence<Programme> {
    val now = System.currentTimeMillis()
    val earliestEnd = now - RoomCatalogStore.PAST_EPG_RETENTION_MS
    val latestStart = now + RoomCatalogStore.FUTURE_EPG_RETENTION_MS
    return filter { it.endMs >= earliestEnd && it.startMs <= latestStart }
}

private fun String.escapeLikePattern(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun Set<String>.forSqlNotIn(): List<String> =
    if (isEmpty()) listOf("\u0000mars-no-blocked-category") else toList()

internal suspend fun <T> Sequence<T>.forEachDatabaseBatch(action: suspend (List<T>) -> Unit) {
    var batch = ArrayList<T>(RoomCatalogStore.IMPORT_BATCH_SIZE)
    for (value in this) {
        currentCoroutineContext().ensureActive()
        batch.add(value)
        if (batch.size == RoomCatalogStore.IMPORT_BATCH_SIZE) {
            action(batch)
            batch = ArrayList(RoomCatalogStore.IMPORT_BATCH_SIZE)
        }
    }
    if (batch.isNotEmpty()) action(batch)
}

private fun Category.toEntity(accountId: String, generation: String) = CatalogCategoryEntity(
    accountId = accountId,
    generation = generation,
    categoryKey = key,
    remoteId = remoteId,
    name = name,
    kind = kind.name,
)

private fun Channel.toEntity(generation: String) = CatalogItemEntity(
    accountId = accountId,
    generation = generation,
    itemKey = key,
    remoteId = remoteId,
    title = name,
    kind = ContentKind.LIVE.name,
    categoryKey = categoryKey,
    categoryName = categoryName,
    artworkUrl = logoUrl,
    backdropUrl = "",
    playbackUrl = playbackUrl,
    description = "",
    rating = "",
    year = "",
    seriesId = "",
    epgId = epgId,
    supportsCatchUp = supportsCatchUp,
    catchUpDays = catchUpDays,
    catchUpTemplate = catchUpTemplate,
)

private fun MediaContent.toEntity(generation: String) = CatalogItemEntity(
    accountId = accountId,
    generation = generation,
    itemKey = key,
    remoteId = remoteId,
    title = title,
    kind = kind.name,
    categoryKey = categoryKey,
    categoryName = categoryName,
    artworkUrl = artworkUrl,
    backdropUrl = backdropUrl,
    playbackUrl = playbackUrl,
    description = description,
    rating = rating,
    year = year,
    seriesId = seriesId,
    epgId = "",
    supportsCatchUp = false,
    catchUpDays = 0,
    catchUpTemplate = "",
)

private fun Episode.toEntity(generation: String, seriesId: String) = CatalogEpisodeEntity(
    accountId = accountId,
    generation = generation,
    episodeKey = key,
    seriesId = seriesId,
    remoteId = remoteId,
    title = title,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    playbackUrl = playbackUrl,
    artworkUrl = artworkUrl,
    description = description,
    durationText = durationText,
)

private fun Programme.toEntity(accountId: String, generation: String) = CatalogProgrammeEntity(
    accountId = accountId,
    generation = generation,
    channelEpgId = channelEpgId,
    title = title,
    description = description,
    startMs = startMs,
    endMs = endMs,
)

private fun CatalogCategoryEntity.toModel() = Category(categoryKey, remoteId, name, ContentKind.valueOf(kind))

private fun CatalogItemEntity.toChannel() = Channel(
    key = itemKey,
    remoteId = remoteId,
    accountId = accountId,
    name = title,
    categoryKey = categoryKey,
    categoryName = categoryName,
    logoUrl = artworkUrl,
    epgId = epgId,
    playbackUrl = playbackUrl,
    supportsCatchUp = supportsCatchUp,
    catchUpDays = catchUpDays,
    catchUpTemplate = catchUpTemplate,
)

private fun CatalogItemEntity.toMedia() = MediaContent(
    key = itemKey,
    remoteId = remoteId,
    accountId = accountId,
    title = title,
    kind = ContentKind.valueOf(kind),
    categoryKey = categoryKey,
    categoryName = categoryName,
    artworkUrl = artworkUrl,
    backdropUrl = backdropUrl,
    playbackUrl = playbackUrl,
    description = description,
    rating = rating,
    year = year,
    seriesId = seriesId,
)

private fun CatalogEpisodeEntity.toModel() = Episode(
    key = episodeKey,
    remoteId = remoteId,
    accountId = accountId,
    title = title,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    playbackUrl = playbackUrl,
    artworkUrl = artworkUrl,
    description = description,
    durationText = durationText,
)

private fun CatalogProgrammeEntity.toModel() = Programme(channelEpgId, title, description, startMs, endMs)
