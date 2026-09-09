package tv.mars.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.LocalState
import tv.mars.app.core.ContentKind
import tv.mars.app.core.ViewerProfile
import tv.mars.app.core.WatchRecord
import tv.mars.app.entitlement.EntitlementState
import tv.mars.app.entitlement.ProFeature
import java.time.Instant

class EntitlementUiStateTest {
    @Test
    fun `free state preserves but limits favourites and history`() {
        val favourites = (1..25).map { "item-$it" }.toSet()
        val history = (1..25).map {
            WatchRecord(
                contentKey = "item-$it",
                accountId = "account",
                title = "Item $it",
                playbackUrl = "https://example.test/$it",
                kind = ContentKind.MOVIE,
                watchedAt = it.toLong(),
            )
        }
        val profile = ViewerProfile(id = "profile", name = "Main", restrictedCategoryKeys = setOf("adult"))
        val state = MarsUiState(
            local = LocalState(
                profiles = listOf(profile),
                activeProfileId = profile.id,
                favouriteKeysByProfile = mapOf(profile.id to favourites),
                watchHistoryByProfile = mapOf(profile.id to history),
            ),
            entitlementState = EntitlementState.Free,
        )

        assertEquals(20, state.favouriteKeys.size)
        assertEquals(5, state.lockedFavouriteKeys.size)
        assertEquals((6L..25L).reversed().toList(), state.watchHistory.map(WatchRecord::watchedAt))
        assertTrue(state.continueWatching.isEmpty())
        assertFalse(state.parentalControlsEnabled)
    }

    @Test
    fun `licensed state exposes complete local data`() {
        val profile = ViewerProfile(id = "profile", name = "Main")
        val favourites = (1..25).map { "item-$it" }.toSet()
        val state = MarsUiState(
            local = LocalState(
                profiles = listOf(profile),
                activeProfileId = profile.id,
                favouriteKeysByProfile = mapOf(profile.id to favourites),
            ),
            entitlementState = EntitlementState.Pro(
                "plan",
                setOf(ProFeature.FAVORITE_GROUPS, ProFeature.PARENTAL_CONTROLS),
                "device",
                "license",
                1,
                Instant.MAX,
            ),
        )

        assertEquals(favourites, state.favouriteKeys)
        assertTrue(state.lockedFavouriteKeys.isEmpty())
        assertTrue(state.parentalControlsEnabled)
    }
}
