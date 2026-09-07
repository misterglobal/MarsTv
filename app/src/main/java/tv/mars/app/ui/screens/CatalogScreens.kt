package tv.mars.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.Flow
import tv.mars.app.core.Category
import tv.mars.app.core.Episode
import tv.mars.app.core.MediaContent
import tv.mars.app.core.SeriesDetails
import tv.mars.app.ui.components.EmptyState
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.components.PosterCard
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurfaceRaised
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun MediaCatalogScreen(
    accountId: String,
    title: String,
    subtitle: String,
    categoriesSource: () -> Flow<List<Category>>,
    contentSource: (categoryKey: String?, blockedCategoryKeys: Set<String>) -> Flow<PagingData<MediaContent>>,
    blockedCategoryKeys: Set<String>,
    favouriteKeys: Set<String>,
    isTelevision: Boolean,
    profileHasPin: Boolean,
    isCategoryLocked: (String) -> Boolean,
    pinMatches: (String) -> Boolean,
    onUnlockCategory: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpen: (MediaContent) -> Unit,
    onToggleFavourite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val categories by remember(accountId) { categoriesSource() }.collectAsStateWithLifecycle(emptyList())
    var selectedCategory by remember(accountId) { mutableStateOf<String?>(null) }
    var pendingUnlock by remember { mutableStateOf<Category?>(null) }
    val content = remember(accountId, selectedCategory, blockedCategoryKeys) {
        contentSource(selectedCategory, blockedCategoryKeys)
    }.collectAsLazyPagingItems()

    LaunchedEffect(categories, selectedCategory) {
        if (selectedCategory != null && categories.none { it.key == selectedCategory }) selectedCategory = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(subtitle, color = MarsMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CatalogChip("All", selectedCategory == null, false) { selectedCategory = null }
            categories.forEach { category ->
                CatalogChip(
                    title = category.name,
                    selected = selectedCategory == category.key,
                    locked = isCategoryLocked(category.key),
                    onClick = {
                        if (isCategoryLocked(category.key)) pendingUnlock = category
                        else selectedCategory = category.key
                    },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            content.loadState.refresh is LoadState.Loading && content.itemCount == 0 -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MarsRed)
                }
            }
            content.loadState.refresh is LoadState.Error && content.itemCount == 0 -> {
                EmptyState("Could not load catalog", "Refresh this account or try again.")
            }
            content.itemCount == 0 -> {
                EmptyState("Nothing here yet", "This source did not return items for the selected category.")
            }
            else -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTelevision) 174.dp else 142.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(
                    count = content.itemCount,
                    key = { index -> content.peek(index)?.key ?: "catalog-placeholder-$index" },
                ) { index ->
                    content[index]?.let { item ->
                        PosterCard(
                            item = item,
                            favourite = item.key in favouriteKeys,
                            isTelevision = isTelevision,
                            onClick = { onOpen(item) },
                            onFavourite = { onToggleFavourite(item.key) },
                        )
                    }
                }
                if (content.loadState.append is LoadState.Loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MarsRed)
                        }
                    }
                }
                item { Spacer(Modifier.height(28.dp)) }
            }
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
private fun CatalogChip(title: String, selected: Boolean, locked: Boolean, onClick: () -> Unit) {
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
fun SeriesDetailsScreen(
    details: SeriesDetails,
    favourite: Boolean,
    onBack: () -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onToggleFavourite: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val seasons = details.episodesBySeason.keys.sorted()
    var selectedSeason by remember(details.series.key) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val episodes = details.episodesBySeason[selectedSeason].orEmpty()

    Column(modifier = Modifier.fillMaxSize().background(MarsMidnight)) {
        Box(modifier = Modifier.fillMaxWidth().height(270.dp)) {
            val backdrop = details.series.backdropUrl.ifBlank { details.series.artworkUrl }
            if (backdrop.isNotBlank()) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = details.series.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, MarsMidnight)),
                ),
            )
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)) {
                Text(details.series.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                if (details.series.description.isNotBlank()) {
                    Text(
                        details.series.description,
                        color = MarsMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.75f),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEach { season ->
                    FocusSurface(onClick = { selectedSeason = season }, selected = selectedSeason == season) {
                        Text("Season $season", modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
            FocusSurface(onClick = onToggleFavourite, selected = favourite) {
                Text(
                    if (favourite) "★ In My TV" else "☆ Add to My TV",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = if (favourite) MarsRed else MarsWhite,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        if (episodes.isEmpty()) {
            EmptyState("No episodes found", "The source returned this series without playable episode data.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(280.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(episodes, key = Episode::key) { episode ->
                    EpisodeCard(episode = episode, onPlay = { onPlayEpisode(episode) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EpisodeCard(episode: Episode, onPlay: () -> Unit) {
    FocusSurface(onClick = onPlay, modifier = Modifier.fillMaxWidth().height(104.dp)) {
        Row(modifier = Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(112.dp).height(74.dp).clip(RoundedCornerShape(8.dp)).background(MarsSurfaceRaised),
                contentAlignment = Alignment.Center,
            ) {
                if (episode.artworkUrl.isNotBlank()) {
                    AsyncImage(
                        model = episode.artworkUrl,
                        contentDescription = episode.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MarsRed)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("E${episode.episodeNumber}  ${episode.title}", fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (episode.durationText.isNotBlank()) Text(episode.durationText, color = MarsMuted, style = MaterialTheme.typography.labelSmall)
                if (episode.description.isNotBlank()) {
                    Text(episode.description, color = MarsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
