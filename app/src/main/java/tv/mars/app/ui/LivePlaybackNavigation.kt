package tv.mars.app.ui

import tv.mars.app.core.ContentKind
import tv.mars.app.core.MainDestination
import tv.mars.app.core.OverlayScreen

/** In-app minimization keeps the current live stream; it does not use Android PiP. */
internal val MarsUiState.canReturnToLiveGuide: Boolean
    get() = playerRequest?.let { it.kind == ContentKind.LIVE && it.accountId == activeAccount?.id } == true

internal fun MarsUiState.returnToLiveGuide(): MarsUiState =
    if (canReturnToLiveGuide) copy(destination = MainDestination.LIVE, overlay = OverlayScreen.NONE) else this
