package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.ContentKind
import java.time.Instant

class EntitlementPolicyTest {
    @Test
    fun `free policy enforces product limits`() {
        val policy = EntitlementPolicy(managerWith(emptySet()))

        assertTrue(policy.canAddAccount(0))
        assertFalse(policy.canAddAccount(1))
        assertTrue(policy.canAddFavourite(19))
        assertFalse(policy.canAddFavourite(20))
        assertFalse(policy.canUseMultipleProfiles())
        assertFalse(policy.canUseParentalControls())
        assertFalse(policy.canUseFullEpg())
        assertFalse(policy.canSearchGlobally())
        assertEquals(0L, policy.resumePosition(42_000L))
        assertEquals(EntitlementPolicy.FREE_PLAYBACK_LIMIT_MS, policy.playbackLimitMs(ContentKind.MOVIE))
        assertEquals(EntitlementPolicy.FREE_PLAYBACK_LIMIT_MS, policy.playbackLimitMs(ContentKind.EPISODE))
        assertEquals(0L, policy.playbackLimitMs(ContentKind.LIVE))
    }

    @Test
    fun `pro policy grants only licensed capabilities`() {
        val policy = EntitlementPolicy(
            managerWith(
                setOf(
                    ProFeature.MULTIPLE_PROFILES,
                    ProFeature.PARENTAL_CONTROLS,
                    ProFeature.CONTINUE_WATCHING,
                    ProFeature.UNRESTRICTED_VOD_PLAYBACK,
                    ProFeature.FULL_EPG,
                    ProFeature.GLOBAL_SEARCH,
                ),
            ),
        )

        assertTrue(policy.canUseMultipleProfiles())
        assertTrue(policy.canUseParentalControls())
        assertTrue(policy.canUseFullEpg())
        assertTrue(policy.canSearchGlobally())
        assertEquals(42_000L, policy.resumePosition(42_000L))
        assertEquals(0L, policy.playbackLimitMs(ContentKind.MOVIE))
        assertEquals(EntitlementPolicy.FREE_PLAYBACK_LIMIT_MS, policy.playbackLimitMs(ContentKind.EPISODE))
    }

    private fun managerWith(features: Set<ProFeature>): EntitlementManager {
        val state = if (features.isEmpty()) {
            EntitlementState.Free
        } else {
            EntitlementState.Pro("plan", features, "device", "license", 1, Instant.MAX)
        }
        return DefaultEntitlementManager(FakeProvider(state))
    }

    private class FakeProvider(initial: EntitlementState) : EntitlementProvider {
        private val current = MutableStateFlow(initial)
        override val state: StateFlow<EntitlementState> = current
        override suspend fun refresh(reason: RefreshReason) = EntitlementResult.Unchanged(current.value)
        override suspend fun restore() = EntitlementResult.Unchanged(current.value)
    }
}
