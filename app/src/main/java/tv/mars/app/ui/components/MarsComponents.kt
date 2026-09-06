package tv.mars.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.mars.app.core.MediaContent
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsSurfaceRaised
import tv.mars.app.ui.theme.MarsViolet
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun MarsLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(if (compact) 34.dp else 46.dp)) {
            val radius = size.minDimension * 0.30f
            drawCircle(MarsRed, radius = radius, center = center)
            drawArc(
                color = Color.White.copy(alpha = 0.9f),
                startAngle = 195f,
                sweepAngle = 255f,
                useCenter = false,
                topLeft = Offset(size.width * 0.04f, size.height * 0.28f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.92f, size.height * 0.44f),
                style = Stroke(width = size.width * 0.055f),
            )
            drawLine(
                color = MarsMidnight,
                start = Offset(center.x - radius * 0.45f, center.y + radius * 0.35f),
                end = Offset(center.x - radius * 0.45f, center.y - radius * 0.35f),
                strokeWidth = radius * 0.32f,
            )
            drawLine(
                color = MarsMidnight,
                start = Offset(center.x - radius * 0.45f, center.y - radius * 0.35f),
                end = Offset(center.x, center.y + radius * 0.20f),
                strokeWidth = radius * 0.24f,
            )
            drawLine(
                color = MarsMidnight,
                start = Offset(center.x, center.y + radius * 0.20f),
                end = Offset(center.x + radius * 0.45f, center.y - radius * 0.35f),
                strokeWidth = radius * 0.24f,
            )
            drawLine(
                color = MarsMidnight,
                start = Offset(center.x + radius * 0.45f, center.y - radius * 0.35f),
                end = Offset(center.x + radius * 0.45f, center.y + radius * 0.35f),
                strokeWidth = radius * 0.32f,
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Text(
            text = "Mars",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MarsWhite,
        )
        Text(
            text = "TV",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MarsRed,
        )
    }
}

@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused -> MarsRed
        selected -> MarsViolet
        else -> Color.Transparent
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .border(if (focused || selected) 2.dp else 0.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = if (focused) MarsSurfaceRaised else MarsSurface,
        tonalElevation = if (focused) 6.dp else 0.dp,
        content = content,
    )
}

@Composable
fun MarsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MarsRed,
            contentColor = Color.White,
            disabledContainerColor = MarsSurfaceRaised,
            disabledContentColor = MarsMuted,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PosterCard(
    item: MediaContent,
    favourite: Boolean,
    isTelevision: Boolean,
    onClick: () -> Unit,
    onFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = if (isTelevision) 174.dp else 142.dp
    val height = width * 1.48f
    Column(modifier = modifier.width(width)) {
        FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(height)) {
            Box(Modifier.fillMaxSize()) {
                if (item.artworkUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(MarsSurfaceRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.title.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            color = MarsMuted,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MarsMidnight.copy(alpha = 0.78f)),
                ) {
                    IconButton(onClick = onFavourite, modifier = Modifier.size(38.dp)) {
                        Icon(
                            imageVector = if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (favourite) "Remove favourite" else "Add favourite",
                            tint = if (favourite) MarsRed else MarsWhite,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MarsWhite,
        )
        if (item.year.isNotBlank() || item.rating.isNotBlank()) {
            Text(
                text = listOf(item.year, item.rating.takeIf(String::isNotBlank)?.let { "★ $it" }.orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString("  •  "),
                style = MaterialTheme.typography.labelSmall,
                color = MarsMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = !message.isNullOrBlank(), modifier = modifier) {
        Surface(
            onClick = onDismiss,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.16f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = message.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
fun LoadingOverlay(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize().background(MarsMidnight.copy(alpha = 0.70f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = MarsSurfaceRaised, shape = RoundedCornerShape(18.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), color = MarsRed, strokeWidth = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Loading your lineup…", color = MarsWhite)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MarsMuted)
    }
}

@Composable
fun SectionTitle(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MarsMuted)
        }
    }
}

fun progressPercent(positionMs: Long, durationMs: Long): Float =
    if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
