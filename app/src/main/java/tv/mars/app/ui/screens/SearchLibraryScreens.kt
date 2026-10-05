package tv.mars.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.mars.app.core.CatalogLookup
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.MediaContent
import tv.mars.app.core.WatchRecord
import tv.mars.app.ui.components.EmptyState
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.components.PosterCard
import tv.mars.app.ui.components.progressPercent
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurfaceRaised
import tv.mars.app.ui.theme.MarsViolet
import tv.mars.app.ui.theme.MarsWhite
import java.text.DateFormat
import java.util.Date

@Composable
fun SearchScreen(
    accountId: String,
    catalogRevision: Long,
    query: String,
    onQueryChange: (String) -> Unit,
    blockedCategoryKeys: Set<String>,
    searchSource: suspend (query: String, blockedCategoryKeys: Set<String>) -> CatalogLookup,
    favouriteKeys: Set<String>,
    globalSearchEnabled: Boolean,
    isTelevision: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onToggleFavourite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalized = query.trim()
    val lookup by produceState<CatalogLookup?>(null, accountId, catalogRevision, normalized, blockedCategoryKeys) {
        value = null
        if (accountId.isNotBlank() && normalized.length >= 2) {
            delay(250)
            value = searchSource(normalized, blockedCategoryKeys)
        }
    }
    val channels = lookup?.channels.orEmpty()
    val media = lookup?.media.orEmpty()

    Column(modifier = modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (globalSearchEnabled) "Search all TV sources" else "Search this TV source") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        Spacer(Modifier.height(18.dp))

        when {
            normalized.length < 2 -> EmptyState(
                "Start typing",
                if (globalSearchEnabled) "Enter at least two characters to search all TV sources."
                else "Enter at least two characters to search this TV source.",
            )
            lookup == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            channels.isEmpty() && media.isEmpty() -> EmptyState("No matches", "Try a title, channel, category, or year.")
            else -> LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (channels.isNotEmpty()) {
                    item { Text("Live channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(channels, key = Channel::key) { channel ->
                                SearchChannelCard(channel, channel.key in favouriteKeys) {
                                    onPlayChannel(channel)
                                }
                            }
                        }
                    }
                }
                if (media.isNotEmpty()) {
                    item { Text("Movies and series", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(media, key = MediaContent::key) { item ->
                                PosterCard(
                                    item = item,
                                    favourite = item.key in favouriteKeys,
                                    isTelevision = isTelevision,
                                    onClick = { onOpenMedia(item) },
                                    onFavourite = { onToggleFavourite(item.key) },
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun SearchChannelCard(channel: Channel, favourite: Boolean, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.width(220.dp).height(92.dp)) {
        Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)).background(MarsSurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl.isNotBlank()) {
                    AsyncImage(model = channel.logoUrl, contentDescription = channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp))
                } else {
                    Icon(Icons.Default.LiveTv, null, tint = MarsRed)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(channel.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(channel.categoryName, color = MarsMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                if (favourite) Text("★ Favourite", color = MarsRed, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun LibraryScreen(
    accountId: String,
    catalogRevision: Long,
    favouriteKeys: Set<String>,
    lockedFavouriteKeys: Set<String>,
    blockedCategoryKeys: Set<String>,
    favouritesSource: suspend (favouriteKeys: Set<String>, blockedCategoryKeys: Set<String>) -> CatalogLookup,
    continueWatching: List<WatchRecord>,
    history: List<WatchRecord>,
    isTelevision: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onPlayHistory: (WatchRecord) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(accountId) { mutableIntStateOf(0) }
    var contentFilter by rememberSaveable(accountId) { mutableIntStateOf(0) }
    val favourites by produceState<CatalogLookup?>(null, accountId, catalogRevision, favouriteKeys, blockedCategoryKeys) {
        value = if (accountId.isBlank()) CatalogLookup() else {
            favouritesSource(favouriteKeys, blockedCategoryKeys)
        }
    }
    val lockedFavourites by produceState<CatalogLookup?>(null, accountId, catalogRevision, lockedFavouriteKeys, blockedCategoryKeys) {
        value = if (accountId.isBlank() || lockedFavouriteKeys.isEmpty()) CatalogLookup() else {
            favouritesSource(lockedFavouriteKeys, blockedCategoryKeys)
        }
    }
    val tabs = listOf("Favourites", "Continue watching", "History")

    Surface(modifier = modifier.fillMaxSize(), color = Color.Transparent, contentColor = MarsWhite) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("My TV", color = MarsWhite, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Favourites and viewing activity stay on this device", color = MarsMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (tab == 2 && history.isNotEmpty()) {
                    FocusSurface(onClick = onClearHistory) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeleteSweep, null, tint = MarsMuted)
                            Spacer(Modifier.width(6.dp))
                            Text("Clear history", color = MarsMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { index, label ->
                    FocusSurface(onClick = { tab = index }, selected = tab == index) {
                        Text(label, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Channels", "Movies", "Series").forEachIndexed { index, label ->
                    FocusSurface(onClick = { contentFilter = index }, selected = contentFilter == index) {
                        Text(label, Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                key(tab, contentFilter) {
                    when (tab) {
                        0 -> if (favourites == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            FavouriteLibrary(
                                channels = favourites.orEmpty().channels.filter { matchesLibraryFilter(ContentKind.LIVE, contentFilter) },
                                media = favourites.orEmpty().media.filter { matchesLibraryFilter(it.kind, contentFilter) },
                                onPlayChannel = onPlayChannel,
                                onOpenMedia = onOpenMedia,
                                onToggleFavourite = onToggleFavourite,
                                lockedFavourites = CatalogLookup(
                                    channels = lockedFavourites.orEmpty().channels.filter { matchesLibraryFilter(ContentKind.LIVE, contentFilter) },
                                    media = lockedFavourites.orEmpty().media.filter { matchesLibraryFilter(it.kind, contentFilter) },
                                ),
                            )
                        }
                        1 -> HistoryList(
                            records = continueWatching.filter { matchesLibraryFilter(it.kind, contentFilter) },
                            emptyTitle = "Nothing to continue in this view",
                            emptyDetail = "Partially watched movies and episodes will appear here. Try All to see other types.",
                            onPlay = onPlayHistory,
                        )
                        else -> HistoryList(
                            records = history.filter { matchesLibraryFilter(it.kind, contentFilter) },
                            emptyTitle = "No viewing history in this view",
                            emptyDetail = "Recently played content will appear here. Try All to see other types.",
                            onPlay = onPlayHistory,
                        )
                    }
                }
            }
        }
    }
}

internal fun matchesLibraryFilter(kind: ContentKind, filter: Int): Boolean = when (filter) {
    1 -> kind == ContentKind.LIVE
    2 -> kind == ContentKind.MOVIE
    3 -> kind == ContentKind.SERIES || kind == ContentKind.EPISODE
    else -> true
}

private fun CatalogLookup?.orEmpty(): CatalogLookup = this ?: CatalogLookup()

@Composable
private fun FavouriteLibrary(
    channels: List<Channel>,
    media: List<MediaContent>,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onToggleFavourite: (String) -> Unit,
    lockedFavourites: CatalogLookup,
) {
    if (channels.isEmpty() && media.isEmpty() && lockedFavourites.channels.isEmpty() && lockedFavourites.media.isEmpty()) {
        EmptyState("No favourites in this view", "Try All, or use the heart button on a channel, movie, or series.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (channels.isNotEmpty()) {
            item { Text("Channels", color = MarsWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(channels, key = Channel::key) { channel ->
                LibraryFavouriteRow(channel.name, channel.categoryName, channel.logoUrl,
                    onOpen = { onPlayChannel(channel) }, onRemove = { onToggleFavourite(channel.key) })
            }
        }
        listOf(ContentKind.MOVIE to "Movies", ContentKind.SERIES to "Series").forEach { (kind, label) ->
            val entries = media.filter { it.kind == kind || (kind == ContentKind.SERIES && it.kind == ContentKind.EPISODE) }
            if (entries.isNotEmpty()) {
                item { Text(label, color = MarsWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(entries, key = MediaContent::key) { entry ->
                    LibraryFavouriteRow(entry.title, entry.categoryName, entry.artworkUrl,
                        onOpen = { onOpenMedia(entry) }, onRemove = { onToggleFavourite(entry.key) })
                }
            }
        }
        if (lockedFavourites.channels.isNotEmpty() || lockedFavourites.media.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = MarsViolet)
                    Spacer(Modifier.width(8.dp))
                    Text("Additional favourites — MarsTV Pro", color = MarsWhite, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            items(lockedFavourites.channels, key = { "locked:${it.key}" }) { channel ->
                LockedFavouriteRow(channel.name)
            }
            items(lockedFavourites.media, key = { "locked:${it.key}" }) { item ->
                LockedFavouriteRow(item.title)
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun LibraryFavouriteRow(title: String, category: String, artwork: String, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusSurface(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MarsSurfaceRaised), contentAlignment = Alignment.Center) {
                    if (artwork.isNotBlank()) {
                        AsyncImage(model = artwork, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.PlayArrow, null, tint = MarsRed)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(category, color = MarsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        FocusSurface(onClick = onRemove) {
            Icon(Icons.Default.Favorite, contentDescription = "Remove favourite: $title", tint = MarsRed, modifier = Modifier.padding(14.dp))
        }
    }
}

@Composable
private fun LockedFavouriteRow(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MarsSurfaceRaised, RoundedCornerShape(8.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Lock, null, tint = MarsMuted)
        Spacer(Modifier.width(10.dp))
        Text(title, color = MarsMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryList(
    records: List<WatchRecord>,
    emptyTitle: String,
    emptyDetail: String,
    onPlay: (WatchRecord) -> Unit,
) {
    if (records.isEmpty()) {
        EmptyState(emptyTitle, emptyDetail)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(records, key = { "${it.contentKey}:${it.watchedAt}" }) { record ->
            FocusSurface(onClick = { onPlay(record) }, modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.width(112.dp).height(66.dp).clip(RoundedCornerShape(8.dp)).background(MarsSurfaceRaised),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (record.artworkUrl.isNotBlank()) {
                            AsyncImage(model = record.artworkUrl, contentDescription = record.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.PlayArrow, null, tint = MarsRed)
                        }
                        if (record.canContinue) {
                            Box(
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(progressPercent(record.positionMs, record.durationMs)).height(4.dp).background(MarsRed),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(record.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.watchedAt)),
                            color = MarsMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Icon(Icons.Default.History, null, tint = MarsViolet)
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
