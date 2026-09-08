package tv.mars.app.entitlement

import tv.mars.app.core.ContentKind

class EntitlementPolicy(private val manager: EntitlementManager) {
    fun canAddAccount(existingCount: Int): Boolean =
        existingCount == 0 || manager.hasFeature(ProFeature.MULTIPLE_ACCOUNTS)

    fun canUseMultipleProfiles(): Boolean = manager.hasFeature(ProFeature.MULTIPLE_PROFILES)

    fun canUseParentalControls(): Boolean = manager.hasFeature(ProFeature.PARENTAL_CONTROLS)

    fun canUseFullEpg(): Boolean = manager.hasFeature(ProFeature.FULL_EPG)

    fun canSearchGlobally(): Boolean = manager.hasFeature(ProFeature.GLOBAL_SEARCH)

    fun canAddFavourite(existingCount: Int): Boolean =
        existingCount < FREE_FAVOURITE_LIMIT || manager.hasFeature(ProFeature.FAVORITE_GROUPS)

    fun resumePosition(positionMs: Long): Long =
        if (manager.hasFeature(ProFeature.CONTINUE_WATCHING)) positionMs else 0L

    fun playbackLimitMs(kind: ContentKind): Long = when (kind) {
        ContentKind.MOVIE -> if (manager.hasFeature(ProFeature.UNRESTRICTED_VOD_PLAYBACK)) 0L else FREE_PLAYBACK_LIMIT_MS
        ContentKind.EPISODE -> if (manager.hasFeature(ProFeature.UNRESTRICTED_SERIES_PLAYBACK)) 0L else FREE_PLAYBACK_LIMIT_MS
        else -> 0L
    }

    companion object {
        const val FREE_FAVOURITE_LIMIT = 20
        const val FREE_HISTORY_LIMIT = 20
        const val FREE_PLAYBACK_LIMIT_MS = 10 * 60 * 1_000L
    }
}
