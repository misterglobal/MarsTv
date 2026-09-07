package tv.mars.app.entitlement

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

enum class ProFeature(val id: String) {
    MULTIPLE_ACCOUNTS("multiple_accounts"),
    FULL_EPG("full_epg"),
    UNRESTRICTED_VOD_PLAYBACK("unrestricted_vod_playback"),
    UNRESTRICTED_SERIES_PLAYBACK("unrestricted_series_playback"),
    GLOBAL_SEARCH("global_search"),
    FAVORITE_GROUPS("favorite_groups"),
    MULTIPLE_PROFILES("multiple_profiles"),
    CONTINUE_WATCHING("continue_watching"),
    CATCH_UP("catch_up"),
    PARENTAL_CONTROLS("parental_controls"),
    PICTURE_IN_PICTURE("picture_in_picture"),
    ADVANCED_THEMES("advanced_themes"),
    RECORDING("recording"),
    MULTIVIEW("multiview"),
}

sealed interface EntitlementState {
    data object Loading : EntitlementState
    data object Free : EntitlementState
    data class Pro(
        val planId: String,
        val features: Set<ProFeature>,
        val deviceId: String,
        val licenseId: String,
        val licenseVersion: Long,
        val refreshAfter: Instant,
    ) : EntitlementState
    data class ActionRequired(val reason: EntitlementIssue) : EntitlementState
}

enum class EntitlementIssue { NETWORK, INVALID_TOKEN, REVOKED, DEVICE_KEY_MISSING }
enum class RefreshReason { APP_START, USER_REQUEST, FEATURE_REQUIRED }

sealed interface EntitlementResult {
    data class Updated(val state: EntitlementState) : EntitlementResult
    data class Unchanged(val state: EntitlementState) : EntitlementResult
    data class Failed(val issue: EntitlementIssue) : EntitlementResult
}

interface EntitlementManager {
    val state: StateFlow<EntitlementState>
    suspend fun refresh(reason: RefreshReason): EntitlementResult
    fun hasFeature(feature: ProFeature): Boolean
    suspend fun restore(): EntitlementResult
}

internal interface EntitlementProvider {
    val state: StateFlow<EntitlementState>
    suspend fun refresh(reason: RefreshReason): EntitlementResult
    suspend fun restore(): EntitlementResult
}

internal class DefaultEntitlementManager(
    private val provider: EntitlementProvider,
) : EntitlementManager {
    override val state: StateFlow<EntitlementState> = provider.state

    override suspend fun refresh(reason: RefreshReason): EntitlementResult = provider.refresh(reason)

    override fun hasFeature(feature: ProFeature): Boolean =
        (state.value as? EntitlementState.Pro)?.features?.contains(feature) == true

    override suspend fun restore(): EntitlementResult = provider.restore()
}
