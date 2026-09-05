package tv.mars.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.mars.app.core.MainDestination
import tv.mars.app.core.ContentKind
import tv.mars.app.core.OverlayScreen
import tv.mars.app.ui.components.ErrorBanner
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.components.LoadingOverlay
import tv.mars.app.ui.components.MarsLogo
import tv.mars.app.ui.screens.AccountSetupScreen
import tv.mars.app.ui.screens.LibraryScreen
import tv.mars.app.ui.screens.LiveGuideScreen
import tv.mars.app.ui.screens.MediaCatalogScreen
import tv.mars.app.ui.screens.PlayerScreen
import tv.mars.app.ui.screens.ProfilesScreen
import tv.mars.app.ui.screens.SearchScreen
import tv.mars.app.ui.screens.SeriesDetailsScreen
import tv.mars.app.ui.screens.SettingsScreen
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsSurface
import tv.mars.app.ui.theme.MarsViolet
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun MarsTvRoot(viewModel: MarsTvViewModel, isTelevision: Boolean) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerRequest = state.playerRequest
    val selectedSeries = state.selectedSeries

    Box(modifier = Modifier.fillMaxSize().background(MarsMidnight)) {
        when {
            !state.hasLoadedState -> SplashScreen()
            state.overlay == OverlayScreen.PLAYER && playerRequest != null -> {
                PlayerScreen(request = playerRequest, onClose = viewModel::closePlayer)
            }
            state.overlay == OverlayScreen.SERIES_DETAILS && selectedSeries != null -> {
                SeriesDetailsScreen(
                    details = selectedSeries,
                    favourite = selectedSeries.series.key in state.favouriteKeys,
                    onBack = viewModel::dismissOverlay,
                    onPlayEpisode = viewModel::playEpisode,
                    onToggleFavourite = { viewModel.toggleFavourite(selectedSeries.series.key) },
                )
            }
            state.overlay == OverlayScreen.PROFILES -> {
                ProfilesScreen(
                    profiles = state.local.profiles,
                    activeProfileId = state.local.activeProfileId,
                    onSelect = viewModel::selectProfile,
                    onAdd = viewModel::addProfile,
                    onRemove = viewModel::removeProfile,
                    onBack = viewModel::dismissOverlay,
                )
            }
            state.local.accounts.isEmpty() || state.overlay == OverlayScreen.ADD_ACCOUNT -> {
                AccountSetupScreen(
                    privatePortalConfigured = viewModel.privatePortalConfigured,
                    isConnecting = state.isConnecting,
                    errorMessage = state.errorMessage,
                    onClearError = viewModel::clearError,
                    onConnect = viewModel::connectAccount,
                    onCancel = if (state.local.accounts.isEmpty()) null else viewModel::dismissOverlay,
                )
            }
            else -> {
                HomeShell(state = state, viewModel = viewModel, isTelevision = isTelevision)
            }
        }

        LoadingOverlay(visible = state.isLoading && state.overlay != OverlayScreen.PLAYER)
    }
}

@Composable
private fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MarsLogo()
        Spacer(Modifier.height(12.dp))
        Text("Your channels. One orbit.", color = MarsMuted)
    }
}

@Composable
private fun HomeShell(state: MarsUiState, viewModel: MarsTvViewModel, isTelevision: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val railLayout = isTelevision || maxWidth >= 760.dp
        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                accountName = state.activeAccount?.name.orEmpty(),
                profileName = state.activeProfile?.name.orEmpty(),
                onProfiles = viewModel::showProfiles,
            )
            ErrorBanner(
                message = state.errorMessage,
                onDismiss = viewModel::clearError,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.errorMessage != null) Spacer(Modifier.height(8.dp))

            if (railLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    SideNavigation(
                        selected = state.destination,
                        onSelect = viewModel::setDestination,
                        modifier = Modifier.width(if (isTelevision) 184.dp else 160.dp).fillMaxHeight(),
                    )
                    DestinationContent(
                        state = state,
                        viewModel = viewModel,
                        isTelevision = isTelevision,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp, end = 18.dp, top = 8.dp),
                    )
                }
            } else {
                DestinationContent(
                    state = state,
                    viewModel = viewModel,
                    isTelevision = false,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
                BottomNavigation(selected = state.destination, onSelect = viewModel::setDestination)
            }
        }
    }
}

