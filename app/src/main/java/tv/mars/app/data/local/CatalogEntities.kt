package tv.mars.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "catalog_imports", primaryKeys = ["account_id"])
data class CatalogImportEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "active_generation") val activeGeneration: String,
    @ColumnInfo(name = "loaded_at") val loadedAt: Long,
)

@Entity(
    tableName = "catalog_categories",
    primaryKeys = ["account_id", "generation", "category_key"],
    indices = [Index(value = ["account_id", "generation", "kind", "name"])],
)
data class CatalogCategoryEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val generation: String,
    @ColumnInfo(name = "category_key") val categoryKey: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    val name: String,
    val kind: String,
)

@Entity(
    tableName = "catalog_items",
    primaryKeys = ["account_id", "generation", "item_key"],
    indices = [
        Index(value = ["account_id", "generation", "kind", "title"]),
        Index(value = ["account_id", "generation", "kind", "category_key", "title"]),
        Index(value = ["account_id", "generation", "epg_id"]),
    ],
)
data class CatalogItemEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val generation: String,
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    val title: String,
    val kind: String,
    @ColumnInfo(name = "category_key") val categoryKey: String,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String,
    @ColumnInfo(name = "backdrop_url") val backdropUrl: String,
    @ColumnInfo(name = "playback_url") val playbackUrl: String,
    val description: String,
    val rating: String,
    val year: String,
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "epg_id") val epgId: String,
    @ColumnInfo(name = "supports_catch_up") val supportsCatchUp: Boolean,
    @ColumnInfo(name = "catch_up_days") val catchUpDays: Int,
    @ColumnInfo(name = "catch_up_template") val catchUpTemplate: String,
)

@Entity(
    tableName = "catalog_episodes",
    primaryKeys = ["account_id", "generation", "episode_key"],
    indices = [Index(value = ["account_id", "generation", "series_id", "season_number", "episode_number"])],
)
data class CatalogEpisodeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val generation: String,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    @ColumnInfo(name = "series_id") val seriesId: String,
    @ColumnInfo(name = "remote_id") val remoteId: String,
    val title: String,
    @ColumnInfo(name = "season_number") val seasonNumber: Int,
    @ColumnInfo(name = "episode_number") val episodeNumber: Int,
    @ColumnInfo(name = "playback_url") val playbackUrl: String,
    @ColumnInfo(name = "artwork_url") val artworkUrl: String,
    val description: String,
    @ColumnInfo(name = "duration_text") val durationText: String,
)

@Entity(
    tableName = "catalog_programmes",
    primaryKeys = ["account_id", "generation", "channel_epg_id", "start_ms"],
    indices = [Index(value = ["account_id", "generation", "channel_epg_id", "end_ms", "start_ms"])],
)
data class CatalogProgrammeEntity(
    @ColumnInfo(name = "account_id") val accountId: String,
    val generation: String,
    @ColumnInfo(name = "channel_epg_id") val channelEpgId: String,
    val title: String,
    val description: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)
