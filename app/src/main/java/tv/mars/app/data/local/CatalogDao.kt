package tv.mars.app.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Query("SELECT EXISTS(SELECT 1 FROM catalog_imports WHERE account_id = :accountId)")
    suspend fun hasActiveImport(accountId: String): Boolean

    @Query("SELECT active_generation FROM catalog_imports WHERE account_id = :accountId")
    suspend fun activeGeneration(accountId: String): String?

    @Query("SELECT loaded_at FROM catalog_imports WHERE account_id = :accountId")
    suspend fun catalogLoadedAt(accountId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImport(value: CatalogImportEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(values: List<CatalogCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(values: List<CatalogItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(values: List<CatalogEpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(values: List<CatalogProgrammeEntity>)

    @Query(
        """SELECT categories.* FROM catalog_categories AS categories
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = categories.account_id
         AND imports.active_generation = categories.generation
        WHERE categories.account_id = :accountId AND categories.kind = :kind
        ORDER BY categories.name COLLATE NOCASE, categories.category_key""",
    )
    fun observeCategories(accountId: String, kind: String): Flow<List<CatalogCategoryEntity>>

    @Query(
        """SELECT items.epg_id, items.title FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId AND items.kind = 'LIVE'""",
    )
    suspend fun channelReferences(accountId: String): List<CatalogChannelReference>

    @Query(
        """SELECT items.* FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId
          AND items.kind = :kind
          AND (:categoryKey IS NULL OR items.category_key = :categoryKey)
        ORDER BY items.title COLLATE NOCASE, items.item_key""",
    )
    fun pagingItems(accountId: String, kind: String, categoryKey: String?): PagingSource<Int, CatalogItemEntity>

    @Query(
        """SELECT items.* FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId
          AND items.kind = :kind
          AND (:categoryKey IS NULL OR items.category_key = :categoryKey)
          AND items.category_key NOT IN (:blockedCategoryKeys)
        ORDER BY items.title COLLATE NOCASE, items.item_key""",
    )
    fun pagingItemsExcluding(
        accountId: String,
        kind: String,
        categoryKey: String?,
        blockedCategoryKeys: List<String>,
    ): PagingSource<Int, CatalogItemEntity>

    @Query(
        """SELECT items.* FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId
          AND items.kind = 'LIVE'
          AND items.category_key NOT IN (:blockedCategoryKeys)
          AND (items.title LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE
            OR items.category_name LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE)
        ORDER BY items.title COLLATE NOCASE, items.item_key
        LIMIT :limit""",
    )
    suspend fun searchChannels(
        accountId: String,
        query: String,
        blockedCategoryKeys: List<String>,
        limit: Int,
    ): List<CatalogItemEntity>

    @Query(
        """SELECT items.* FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId
          AND items.kind IN ('MOVIE', 'SERIES')
          AND items.category_key NOT IN (:blockedCategoryKeys)
          AND (items.title LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE
            OR items.category_name LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE
            OR items.year LIKE '%' || :query || '%' ESCAPE '\' COLLATE NOCASE)
        ORDER BY items.title COLLATE NOCASE, items.item_key
        LIMIT :limit""",
    )
    suspend fun searchMedia(
        accountId: String,
        query: String,
        blockedCategoryKeys: List<String>,
        limit: Int,
    ): List<CatalogItemEntity>

    @Query(
        """SELECT items.* FROM catalog_items AS items
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = items.account_id
         AND imports.active_generation = items.generation
        WHERE items.account_id = :accountId
          AND items.item_key IN (:itemKeys)
          AND items.category_key NOT IN (:blockedCategoryKeys)
        ORDER BY items.title COLLATE NOCASE, items.item_key
        LIMIT :limit""",
    )
    suspend fun favouriteItems(
        accountId: String,
        itemKeys: List<String>,
        blockedCategoryKeys: List<String>,
        limit: Int,
    ): List<CatalogItemEntity>

    @Query(
        """SELECT episodes.* FROM catalog_episodes AS episodes
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = episodes.account_id
         AND imports.active_generation = episodes.generation
        WHERE episodes.account_id = :accountId AND episodes.series_id = :seriesId
        ORDER BY episodes.season_number, episodes.episode_number, episodes.title COLLATE NOCASE""",
    )
    suspend fun episodes(accountId: String, seriesId: String): List<CatalogEpisodeEntity>

    @Query(
        """SELECT programmes.* FROM catalog_programmes AS programmes
        INNER JOIN catalog_imports AS imports
          ON imports.account_id = programmes.account_id
         AND imports.active_generation = programmes.generation
        WHERE programmes.account_id = :accountId
          AND programmes.channel_epg_id = :channelEpgId
          AND programmes.end_ms > :windowStart
          AND programmes.start_ms < :windowEnd
        ORDER BY programmes.start_ms
        LIMIT :limit""",
    )
    fun programmes(
        accountId: String,
        channelEpgId: String,
        windowStart: Long,
        windowEnd: Long,
        limit: Int,
    ): Flow<List<CatalogProgrammeEntity>>

    @Query("SELECT * FROM catalog_categories WHERE account_id = :accountId AND generation != :activeGeneration LIMIT :limit")
    suspend fun obsoleteCategories(accountId: String, activeGeneration: String, limit: Int): List<CatalogCategoryEntity>

    @Query("SELECT * FROM catalog_items WHERE account_id = :accountId AND generation != :activeGeneration LIMIT :limit")
    suspend fun obsoleteItems(accountId: String, activeGeneration: String, limit: Int): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_episodes WHERE account_id = :accountId AND generation != :activeGeneration LIMIT :limit")
    suspend fun obsoleteEpisodes(accountId: String, activeGeneration: String, limit: Int): List<CatalogEpisodeEntity>

    @Query("SELECT * FROM catalog_programmes WHERE account_id = :accountId AND generation != :activeGeneration LIMIT :limit")
    suspend fun obsoleteProgrammes(accountId: String, activeGeneration: String, limit: Int): List<CatalogProgrammeEntity>

    @Query("SELECT * FROM catalog_categories WHERE account_id = :accountId AND generation = :generation LIMIT :limit")
    suspend fun categoriesInGeneration(accountId: String, generation: String, limit: Int): List<CatalogCategoryEntity>

    @Query("SELECT * FROM catalog_items WHERE account_id = :accountId AND generation = :generation LIMIT :limit")
    suspend fun itemsInGeneration(accountId: String, generation: String, limit: Int): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_episodes WHERE account_id = :accountId AND generation = :generation LIMIT :limit")
    suspend fun episodesInGeneration(accountId: String, generation: String, limit: Int): List<CatalogEpisodeEntity>

    @Query("SELECT * FROM catalog_programmes WHERE account_id = :accountId AND generation = :generation LIMIT :limit")
    suspend fun programmesInGeneration(accountId: String, generation: String, limit: Int): List<CatalogProgrammeEntity>

    @Query("SELECT * FROM catalog_categories WHERE account_id = :accountId LIMIT :limit")
    suspend fun categoriesForAccount(accountId: String, limit: Int): List<CatalogCategoryEntity>

    @Query("SELECT * FROM catalog_items WHERE account_id = :accountId LIMIT :limit")
    suspend fun itemsForAccount(accountId: String, limit: Int): List<CatalogItemEntity>

    @Query("SELECT * FROM catalog_episodes WHERE account_id = :accountId LIMIT :limit")
    suspend fun episodesForAccount(accountId: String, limit: Int): List<CatalogEpisodeEntity>

    @Query("SELECT * FROM catalog_programmes WHERE account_id = :accountId LIMIT :limit")
    suspend fun programmesForAccount(accountId: String, limit: Int): List<CatalogProgrammeEntity>

    @Delete suspend fun deleteCategories(values: List<CatalogCategoryEntity>)
    @Delete suspend fun deleteItems(values: List<CatalogItemEntity>)
    @Delete suspend fun deleteEpisodes(values: List<CatalogEpisodeEntity>)
    @Delete suspend fun deleteProgrammes(values: List<CatalogProgrammeEntity>)

    @Query("DELETE FROM catalog_programmes WHERE account_id = :accountId AND generation = :generation")
    suspend fun deleteProgrammeGeneration(accountId: String, generation: String)

    @Query(
        """UPDATE catalog_programmes SET generation = :targetGeneration
        WHERE account_id = :accountId AND generation = :sourceGeneration""",
    )
    suspend fun moveProgrammeGeneration(accountId: String, sourceGeneration: String, targetGeneration: String)

    @Query("DELETE FROM catalog_imports WHERE account_id = :accountId")
    suspend fun deleteImport(accountId: String)

    @Query("DELETE FROM catalog_imports WHERE account_id = :accountId AND active_generation = :generation")
    suspend fun deleteImportGeneration(accountId: String, generation: String)

}
