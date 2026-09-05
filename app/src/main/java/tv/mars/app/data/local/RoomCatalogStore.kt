package tv.mars.app.data.local

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.MediaContent
import tv.mars.app.core.Programme
import java.util.UUID

class RoomCatalogStore(private val database: MarsTvDatabase) {
    private val dao = database.catalogDao()

    suspend fun replaceCatalog(
        catalog: CatalogBundle,
        episodesBySeriesId: Map<String, List<Episode>> = emptyMap(),
    ) {
        val generation = UUID.randomUUID().toString()
        val accountId = catalog.accountId

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
            catalog.programmesByEpgId.asSequence().flatMap { (_, programmes) ->
                programmes.asSequence().map { it.toEntity(accountId, generation) }
            },
            dao::insertProgrammes,
        )

        currentCoroutineContext().ensureActive()
        database.withTransaction {
            dao.insertImport(CatalogImportEntity(accountId, generation, catalog.loadedAt))
        }
        deleteObsoleteRows(accountId, generation)
    }

    fun observeCategories(accountId: String, kind: ContentKind): Flow<List<Category>> =
        dao.observeCategories(accountId, kind.name).map { values -> values.map(CatalogCategoryEntity::toModel) }

    fun pagedChannels(accountId: String, categoryKey: String? = null): Flow<PagingData<Channel>> = Pager(
        config = pagingConfig(),
        pagingSourceFactory = { dao.pagingItems(accountId, ContentKind.LIVE.name, categoryKey) },
    ).flow.map { page -> page.map(CatalogItemEntity::toChannel) }

    fun pagedMedia(
        accountId: String,
        kind: ContentKind,
        categoryKey: String? = null,
    ): Flow<PagingData<MediaContent>> {
        require(kind == ContentKind.MOVIE || kind == ContentKind.SERIES)
        return Pager(
            config = pagingConfig(),
            pagingSourceFactory = { dao.pagingItems(accountId, kind.name, categoryKey) },
        ).flow.map { page -> page.map(CatalogItemEntity::toMedia) }
    }

    suspend fun episodes(accountId: String, seriesId: String): List<Episode> =
        dao.episodes(accountId, seriesId).map(CatalogEpisodeEntity::toModel)

    suspend fun programmes(
        accountId: String,
        channelEpgId: String,
        windowStart: Long,
        windowEnd: Long,
    ): List<Programme> = dao.programmes(
        accountId = accountId,
        channelEpgId = channelEpgId,
        windowStart = windowStart,
        windowEnd = windowEnd,
        limit = MAX_PROGRAMMES_PER_WINDOW,
    ).map(CatalogProgrammeEntity::toModel)

    suspend fun clearAccount(accountId: String) = database.withTransaction {
        dao.deleteAllProgrammes(accountId)
        dao.deleteAllEpisodes(accountId)
        dao.deleteAllItems(accountId)
        dao.deleteAllCategories(accountId)
        dao.deleteImport(accountId)
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
        internal const val IMPORT_BATCH_SIZE = 500
        internal const val INITIAL_LOAD_SIZE = 100
        internal const val PAGE_SIZE = 50
        internal const val MAX_PAGING_CACHE_SIZE = 300
        private const val MAX_PROGRAMMES_PER_WINDOW = 50
    }
}

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
