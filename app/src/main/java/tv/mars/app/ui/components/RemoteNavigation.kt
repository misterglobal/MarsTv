package tv.mars.app.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo

/** Establish TV focus before OK arrives, without replacing an existing child focus target. */
@Composable
fun RemoteNavigation(
    isTelevision: Boolean,
    screenKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isTelevision, screenKey, windowFocused) {
        if (isTelevision && windowFocused) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            if (!hasFocus) requester.requestFocus()
        }
    }
    Box(
        modifier.focusRequester(requester).onFocusChanged { hasFocus = it.hasFocus }.focusGroup(),
        content = content,
    )
}
