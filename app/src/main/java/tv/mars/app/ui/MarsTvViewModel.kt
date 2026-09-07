package tv.mars.app.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.mars.app.BuildConfig
import tv.mars.app.core.CatalogLookup
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
import tv.mars.app.data.local.MarsTvDatabase
import tv.mars.app.data.local.RoomCatalogStore
import tv.mars.app.data.repository.IptvRepository

data class MarsUiState(
    val local: LocalState = LocalState(),
    val loadedCatalogAccountId: String? = null,
    val catalogRevision: Long = 0L,
    val destination: MainDestination = MainDestination.LIVE,
    val overlay: OverlayScreen = OverlayScreen.NONE,
    val selectedSeries: SeriesDetails? = null,
    val playerRequest: PlayerRequest? = null,
    val isLoading: Boolean = false,
    val isCatalogLoading: Boolean = false,
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
    private val roomCatalog = RoomCatalogStore(MarsTvDatabase.getInstance(application))
    private val repository = IptvRepository(catalogPersistence, roomCatalog)
    private var catalogJob: Job? = null
    private var catalogJobAccountId: String? = null
    private var catalogStartedAtElapsedMs = 0L
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
                if (activeId != null && catalogJob?.isActive != true &&
                    ((activeId != previousAccount) || (_uiState.value.loadedCatalogAccountId != activeId))
                ) {
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
        catalogJob?.cancel()
        catalogStartedAtElapsedMs = SystemClock.elapsedRealtime()
        Log.i(BENCHMARK_TAG, "catalog_start mode=connect source=$sourceType")
        catalogJob = viewModelScope.launch {
            _uiState.update { it.copy(isConnecting = true, isCatalogLoading = true, errorMessage = null) }
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
            catalogJobAccountId = account.id

            var accountPublished = false
            runCatching {
                repository.refreshCatalog(account) { previewRevision ->
                    val publishAccount = !accountPublished
                    if (publishAccount) {
                        accountPublished = true
                        Log.i(
                            BENCHMARK_TAG,
                            "catalog_first_usable durationMs=${SystemClock.elapsedRealtime() - catalogStartedAtElapsedMs}",
                        )
                    }
                    _uiState.update {
                        it.copy(
                            loadedCatalogAccountId = account.id,
                            catalogRevision = previewRevision,
                            overlay = OverlayScreen.NONE,
                            isConnecting = false,
                            isCatalogLoading = true,
                        )
                    }
                    if (publishAccount) stateStore.addAccount(account)
                }
            }
                .onSuccess { revision ->
                    logCatalogCompleted()
                    _uiState.update {
                        it.copy(
                            loadedCatalogAccountId = account.id,
                            catalogRevision = revision,
                            overlay = OverlayScreen.NONE,
                            isConnecting = false,
                            isCatalogLoading = false,
                            errorMessage = null,
                        )
                    }
                    if (!accountPublished) stateStore.addAccount(account)
                }
                .onFailure { error ->
                    if (error is CancellationException) {
                        Log.i(BENCHMARK_TAG, "catalog_interrupted reason=canceled")
                        return@onFailure
                    }
                    if (accountPublished) {
                        stateStore.removeAccount(account.id)
                        _uiState.update {
                            it.copy(
                                loadedCatalogAccountId = if (it.loadedCatalogAccountId == account.id) null else it.loadedCatalogAccountId,
                                isConnecting = false,
                                isCatalogLoading = false,
                            )
                        }
                    }
                    _uiState.update {
                        it.copy(
                            loadedCatalogAccountId = if (it.loadedCatalogAccountId == account.id) null else it.loadedCatalogAccountId,
                            isConnecting = false,
                            isCatalogLoading = false,
                            errorMessage = error.message?.take(240) ?: "Could not connect to this source",
                        )
                    }
                }
        }
    }

    fun loadActiveAccount(force: Boolean = false) {
        val account = _uiState.value.activeAccount ?: return
        if (!force && _uiState.value.loadedCatalogAccountId == account.id) return
        catalogJob?.cancel()
        catalogStartedAtElapsedMs = SystemClock.elapsedRealtime()
        catalogJobAccountId = account.id
        Log.i(BENCHMARK_TAG, "catalog_start mode=${if (force) "refresh" else "load"} source=${account.sourceType}")
        catalogJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, isCatalogLoading = true, errorMessage = null, selectedSeries = null)
            }
            
            if (!force) {
                val dayMs = 24 * 60 * 60 * 1000L
                val roomLoadedAt = repository.catalogLoadedAt(account.id)
                if (roomLoadedAt != null && System.currentTimeMillis() - roomLoadedAt < dayMs) {
                    _uiState.update {
                        it.copy(
                            loadedCatalogAccountId = account.id,
                            catalogRevision = roomLoadedAt,
                            isLoading = false,
                            isCatalogLoading = false,
                        )
                    }
                    return@launch
                }
                if (roomLoadedAt == null) {
                    val persisted = catalogPersistence.load(account.id)
                    if (persisted != null && System.currentTimeMillis() - persisted.loadedAt < dayMs) {
                        runCatching { repository.seedRoomFromLegacyCache(persisted) }
                            .onFailure {
                                _uiState.update { state ->
                                    state.copy(errorMessage = "Refresh this account to finish updating its catalog storage")
                                }
                            }
                            .onSuccess {
                                catalogPersistence.clear(account.id)
                                _uiState.update {
                                    it.copy(
                                        loadedCatalogAccountId = account.id,
                                        catalogRevision = persisted.loadedAt,
                                        isLoading = false,
                                        isCatalogLoading = false,
                                    )
                                }
                                return@launch
                            }
                    }
                }
            }

            _uiState.update { it.copy(isLoading = true, isCatalogLoading = true, errorMessage = null) }

            runCatching { repository.refreshCatalog(account) }
                .onSuccess { revision ->
                    logCatalogCompleted()
                    catalogPersistence.clear(account.id)
                    _uiState.update {
                        it.copy(
                            loadedCatalogAccountId = account.id,
                            catalogRevision = revision,
                            isLoading = false,
                            isCatalogLoading = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isCatalogLoading = false,
                            errorMessage = error.message?.take(240) ?: "Could not refresh this account",
                        )
                    }
                }
        }
    }

    private fun logCatalogCompleted() {
        Log.i(BENCHMARK_TAG, "catalog_complete durationMs=${SystemClock.elapsedRealtime() - catalogStartedAtElapsedMs}")
    }

    fun selectAccount(accountId: String) {
        viewModelScope.launch { stateStore.setActiveAccount(accountId) }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            if (catalogJobAccountId == accountId) {
                val job = catalogJob
                job?.cancelAndJoin()
                if (catalogJob === job) {
                    catalogJob = null
                    catalogJobAccountId = null
                }
            }
            repository.clearStoredCatalog(accountId)
            stateStore.removeAccount(accountId)
            _uiState.update {
                it.copy(
                    loadedCatalogAccountId = if (it.loadedCatalogAccountId == accountId) null else it.loadedCatalogAccountId,
                    isLoading = false,
                    isCatalogLoading = false,
                    isConnecting = false,
                )
            }
        }
    }

    fun observeCategories(accountId: String, kind: ContentKind): Flow<List<tv.mars.app.core.Category>> =
        repository.observeCategories(accountId, kind)

    fun pagedMedia(
        accountId: String,
        kind: ContentKind,
        categoryKey: String?,
        blockedCategoryKeys: Set<String>,
    ): Flow<PagingData<MediaContent>> = repository.pagedMedia(accountId, kind, categoryKey, blockedCategoryKeys)

    fun pagedChannels(
        accountId: String,
        categoryKey: String?,
        blockedCategoryKeys: Set<String>,
    ): Flow<PagingData<Channel>> = repository.pagedChannels(accountId, categoryKey, blockedCategoryKeys)

    fun programmes(
        accountId: String,
        channelEpgId: String,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<Programme>> = repository.programmes(accountId, channelEpgId, windowStart, windowEnd)

    suspend fun searchCatalog(
        accountId: String,
        query: String,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup = repository.searchCatalog(accountId, query, blockedCategoryKeys)

    suspend fun favouriteCatalog(
        accountId: String,
        favouriteKeys: Set<String>,
        blockedCategoryKeys: Set<String>,
    ): CatalogLookup = repository.favouriteCatalog(accountId, favouriteKeys, blockedCategoryKeys)

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

    fun playChannel(channel: Channel) {
        val account = _uiState.value.local.accounts.firstOrNull { it.id == channel.accountId } ?: return
        openPlayer(repository.liveRequest(account, channel))
    }

    fun playProgramme(channel: Channel, programme: Programme) {
        val account = _uiState.value.local.accounts.firstOrNull { it.id == channel.accountId } ?: return
        val request = when {
            programme.isLive -> repository.liveRequest(account, channel)
            programme.isPast -> repository.catchUpRequest(account, channel, programme)
            else -> null
        }
        request?.let(::openPlayer)
    }

    fun openMedia(media: MediaContent) {
        val account = _uiState.value.local.accounts.firstOrNull { it.id == media.accountId } ?: return
        if (media.kind != ContentKind.SERIES || media.seriesId.isBlank()) {
            if (media.playbackUrl.isNotBlank()) {
                val resume = _uiState.value.watchHistory.firstOrNull { it.contentKey == media.key }?.positionMs ?: 0L
                openPlayer(
                    PlayerRequest(
                        contentKey = media.key,
                        accountId = media.accountId,
                        title = media.title,
                        url = repository.playbackUrl(account, media.playbackUrl),
                        kind = media.kind,
                        artworkUrl = media.artworkUrl,
                        resumePositionMs = resume,
                    ),
                )
            }
            return
        }

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
        val account = _uiState.value.local.accounts.firstOrNull { it.id == episode.accountId } ?: return
        val resume = _uiState.value.watchHistory.firstOrNull { it.contentKey == episode.key }?.positionMs ?: 0L
        openPlayer(
            PlayerRequest(
                contentKey = episode.key,
                accountId = episode.accountId,
                title = episode.title,
                url = repository.playbackUrl(account, episode.playbackUrl),
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

    private companion object {
        const val BENCHMARK_TAG = "MarsCatalogMetrics"
    }
}