@Composable
private fun HomeTopBar(accountName: String, profileName: String, onProfiles: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(70.dp).background(MarsMidnight).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MarsLogo(compact = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(accountName, color = MarsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Spacer(Modifier.width(14.dp))
            FocusSurface(onClick = onProfiles) {
                Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(MarsViolet),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(profileName, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DestinationContent(
    state: MarsUiState,
    viewModel: MarsTvViewModel,
    isTelevision: Boolean,
    modifier: Modifier,
) {
    val hasPin = !state.activeProfile?.pinHash.isNullOrBlank()
    val openSettings = { viewModel.setDestination(MainDestination.SETTINGS) }
    val accountId = state.activeAccount?.id.orEmpty()
    val blockedCategoryKeys = state.activeProfile?.restrictedCategoryKeys.orEmpty() - state.unlockedCategoryKeys
    when (state.destination) {
        MainDestination.LIVE -> LiveGuideScreen(
            catalog = state.catalog,
            favouriteKeys = state.favouriteKeys,
            profileHasPin = hasPin,
            isCategoryLocked = viewModel::isCategoryLocked,
            pinMatches = viewModel::pinMatches,
            onUnlockCategory = viewModel::unlockCategory,
            onPlayChannel = viewModel::playChannel,
            onPlayProgramme = viewModel::playProgramme,
            onToggleFavourite = viewModel::toggleFavourite,
            onOpenSettings = openSettings,
            modifier = modifier,
        )
        MainDestination.MOVIES -> MediaCatalogScreen(
            accountId = accountId,
            title = "Movies",
            subtitle = "On-demand titles from ${state.activeAccount?.name.orEmpty()}",
            categoriesSource = { viewModel.observeCategories(accountId, ContentKind.MOVIE) },
            contentSource = { categoryKey, blocked ->
                viewModel.pagedMedia(accountId, ContentKind.MOVIE, categoryKey, blocked)
            },
            blockedCategoryKeys = blockedCategoryKeys,
            favouriteKeys = state.favouriteKeys,
            isTelevision = isTelevision,
            profileHasPin = hasPin,
            isCategoryLocked = viewModel::isCategoryLocked,
            pinMatches = viewModel::pinMatches,
            onUnlockCategory = viewModel::unlockCategory,
            onOpenSettings = openSettings,
            onOpen = viewModel::openMedia,
            onToggleFavourite = viewModel::toggleFavourite,
            modifier = modifier,
        )
        MainDestination.SERIES -> MediaCatalogScreen(
            accountId = accountId,
            title = "Series",
            subtitle = "Browse shows and episodes",
            categoriesSource = { viewModel.observeCategories(accountId, ContentKind.SERIES) },
            contentSource = { categoryKey, blocked ->
                viewModel.pagedMedia(accountId, ContentKind.SERIES, categoryKey, blocked)
            },
            blockedCategoryKeys = blockedCategoryKeys,
            favouriteKeys = state.favouriteKeys,
            isTelevision = isTelevision,
            profileHasPin = hasPin,
            isCategoryLocked = viewModel::isCategoryLocked,
            pinMatches = viewModel::pinMatches,
            onUnlockCategory = viewModel::unlockCategory,
            onOpenSettings = openSettings,
            onOpen = viewModel::openMedia,
            onToggleFavourite = viewModel::toggleFavourite,
            modifier = modifier,
        )
        MainDestination.SEARCH -> SearchScreen(
            accountId = accountId,
            catalogRevision = state.catalog.loadedAt,
            query = state.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            blockedCategoryKeys = blockedCategoryKeys,
            searchSource = { query, blocked -> viewModel.searchCatalog(accountId, query, blocked) },
            favouriteKeys = state.favouriteKeys,
            isTelevision = isTelevision,
            onPlayChannel = viewModel::playChannel,
            onOpenMedia = viewModel::openMedia,
            onToggleFavourite = viewModel::toggleFavourite,
            modifier = modifier,
        )
        MainDestination.LIBRARY -> LibraryScreen(
            accountId = accountId,
            catalogRevision = state.catalog.loadedAt,
            favouriteKeys = state.favouriteKeys,
            blockedCategoryKeys = blockedCategoryKeys,
            favouritesSource = { keys, blocked -> viewModel.favouriteCatalog(accountId, keys, blocked) },
            continueWatching = state.continueWatching,
            history = state.watchHistory,
            isTelevision = isTelevision,
            onPlayChannel = viewModel::playChannel,
            onOpenMedia = viewModel::openMedia,
            onPlayHistory = viewModel::playHistory,
            onToggleFavourite = viewModel::toggleFavourite,
            onClearHistory = viewModel::clearHistory,
            modifier = modifier,
        )
        MainDestination.SETTINGS -> SettingsScreen(
            accounts = state.local.accounts,
            activeAccountId = state.activeAccount?.id,
            profiles = state.local.profiles,
            activeProfile = state.activeProfile,
            categories = state.catalog.liveCategories + state.catalog.movieCategories + state.catalog.seriesCategories,
            pinMatches = viewModel::pinMatches,
            onSelectAccount = viewModel::selectAccount,
            onRemoveAccount = viewModel::removeAccount,
            onAddAccount = viewModel::showAddAccount,
            onRefresh = { viewModel.loadActiveAccount(force = true) },
            onOpenProfiles = viewModel::showProfiles,
            onSetPin = viewModel::setProfilePin,
            onToggleCategory = viewModel::toggleCategoryRestriction,
            modifier = modifier,
        )
    }
}

private data class NavItem(val destination: MainDestination, val icon: ImageVector)

private val navItems = listOf(
    NavItem(MainDestination.LIVE, Icons.Default.LiveTv),
    NavItem(MainDestination.MOVIES, Icons.Default.LocalMovies),
    NavItem(MainDestination.SERIES, Icons.Default.VideoLibrary),
    NavItem(MainDestination.SEARCH, Icons.Default.Search),
    NavItem(MainDestination.LIBRARY, Icons.Default.Favorite),
    NavItem(MainDestination.SETTINGS, Icons.Default.Settings),
)

@Composable
private fun SideNavigation(selected: MainDestination, onSelect: (MainDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.background(MarsSurface.copy(alpha = 0.48f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        navItems.forEach { item ->
            FocusSurface(
                onClick = { onSelect(item.destination) },
                selected = selected == item.destination,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, contentDescription = null, tint = if (selected == item.destination) MarsRed else MarsMuted)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.destination.label,
                        color = if (selected == item.destination) MarsWhite else MarsMuted,
                        fontWeight = if (selected == item.destination) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(selected: MainDestination, onSelect: (MainDestination) -> Unit) {
    NavigationBar(containerColor = MarsSurface, modifier = Modifier.navigationBarsPadding()) {
        navItems.forEach { item ->
            NavigationBarItem(
                selected = selected == item.destination,
                onClick = { onSelect(item.destination) },
                icon = { Icon(item.icon, contentDescription = item.destination.label) },
                label = { Text(if (item.destination == MainDestination.LIBRARY) "My TV" else item.destination.label.substringBefore(' ')) },
            )
        }
    }
}
