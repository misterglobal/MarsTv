package tv.mars.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MarsMidnight = Color(0xFF070A12)
val MarsSurface = Color(0xFF111827)
val MarsSurfaceRaised = Color(0xFF1A2333)
val MarsRed = Color(0xFFFF5A4F)
val MarsViolet = Color(0xFF7C5CFF)
val MarsWhite = Color(0xFFF8FAFC)
val MarsMuted = Color(0xFF94A3B8)
val MarsSuccess = Color(0xFF34D399)

private val MarsColors = darkColorScheme(
    primary = MarsRed,
    onPrimary = Color.White,
    secondary = MarsViolet,
    onSecondary = Color.White,
    background = MarsMidnight,
    onBackground = MarsWhite,
    surface = MarsSurface,
    onSurface = MarsWhite,
    surfaceVariant = MarsSurfaceRaised,
    onSurfaceVariant = MarsMuted,
    error = Color(0xFFFF7B72),
)

@Composable
fun MarsTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MarsColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
