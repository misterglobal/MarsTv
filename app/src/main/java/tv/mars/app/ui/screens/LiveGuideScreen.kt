package tv.mars.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.Programme
import tv.mars.app.ui.components.EmptyState
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSuccess
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsWhite
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import tv.mars.app.core.PlayerRequest
import tv.mars.app.ui.LocalGuidePreviewBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun LiveGuideScreen(
    accountId: String,
    categoriesSource: () -> Flow<List<Category>>,
    channelsSource: (categoryKey: String?, blockedCategoryKeys: Set<String>) -> Flow<PagingData<Channel>>,
    programmesSource: (channelEpgId: String, windowStart: Long, windowEnd: Long) -> Flow<List<Programme>>,
    blockedCategoryKeys: Set<String>,
    favouriteKeys: Set<String>,
    fullEpgEnabled: Boolean,
    profileHasPin: Boolean,
    isCategoryLocked: (String) -> Boolean,
    pinMatches: (String) -> Boolean,
    onUnlockCategory: (String) -> Unit,
    onPlayChannel: (Channel) -> Unit,
    onPlayProgramme: (Channel, Programme) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onOpenSettings: () -> Unit,
    isTelevision: Boolean = false,
    previewRequest: PlayerRequest? = null,
    playerFullscreen: Boolean = false,
    onExpandPreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var selectedCategory by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    var categoriesExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingUnlock by remember { mutableStateOf<Category?>(null) }
    var guideOffsetMs by rememberSaveable(accountId) { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var highlighted by remember(accountId) { mutableStateOf<Pair<Channel, Programme>?>(null) }
    var details by remember(accountId) { mutableStateOf<Pair<Channel, Programme>?>(null) }
    var lastFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val previewFocus = remember { FocusRequester() }
    val categoryButtonFocus = remember { FocusRequester() }
    val selectedCategoryFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    val categoryListState = rememberLazyListState()
    val navigationScope = rememberCoroutineScope()
    var focusNewCategory by remember { mutableStateOf(false) }
    fun closeCategories() {
        categoriesExpanded = false
        navigationScope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { (lastFocus ?: categoryButtonFocus).requestFocus() }
                .onFailure { categoryButtonFocus.requestFocus() }
        }
    }
    fun selectCategory(key: String?) {
        if (selectedCategory == key) closeCategories()
        else {
            selectedCategory = key
            categoriesExpanded = false
            lastFocus = null
            focusNewCategory = true
        }
    }
    val categories by remember(accountId) { categoriesSource() }.collectAsStateWithLifecycle(emptyList())
    val channels = remember(accountId, selectedCategory, blockedCategoryKeys) {
        channelsSource(selectedCategory, blockedCategoryKeys)
    }.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val onPreviewBounds = LocalGuidePreviewBounds.current
    val halfHour = 30 * 60_000L
    val windowStart = now / halfHour * halfHour + guideOffsetMs
    val windowEnd = windowStart + 2 * 3_600_000L
    val formatter = remember { DateTimeFormatter.ofPattern("EEE h:mm a").withZone(ZoneId.systemDefault()) }
    fun moveWindow(direction: Int) {
        if (fullEpgEnabled) guideOffsetMs = (guideOffsetMs + direction * 2 * 3_600_000L)
            .coerceIn(-7 * 24 * 3_600_000L, 14 * 24 * 3_600_000L)
    }
    BackHandler(enabled = categoriesExpanded && !playerFullscreen && pendingUnlock == null) { closeCategories() }
    LaunchedEffect(categoriesExpanded) {
        if (categoriesExpanded) {
            val index = categories.indexOfFirst { it.key == selectedCategory } + 1
            categoryListState.scrollToItem(index.coerceAtLeast(0))
            androidx.compose.runtime.withFrameNanos { }
            runCatching { selectedCategoryFocus.requestFocus() }
        }
    }
    LaunchedEffect(focusNewCategory, channels.loadState.refresh, channels.itemCount) {
        if (focusNewCategory && channels.loadState.refresh !is LoadState.Loading) {
            listState.scrollToItem(0)
            androidx.compose.runtime.withFrameNanos { }
            val target = if (channels.itemCount > 0) firstChannelFocus else categoryButtonFocus
            runCatching { target.requestFocus() }.onSuccess { focusNewCategory = false }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(fullEpgEnabled) { if (!fullEpgEnabled) guideOffsetMs = 0 }
    LaunchedEffect(playerFullscreen) {
        if (!playerFullscreen) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { (lastFocus ?: previewFocus).requestFocus() }.onFailure { runCatching { previewFocus.requestFocus() } }
        }
    }
    LaunchedEffect(categories, selectedCategory) {
        if (selectedCategory != null && categories.none { it.key == selectedCategory }) selectedCategory = null
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusSurface(onClick = { if (categoriesExpanded) closeCategories() else categoriesExpanded = true },
                modifier = Modifier.width(180.dp).focusRequester(categoryButtonFocus), selected = categoriesExpanded) {
                Text(categories.firstOrNull { it.key == selectedCategory }?.name ?: "All channels",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = MarsWhite, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            Text(formatter.format(Instant.ofEpochMilli(windowStart)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MarsWhite)
            if (fullEpgEnabled) AssistChip(onClick = { moveWindow(-1) }, label = { Text("Earlier") })
            AssistChip(onClick = { guideOffsetMs = 0 }, label = { Text("Now") })
            if (fullEpgEnabled) AssistChip(onClick = { moveWindow(1) }, label = { Text("Later") })
        }
        if (previewRequest != null) {
            Row(Modifier.fillMaxWidth().height(96.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusSurface(onClick = onExpandPreview, modifier = Modifier.width(160.dp).fillMaxHeight().focusRequester(previewFocus)) {
                    // Leave the focus border visible around the root-owned video surface.
                    Box(Modifier.fillMaxSize().padding(3.dp)) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) })
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Playing: ${previewRequest.title}", maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MarsSuccess)
                    Text(highlighted?.second?.title ?: previewRequest.title, maxLines = 1, fontWeight = FontWeight.Bold, color = MarsWhite)
                    Text(highlighted?.second?.description.orEmpty().ifBlank { "Select the preview or playing channel for fullscreen" }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MarsMuted)
                }
                TextButton(onClick = onStopPreview) { Text("Stop") }
            }
        }
        Row(Modifier.weight(1f)) {
            if (categoriesExpanded) {
                LazyColumn(state = categoryListState, modifier = Modifier.width(180.dp).fillMaxHeight().padding(end = 8.dp)
                    .onPreviewKeyEvent {
                        if (it.key == Key.DirectionRight) {
                            if (it.type == KeyEventType.KeyDown) closeCategories()
                            true
                        } else false
                    }) {
                    item { CategoryChip("All channels", selectedCategory == null, false,
                        if (selectedCategory == null) Modifier.focusRequester(selectedCategoryFocus) else Modifier) { selectCategory(null) } }
                    items(categories, key = { it.key }) { category ->
                        CategoryChip(category.name, selectedCategory == category.key, isCategoryLocked(category.key),
                            if (selectedCategory == category.key) Modifier.focusRequester(selectedCategoryFocus) else Modifier) {
                            if (isCategoryLocked(category.key)) pendingUnlock = category
                            else selectCategory(category.key)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().height(24.dp)) {
                    Text(if (isTelevision) "Left: Categories" else "Channel", Modifier.width(if (isTelevision) 158.dp else 130.dp), color = MarsMuted, style = MaterialTheme.typography.labelSmall)
                    repeat(4) { index ->
                        Text(DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(windowStart + index * halfHour)),
                            Modifier.weight(1f), color = MarsMuted, style = MaterialTheme.typography.labelSmall)
                    }
                }
                when {
                    channels.loadState.refresh is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    channels.loadState.refresh is LoadState.Error -> Column {
                        Text("Could not load live channels")
                        TextButton(onClick = { channels.retry() }) { Text("Retry") }
                    }
                    channels.itemCount == 0 -> EmptyState("No live channels", "Try another category or refresh this account.")
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val rowHeight = if (isTelevision) ((maxHeight - 14.dp) / 8).coerceIn(36.dp, 54.dp) else 64.dp
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(count = channels.itemCount, key = { channels.peek(it)?.key ?: "placeholder-$it" }) { index ->
                                channels[index]?.let { channel ->
                                    val programmes by remember(accountId, channel.epgId, windowStart, fullEpgEnabled) {
                                        programmesSource(channel.epgId, windowStart, windowEnd)
                                    }.collectAsStateWithLifecycle(emptyList())
                                    ChannelTimelineRow(
                                        channel, programmes, windowStart, windowEnd, now,
                                        channel.key in favouriteKeys, previewRequest?.contentKey == channel.key,
                                        Modifier.height(rowHeight), if (isTelevision) 158.dp else 130.dp,
                                        onPlay = { onPlayChannel(channel) },
                                        onFavourite = { onToggleFavourite(channel.key) },
                                        onSelect = { programme ->
                                            if (programme.isLive) onPlayProgramme(channel, programme)
                                            else details = channel to programme
                                        },
                                        onFocus = { programme, requester -> highlighted = channel to programme; lastFocus = requester },
                                        onChannelFocus = { lastFocus = it },
                                        onMoveWindow = ::moveWindow,
                                        channelModifier = if (index == 0) Modifier.focusRequester(firstChannelFocus) else Modifier,
                                        onOpenCategories = { categoriesExpanded = true },
                                    )
                                }
                            }
                            if (channels.loadState.append is LoadState.Loading) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                            if (channels.loadState.append is LoadState.Error) item { TextButton(onClick = { channels.retry() }) { Text("Retry loading channels") } }
                        }
                    }
                }
            }
        }
    }
    details?.let { (channel, programme) ->
        AlertDialog(onDismissRequest = { details = null }, title = { Text(programme.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(channel.name)
                Text("${formatter.format(Instant.ofEpochMilli(programme.startMs))} ? ${formatter.format(Instant.ofEpochMilli(programme.endMs))}")
                Text(programme.description.ifBlank { "No description available" })
                TextButton(onClick = { onToggleFavourite(channel.key) }) { Text(if (channel.key in favouriteKeys) "Remove favourite" else "Add favourite") }
            } },
            confirmButton = {
                if (programme.isLive || (programme.isPast && channel.supportsCatchUp)) {
                    TextButton(onClick = { details = null; onPlayProgramme(channel, programme) }) { Text(if (programme.isLive) "Watch live" else "Watch catch-up") }
                } else TextButton(onClick = { details = null }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { details = null; onPlayChannel(channel) }) { Text("Watch channel") } })
    }
    pendingUnlock?.let { category ->
        UnlockCategoryDialog(category.name, profileHasPin, pinMatches, { pendingUnlock = null }, {
            onUnlockCategory(category.key); pendingUnlock = null; selectCategory(category.key)
        }, { pendingUnlock = null; onOpenSettings() })
    }
}

