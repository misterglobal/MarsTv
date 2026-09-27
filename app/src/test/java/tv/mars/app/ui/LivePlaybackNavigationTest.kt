package tv.mars.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.mars.app.core.*
import tv.mars.app.entitlement.EntitlementState

class LivePlaybackNavigationTest {
    private val account = IptvAccount(id = "account", name = "Test", sourceType = SourceType.XTREAM)
    private val request = PlayerRequest("channel", account.id, "Live channel", "https://example.test/live", ContentKind.LIVE)
    private val state = MarsUiState(
        local = LocalState(accounts = listOf(account), activeAccountId = account.id),
        destination = MainDestination.SEARCH, overlay = OverlayScreen.PLAYER,
        playerRequest = request, entitlementState = EntitlementState.Free,
    )

    @Test fun `back from live search playback preserves the stream without a Pro preview grant`() {
        assertFalse(state.guidePreviewEnabled)
        val minimized = state.returnToLiveGuide()
        assertEquals(MainDestination.LIVE, minimized.destination)
        assertEquals(OverlayScreen.NONE, minimized.overlay)
        assertSame(request, minimized.playerRequest)
        assertTrue(minimized.canReturnToLiveGuide)
    }

    @Test fun `movies and streams from another account cannot become a live preview`() {
        listOf(state.copy(playerRequest = request.copy(kind = ContentKind.MOVIE)),
            state.copy(playerRequest = request.copy(accountId = "other")), state.copy(playerRequest = null))
            .forEach { assertFalse(it.canReturnToLiveGuide); assertSame(it, it.returnToLiveGuide()) }
    }
}
