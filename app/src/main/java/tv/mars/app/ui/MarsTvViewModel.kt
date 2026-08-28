package tv.mars.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.mars.app.BuildConfig
import tv.mars.app.core.CatalogBundle
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.Episode
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.LocalState
import tv.mars.app.core.MainDestination
import tv.mars.app.core.MediaContent
import tv.mars.app.core.OverlayScreen
import tv.mars.app.core.PlayerRequest
import tv.mars.app.core.Programme
import tv.mars.app.core.SeriesDetails
import tv.mars.app.core.SourceType
import tv.mars.app.core.ViewerProfile
import tv.mars.app.core.WatchRecord
import tv.mars.app.data.local.SecureStateStore
import tv.mars.app.data.repository.IptvRepository
import java.util.concurrent.ConcurrentHashMap

data class MarsUiState(
    val local: LocalState = LocalState(),
    val catalog: CatalogBundle = CatalogBundle.empty(),
    val destination: MainDestination = MainDestination.LIVE,
    val overlay: OverlayScreen = OverlayScreen.NONE,
    val selectedSeries: SeriesDetails? = null,
    val playerRequest: PlayerRequest? = null,
    val isLoading: Boolean = false,
    val isConnecting: Boolean = false,
    val hasLoadedState: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val unlockedCategoryKeys: Set<String> = emptySet(),
) {
    val activeAccount: IptvAccount?
        get() = local.accounts.firstOrNull { it.id == local.activeAccountId } ?: local.accounts.firstOrNull()

    val activeProfile: ViewerProfile?
        get() = local.profiles.firstOrNull { it.id == local.activeProfileId } ?: local.profiles.firstOrNull()

    val favouriteKeys: Set<String>
        get() = activeProfile?.let { local.favouriteKeysByProfile[it.id].orEmpty() }.orEmpty()

    val watchHistory: List<WatchRecord>
        get() = activeProfile?.let { local.watchHistoryByProfile[it.id].orEmpty() }.orEmpty()

    val continueWatching: List<WatchRecord>
        get() = watchHistory.filter(WatchRecord::canContinue)
}

class MarsTvViewModel(application: Application) : AndroidViewModel(application) {
    private val stateStore = SecureStateStore(application)
    private val catalogPersistence = tv.mars.app.data.local.CatalogPersistence(application)
    private val repository = IptvRepository(catalogPersistence)
    private val catalogCache = ConcurrentHashMap<String, CatalogBundle>()
    private val _uiState = MutableStateFlow(MarsUiState())
    val uiState: StateFlow<MarsUiState> = _uiState.asStateFlow()

    val privatePortalConfigured: Boolean get() = BuildConfig.PRIVATE_PORTAL_URL.isNotBlank()

    init {
        viewModelScope.launch {
            stateStore.state.collectLatest { local ->
                val previousAccount = _uiState.value.activeAccount?.id
                _uiState.update {
                    it.copy(
                        local = local,
                        hasLoadedState = true,
                        unlockedCategoryKeys = if (it.activeProfile?.id == local.activeProfileId) {
                            it.unlockedCategoryKeys
                        } else {
                            emptySet()
                        },
                    )
                }
                val activeId = _uiState.value.activeAccount?.id
                if (activeId != null && ((activeId != previousAccount) || (_uiState.value.catalog.accountId != activeId))) {
                    loadActiveAccount()
                }
            }
        }
    }