@Composable
private fun CategoryChip(title: String, selected: Boolean, locked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick, modifier.fillMaxWidth().padding(bottom = 3.dp), selected) {
        Text((if (locked) "Locked ? " else "") + title, Modifier.padding(8.dp), maxLines = 2,
            style = MaterialTheme.typography.labelMedium, color = if (selected) MarsWhite else MarsMuted)
    }
}

@Composable
private fun ChannelTimelineRow(
    channel: Channel, programmes: List<Programme>, windowStart: Long, windowEnd: Long, now: Long,
    favourite: Boolean, playing: Boolean, modifier: Modifier, channelWidth: androidx.compose.ui.unit.Dp,
    onPlay: () -> Unit, onFavourite: () -> Unit, onSelect: (Programme) -> Unit,
    onFocus: (Programme, FocusRequester) -> Unit, onChannelFocus: (FocusRequester) -> Unit,
    onMoveWindow: (Int) -> Unit,
    channelModifier: Modifier = Modifier,
    onOpenCategories: () -> Unit,
) {
    val channelFocus = remember(channel.key) { FocusRequester() }
    val scope = rememberCoroutineScope()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FocusSurface(onPlay, channelModifier.width(channelWidth).fillMaxHeight().focusRequester(channelFocus)
            .onPreviewKeyEvent {
                if (it.key == Key.DirectionLeft) {
                    if (it.type == KeyEventType.KeyDown) onOpenCategories()
                    true
                } else false
            }
            .onFocusChanged { if (it.hasFocus) onChannelFocus(channelFocus) }, selected = playing) {
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(26.dp))
                Text(channel.name, Modifier.weight(1f).padding(start = 6.dp), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MarsWhite)
                IconButton(onClick = onFavourite, modifier = Modifier.size(30.dp)) {
                    Icon(if (favourite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (favourite) "Remove favourite" else "Add favourite",
                        tint = if (favourite) MarsRed else MarsMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MarsSurface)) {
            val timelineWidth = maxWidth
            val slots = remember(programmes, windowStart, windowEnd) { guideSlots(programmes, windowStart, windowEnd) }
            slots.forEachIndexed { index, slot ->
                val programme = slot.programme
                val requester = remember(channel.key, slot.startMs) { FocusRequester() }
                val startFraction = (slot.startMs - windowStart).toFloat() / (windowEnd - windowStart)
                val widthFraction = (slot.endMs - slot.startMs).toFloat() / (windowEnd - windowStart)
                FocusSurface(
                    onClick = { if (programme == null) onPlay() else onSelect(programme) },
                    modifier = Modifier.offset(x = timelineWidth * startFraction).width(timelineWidth * widthFraction).fillMaxHeight()
                        .padding(end = 2.dp).focusRequester(requester)
                        .onFocusChanged { if (it.isFocused) { if (programme != null) onFocus(programme, requester) else onChannelFocus(requester) } }
                        .onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && ((it.key == Key.DirectionRight && index == slots.lastIndex) || (it.key == Key.DirectionLeft && index == 0))) {
                                if (it.key == Key.DirectionRight) {
                                    onMoveWindow(1)
                                    scope.launch { androidx.compose.runtime.withFrameNanos { }; channelFocus.requestFocus() }
                                    true
                                } else false
                            } else false
                        },
                    selected = programme != null && now in programme.startMs until programme.endMs,
                ) {
                    Text(programme?.title ?: "No guide data ? Watch live", Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                        color = if (programme == null) MarsMuted else MarsWhite)
                }
            }
            if (now in windowStart until windowEnd) {
                Box(Modifier.offset(x = timelineWidth * ((now - windowStart).toFloat() / (windowEnd - windowStart)))
                    .width(1.dp).fillMaxHeight().background(MarsSuccess))
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
