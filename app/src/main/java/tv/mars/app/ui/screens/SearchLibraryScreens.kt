package tv.mars.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Channel
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
    query: String,
    onQueryChange: (String) -> Unit,
    catalog: CatalogBundle,
    favouriteKeys: Set<String>,
    isTelevision: Boolean,
    isCategoryLocked: (String) -> Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onToggleFavourite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalized = query.trim()
    val channels = if (normalized.length < 2) emptyList() else catalog.channels.filter {
        !isCategoryLocked(it.categoryKey) && (it.name.contains(normalized, true) || it.categoryName.contains(normalized, true))
    }.take(30)
    val media = if (normalized.length < 2) emptyList() else (catalog.movies + catalog.series).filter {
        !isCategoryLocked(it.categoryKey) &&
            (it.title.contains(normalized, true) || it.categoryName.contains(normalized, true) || it.year.contains(normalized, true))
    }.take(60)

    Column(modifier = modifier.fillMaxSize()) {
        Text("Search", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Channels, movies, or series") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true,
        )
        Spacer(Modifier.height(18.dp))

        when {
            normalized.length < 2 -> EmptyState("Start typing", "Enter at least two characters to search this account.")
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
    catalog: CatalogBundle,
    favouriteKeys: Set<String>,
    continueWatching: List<WatchRecord>,
    history: List<WatchRecord>,
    isTelevision: Boolean,
    isCategoryLocked: (String) -> Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onPlayHistory: (WatchRecord) -> Unit,
    onToggleFavourite: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }
    val favouriteChannels = catalog.channels.filter { it.key in favouriteKeys && !isCategoryLocked(it.categoryKey) }
    val favouriteMedia = (catalog.movies + catalog.series).filter { it.key in favouriteKeys && !isCategoryLocked(it.categoryKey) }
    val tabs = listOf("Favourites", "Continue", "History")

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("My TV", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { index, label ->
                FocusSurface(onClick = { tab = index }, selected = tab == index) {
                    Text(label, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        when (tab) {
            0 -> FavouriteLibrary(
                channels = favouriteChannels,
                media = favouriteMedia,
                favouriteKeys = favouriteKeys,
                isTelevision = isTelevision,
                onPlayChannel = onPlayChannel,
                onOpenMedia = onOpenMedia,
                onToggleFavourite = onToggleFavourite,
            )
            1 -> HistoryList(
                records = continueWatching,
                emptyTitle = "Nothing to continue",
                emptyDetail = "Partially watched movies and episodes will appear here.",
                onPlay = onPlayHistory,
            )
            else -> HistoryList(
                records = history,
                emptyTitle = "No viewing history",
                emptyDetail = "Recently played content will appear here.",
                onPlay = onPlayHistory,
            )
        }
    }
}

@Composable
private fun FavouriteLibrary(
    channels: List<Channel>,
    media: List<MediaContent>,
    favouriteKeys: Set<String>,
    isTelevision: Boolean,
    onPlayChannel: (Channel) -> Unit,
    onOpenMedia: (MediaContent) -> Unit,
    onToggleFavourite: (String) -> Unit,
) {
    if (channels.isEmpty() && media.isEmpty()) {
        EmptyState("No favourites yet", "Use the heart button on a channel, movie, or series.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (channels.isNotEmpty()) {
            item { Text("Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(channels, key = Channel::key) { channel ->
                        SearchChannelCard(channel, true) { onPlayChannel(channel) }
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
            FocusSurface(onClick = { onPlay(record) }, modifier = Modifier.fillMaxWidth().height(86.dp)) {
                Row(modifier = Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        Text(record.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