    fun connectAccount(
        sourceType: SourceType,
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        m3uUrl: String,
    ) {
        if (_uiState.value.isConnecting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
            val resolvedServer = if (sourceType == SourceType.PRIVATE_XTREAM) {
                BuildConfig.PRIVATE_PORTAL_URL
            } else {
                normalizeUrl(serverUrl)
            }
            val account = IptvAccount(
                name = name.trim().ifBlank {
                    when (sourceType) {
                        SourceType.PRIVATE_XTREAM -> "MarsTV account"
                        SourceType.XTREAM -> "Xtream account"
                        SourceType.M3U -> "My playlist"
                    }
                },
                sourceType = sourceType,
                serverUrl = resolvedServer,
                username = username.trim(),
                password = password,
                m3uUrl = normalizeUrl(m3uUrl),
            )

            releaseCatalogBeforeLoad(account.id)
            runCatching { repository.loadCatalog(account) }
                .onSuccess { catalog ->
                    catalogCache[account.id] = catalog
                    catalogPersistence.save(catalog)
                    _uiState.update {
                        it.copy(
                            catalog = catalog,
                            overlay = OverlayScreen.NONE,
                            isConnecting = false,
                            errorMessage = null,
                        )
                    }
                    stateStore.addAccount(account)
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            errorMessage = error.message?.take(240) ?: "Could not connect to this source",
                        )
                    }
                }
        }
    }

    fun loadActiveAccount(force: Boolean = false) {
        val account = _uiState.value.activeAccount ?: return

        // Keep only the active account in memory to prevent OOM on large playlists
        val otherAccountIds = catalogCache.keys.filter { it != account.id }
        otherAccountIds.forEach {
            catalogCache.remove(it)
            repository.clearCache(it)
        }

        if (force) releaseCatalogBeforeLoad(account.id)

        val cached = catalogCache[account.id]
        if (!force && cached != null) {
            _uiState.update { it.copy(catalog = cached, isLoading = false, errorMessage = null) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            
            if (!force) {
                val persisted = catalogPersistence.load(account.id)
                if (persisted != null) {
                    val dayMs = 24 * 60 * 60 * 1000L
                    if (System.currentTimeMillis() - persisted.loadedAt < dayMs) {
                        catalogCache[account.id] = persisted
                        _uiState.update { it.copy(catalog = persisted, isLoading = false) }
                        return@launch
                    }
                }
            }

            // Do not hold an expired catalog while constructing its replacement.
            releaseCatalogBeforeLoad(account.id)
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            runCatching { repository.loadCatalog(account) }
                .onSuccess { catalog ->
                    catalogCache[account.id] = catalog
                    catalogPersistence.save(catalog)
                    _uiState.update { it.copy(catalog = catalog, isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message?.take(240) ?: "Could not refresh this account",
                        )
                    }
                }
        }
    }

    fun selectAccount(accountId: String) {
        viewModelScope.launch { stateStore.setActiveAccount(accountId) }
    }

    fun removeAccount(accountId: String) {
        catalogCache.remove(accountId)
        repository.clearCache(accountId)
        viewModelScope.launch {
            catalogPersistence.clear(accountId)
            stateStore.removeAccount(accountId)
        }
    }

    fun setDestination(destination: MainDestination) {
        _uiState.update { it.copy(destination = destination, searchQuery = if (destination == MainDestination.SEARCH) it.searchQuery else "") }
    }

    fun showAddAccount() = _uiState.update { it.copy(overlay = OverlayScreen.ADD_ACCOUNT, errorMessage = null) }
    fun showProfiles() = _uiState.update { it.copy(overlay = OverlayScreen.PROFILES, errorMessage = null) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
    fun setSearchQuery(value: String) = _uiState.update { it.copy(searchQuery = value) }

    fun dismissOverlay() {
        _uiState.update {
            it.copy(
                overlay = OverlayScreen.NONE,
                selectedSeries = null,
                playerRequest = null,
            )
        }
    }

    fun playChannel(channel: Channel) = openPlayer(repository.liveRequest(channel))

    fun playProgramme(channel: Channel, programme: Programme) {
        val account = _uiState.value.activeAccount ?: return
        val request = when {
            programme.isLive -> repository.liveRequest(channel)
            programme.isPast -> repository.catchUpRequest(account, channel, programme)
            else -> null
        }
        request?.let(::openPlayer)
    }

    fun openMedia(media: MediaContent) {
        if (media.kind != ContentKind.SERIES || media.seriesId.isBlank()) {
            if (media.playbackUrl.isNotBlank()) {
                val resume = _uiState.value.watchHistory.firstOrNull { it.contentKey == media.key }?.positionMs ?: 0L
                openPlayer(
                    PlayerRequest(
                        contentKey = media.key,
                        accountId = media.accountId,
                        title = media.title,
                        url = media.playbackUrl,
                        kind = media.kind,
                        artworkUrl = media.artworkUrl,
                        resumePositionMs = resume,
                    ),
                )
            }
            return
        }

        val account = _uiState.value.activeAccount ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { repository.loadSeriesDetails(account, media) }
                .onSuccess { details ->
                    _uiState.update {
                        it.copy(
                            selectedSeries = details,
                            overlay = OverlayScreen.SERIES_DETAILS,
                            isLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load episodes") }
                }
        }
    }

    fun playEpisode(episode: Episode) {
        val resume = _uiState.value.watchHistory.firstOrNull { it.contentKey == episode.key }?.positionMs ?: 0L
        openPlayer(
            PlayerRequest(
                contentKey = episode.key,
                accountId = episode.accountId,
                title = episode.title,
                url = episode.playbackUrl,
                kind = ContentKind.EPISODE,
                artworkUrl = episode.artworkUrl,
                resumePositionMs = resume,
            ),
        )
    }

    fun playHistory(record: WatchRecord) = openPlayer(
        PlayerRequest(
            contentKey = record.contentKey,
            accountId = record.accountId,
            title = record.title,
            url = record.playbackUrl,
            kind = record.kind,
            artworkUrl = record.artworkUrl,
            resumePositionMs = record.positionMs,
        ),
    )

    fun closePlayer(positionMs: Long, durationMs: Long) {
        val request = _uiState.value.playerRequest
        val profile = _uiState.value.activeProfile
        if (request != null && profile != null) {
            viewModelScope.launch {
                stateStore.recordWatch(
                    profile.id,
                    WatchRecord(
                        contentKey = request.contentKey,
                        accountId = request.accountId,
                        title = request.title,
                        playbackUrl = request.url,
                        artworkUrl = request.artworkUrl,
                        kind = request.kind,
                        positionMs = positionMs,
                        durationMs = durationMs.coerceAtLeast(0),
                    ),
                )
            }
        }
        _uiState.update { state ->
            state.copy(
                overlay = if (state.selectedSeries != null) OverlayScreen.SERIES_DETAILS else OverlayScreen.NONE,
                playerRequest = null,
            )
        }
    }

    fun toggleFavourite(contentKey: String) {
        val profileId = _uiState.value.activeProfile?.id ?: return
        viewModelScope.launch { stateStore.toggleFavourite(profileId, contentKey) }
    }

    fun addProfile(name: String) {
        viewModelScope.launch { stateStore.addProfile(name) }
    }

    fun selectProfile(profileId: String) {
        _uiState.update { it.copy(unlockedCategoryKeys = emptySet(), overlay = OverlayScreen.NONE) }
        viewModelScope.launch { stateStore.setActiveProfile(profileId) }
    }

    fun removeProfile(profileId: String) {
        viewModelScope.launch { stateStore.removeProfile(profileId) }
    }

    fun setProfilePin(pin: String) {
        val profile = _uiState.value.activeProfile ?: return
        val updated = profile.copy(pinHash = if (pin.isBlank()) "" else SecureStateStore.hashPin(pin))
        _uiState.update { it.copy(unlockedCategoryKeys = emptySet()) }
        viewModelScope.launch { stateStore.updateProfile(updated) }
    }

    fun toggleCategoryRestriction(categoryKey: String) {
        val profile = _uiState.value.activeProfile ?: return
        val current = profile.restrictedCategoryKeys
        _uiState.update { it.copy(unlockedCategoryKeys = it.unlockedCategoryKeys - categoryKey) }
        val updated = profile.copy(
            restrictedCategoryKeys = if (categoryKey in current) current - categoryKey else current + categoryKey,
        )
        viewModelScope.launch { stateStore.updateProfile(updated) }
    }

    fun pinMatches(pin: String): Boolean {
        val hash = _uiState.value.activeProfile?.pinHash.orEmpty()
        return hash.isNotBlank() && SecureStateStore.hashPin(pin) == hash
    }

    fun unlockCategory(categoryKey: String) {
        _uiState.update { it.copy(unlockedCategoryKeys = it.unlockedCategoryKeys + categoryKey) }
    }

    fun isCategoryLocked(categoryKey: String): Boolean {
        val state = _uiState.value
        return categoryKey in state.activeProfile?.restrictedCategoryKeys.orEmpty() &&
            categoryKey !in state.unlockedCategoryKeys
    }

    fun clearHistory() {
        val profileId = _uiState.value.activeProfile?.id ?: return
        viewModelScope.launch { stateStore.clearHistory(profileId) }
    }

    fun clearHiddenCaches() {
        val activeId = _uiState.value.activeAccount?.id
        val otherAccountIds = catalogCache.keys.filter { it != activeId }
        otherAccountIds.forEach {
            catalogCache.remove(it)
            repository.clearCache(it)
        }
    }

    private fun releaseCatalogBeforeLoad(accountId: String) {
        catalogCache.remove(accountId)
        repository.clearCache(accountId)
        _uiState.update { state ->
            if (state.catalog.accountId.isBlank()) state else state.copy(
                catalog = CatalogBundle.empty(accountId),
                selectedSeries = null,
            )
        }
    }

    private fun openPlayer(request: PlayerRequest) {
        _uiState.update { it.copy(playerRequest = request, overlay = OverlayScreen.PLAYER) }
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }
}
