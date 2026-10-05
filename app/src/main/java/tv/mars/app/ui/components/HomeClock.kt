package tv.mars.app.ui.components

import android.view.Gravity
import android.widget.TextClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import tv.mars.app.ui.theme.MarsMuted

@Composable
fun HomeClock(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextClock(context).apply {
                textSize = 16f
                setTextColor(MarsMuted.toArgb())
                gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                isFocusable = false
                isClickable = false
                setSingleLine()
            }
        },
    )
}
