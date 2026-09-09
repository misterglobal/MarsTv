package tv.mars.app.core

import kotlinx.serialization.Serializable
import java.net.URI
import java.util.UUID

@Serializable
enum class SourceType { PRIVATE_XTREAM, XTREAM, M3U }

@Serializable
enum class ContentKind { LIVE, MOVIE, SERIES, EPISODE }

@Serializable
data class IptvAccount(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sourceType: SourceType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    val sourceLabel: String
        get() = when (sourceType) {
            SourceType.PRIVATE_XTREAM -> "MarsTV login"
            SourceType.XTREAM -> "Xtream"
            SourceType.M3U -> "M3U playlist"
        }

    val safeHost: String
        get() = runCatching {
            val raw = if (sourceType == SourceType.M3U) m3uUrl else serverUrl
            URI(raw).host ?: "Custom source"
        }.getOrDefault("Custom source")
}

@Serializable
data class ViewerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val avatarIndex: Int = 0,
    val pinHash: String = "",
    val restrictedCategoryKeys: Set<String> = emptySet(),
)

@Serializable
data class WatchRecord(
    val contentKey: String,
    val accountId: String,
    val title: String,
    val playbackUrl: String,
    val artworkUrl: String = "",
    val kind: ContentKind,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    val canContinue: Boolean
        get() = kind != ContentKind.LIVE && durationMs > 0 && positionMs in 30_000 until (durationMs * 0.92).toLong()
}

@Serializable
data class LocalState(
    val accounts: List<IptvAccount> = emptyList(),
    val activeAccountId: String? = null,
    val profiles: List<ViewerProfile> = emptyList(),
    val activeProfileId: String? = null,
    val favouriteKeysByProfile: Map<String, Set<String>> = emptyMap(),
    val watchHistoryByProfile: Map<String, List<WatchRecord>> = emptyMap(),
    val entitlementMigrationVersion: Int = 0,
)

@Serializable
data class Category(
    val key: String,
    val remoteId: String,
    val name: String,
    val kind: ContentKind,
)

@Serializable
data class Channel(
    val key: String,
    val remoteId: String,
    val accountId: String,
    val name: String,
    val categoryKey: String,
    val categoryName: String,
    val logoUrl: String,
    val epgId: String,
    val playbackUrl: String,
    val supportsCatchUp: Boolean = false,
    val catchUpDays: Int = 0,
    val catchUpTemplate: String = "",
)

@Serializable
data class Programme(
    val channelEpgId: String,
    val title: String,
    val description: String,
    val startMs: Long,
    val endMs: Long,
) {
    val isLive: Boolean get() = System.currentTimeMillis() in startMs until endMs
    val isPast: Boolean get() = endMs <= System.currentTimeMillis()
}

@Serializable
data class MediaContent(
    val key: String,
    val remoteId: String,
    val accountId: String,
    val title: String,
    val kind: ContentKind,
    val categoryKey: String,
    val categoryName: String,
    val artworkUrl: String = "",
    val backdropUrl: String = "",
    val playbackUrl: String = "",
    val description: String = "",
    val rating: String = "",
    val year: String = "",
    val seriesId: String = "",
)

@Serializable
data class Episode(
    val key: String,
    val remoteId: String,
    val accountId: String,
    val title: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val playbackUrl: String,
    val artworkUrl: String = "",
    val description: String = "",
    val durationText: String = "",
)

@Serializable
data class SeriesDetails(
    val series: MediaContent,
    val episodesBySeason: Map<Int, List<Episode>>,
)

@Serializable
data class CatalogBundle(
    val accountId: String,
    val liveCategories: List<Category> = emptyList(),
    val movieCategories: List<Category> = emptyList(),
    val seriesCategories: List<Category> = emptyList(),
    val channels: List<Channel> = emptyList(),
    val movies: List<MediaContent> = emptyList(),
    val series: List<MediaContent> = emptyList(),
    val programmesByEpgId: Map<String, List<Programme>> = emptyMap(),
    val loadedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun empty(accountId: String = "") = CatalogBundle(accountId = accountId)
    }
}

data class CatalogLookup(
    val channels: List<Channel> = emptyList(),
    val media: List<MediaContent> = emptyList(),
)

data class PlayerRequest(
    val contentKey: String,
    val accountId: String,
    val title: String,
    val url: String,
    val kind: ContentKind,
    val artworkUrl: String = "",
    val resumePositionMs: Long = 0,
    val playbackLimitMs: Long = 0,
)

enum class MainDestination(val label: String) {
    LIVE("Live TV"),
    MOVIES("Movies"),
    SERIES("Series"),
    SEARCH("Search"),
    LIBRARY("My TV"),
    SETTINGS("Settings"),
}

enum class OverlayScreen { NONE, ADD_ACCOUNT, PROFILES, SERIES_DETAILS, PLAYER, UPGRADE }
