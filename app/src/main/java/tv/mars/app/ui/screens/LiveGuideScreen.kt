package tv.mars.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.Programme
import tv.mars.app.ui.components.EmptyState
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSuccess
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsSurfaceRaised
import tv.mars.app.ui.theme.MarsViolet
import tv.mars.app.ui.theme.MarsWhite
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LiveGuideScreen(
    catalog: CatalogBundle,
    favouriteKeys: Set<String>,
    profileHasPin: Boolean,
    isCategoryLocked: (String) -> Boolean,
    pinMatches: (String) -> Boolean,
    onUnlockCategory: (String) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    onPlayProgramme: (Channel, Programme) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedCategory by remember(catalog.accountId) { mutableStateOf<String?>(null) }
    var pendingUnlock by remember { mutableStateOf<Category?>(null) }
    var guideOffsetMs by remember { mutableLongStateOf(0L) }

    val channels = catalog.channels.filter {
        !isCategoryLocked(it.categoryKey) && (selectedCategory == null || it.categoryKey == selectedCategory)
    }

    Column(modifier = modifier.fillMaxSize()) {
        GuideHeader(
            categories = catalog.liveCategories,
            selectedCategory = selectedCategory,
            isLocked = isCategoryLocked,
            onSelect = { category ->
                if (category != null && isCategoryLocked(category.key)) pendingUnlock = category
                else selectedCategory = category?.key
            },
            onEarlier = { guideOffsetMs -= 2 * 60 * 60 * 1000L },
            onNow = { guideOffsetMs = 0L },
            onLater = { guideOffsetMs += 2 * 60 * 60 * 1000L },
        )

        if (channels.isEmpty()) {
            EmptyState("No live channels", "Try another category or refresh this account.")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(channels, key = Channel::key) { channel ->
                    ChannelGuideRow(
                        channel = channel,
                        programmes = catalog.programmesByEpgId[channel.epgId].orEmpty(),
                        guideOffsetMs = guideOffsetMs,
                        favourite = channel.key in favouriteKeys,
                        onPlayChannel = { onPlayChannel(channel) },
                        onPlayProgramme = { onPlayProgramme(channel, it) },
                        onFavourite = { onToggleFavourite(channel.key) },
                    )
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }

    pendingUnlock?.let { category ->
        UnlockCategoryDialog(
            categoryName = category.name,
            hasPin = profileHasPin,
            pinMatches = pinMatches,
            onDismiss = { pendingUnlock = null },
            onUnlocked = {
                onUnlockCategory(category.key)
                selectedCategory = category.key
                pendingUnlock = null
            },
            onOpenSettings = {
                pendingUnlock = null
                onOpenSettings()
            },
        )
    }
}

@Composable
private fun GuideHeader(
    categories: List<Category>,
    selectedCategory: String?,
    isLocked: (String) -> Boolean,
    onSelect: (Category?) -> Unit,
    onEarlier: () -> Unit,
    onNow: () -> Unit,
    onLater: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(MarsMidnight).padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Live guide", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text("Select a programme to watch live or replay catch-up", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onEarlier, label = { Text("−2h") })
                AssistChip(onClick = onNow, label = { Text("Now") })
                AssistChip(onClick = onLater, label = { Text("+2h") })
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip("All channels", selectedCategory == null, false) { onSelect(null) }
            categories.forEach { category ->
                CategoryChip(
                    title = category.name,
                    selected = selectedCategory == category.key,
                    locked = isLocked(category.key),
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(title: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, selected = selected) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (locked) {
                Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.width(16.dp), tint = MarsMuted)
                Spacer(Modifier.width(6.dp))
            }
            Text(title, maxLines = 1, color = if (selected) MarsWhite else MarsMuted, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChannelGuideRow(
    channel: Channel,
    programmes: List<Programme>,
    guideOffsetMs: Long,
    favourite: Boolean,
    onPlayChannel: () -> Unit,
    onPlayProgramme: (Programme) -> Unit,
    onFavourite: () -> Unit,
) {
    val windowStart = System.currentTimeMillis() + guideOffsetMs - 15 * 60 * 1000L
    val windowEnd = windowStart + 4 * 60 * 60 * 1000L
    val visibleProgrammes = programmes.filter { it.endMs > windowStart && it.startMs < windowEnd }.take(10)

    Row(modifier = Modifier.fillMaxWidth().height(104.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(onClick = onPlayChannel, modifier = Modifier.width(218.dp).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(MarsSurfaceRaised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (channel.logoUrl.isNotBlank()) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(5.dp),
                        )
                    } else {
                        Text(channel.name.take(2).uppercase(), color = MarsMuted, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(channel.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(channel.categoryName, color = MarsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                IconButton(onClick = onFavourite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favourite",
                        tint = if (favourite) MarsRed else MarsMuted,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxHeight().weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visibleProgrammes.isEmpty()) {
                FocusSurface(onClick = onPlayChannel, modifier = Modifier.width(280.dp).fillMaxHeight()) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = MarsRed)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Watch live", fontWeight = FontWeight.Bold)
                            Text("Guide data unavailable", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                visibleProgrammes.forEach { programme ->
                    ProgrammeCard(
                        programme = programme,
                        catchUpAvailable = channel.supportsCatchUp && programme.isPast,
                        onClick = { onPlayProgramme(programme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCard(programme: Programme, catchUpAvailable: Boolean, onClick: () -> Unit) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("h:mm a") }
    val startText = remember(programme.startMs) {
        Instant.ofEpochMilli(programme.startMs).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }
    val durationMinutes = ((programme.endMs - programme.startMs) / 60_000L).coerceIn(20, 180)
    val width = (durationMinutes * 3.4).dp.coerceIn(160.dp, 420.dp)
    val activeColor = if (programme.isLive) MarsSuccess else if (catchUpAvailable) MarsViolet else MarsMuted

    FocusSurface(onClick = onClick, modifier = Modifier.width(width).fillMaxHeight(), selected = programme.isLive) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(activeColor))
                Spacer(Modifier.width(6.dp))
                Text(startText, color = MarsMuted, style = MaterialTheme.typography.labelMedium)
                if (catchUpAvailable) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.Replay, contentDescription = "Catch-up", tint = MarsViolet, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
fun UnlockCategoryDialog(
    categoryName: String,
    hasPin: Boolean,
    pinMatches: (String) -> Boolean,
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (hasPin) "Unlock $categoryName" else "Parental PIN required") },
        text = {
            Column {
                if (hasPin) {
                    Text("Enter the profile PIN to view this category.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.take(8); invalid = false },
                        label = { Text("PIN") },
                        isError = invalid,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (invalid) Text("Incorrect PIN", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Set a PIN in Settings before locking categories.")
                }
            }
        },
        confirmButton = {
            if (hasPin) {
                Button(onClick = {
                    if (pinMatches(pin)) onUnlocked() else invalid = true
                }) { Text("Unlock") }
            } else {
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
