package tv.mars.app.entitlement

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

internal class DebugEntitlementProvider : EntitlementProvider {
    private val current = MutableStateFlow<EntitlementState>(
        EntitlementState.Pro(
            planId = "debug_pro_lifetime_v1",
            features = ProFeature.entries.toSet(),
            deviceId = "debug-device",
            licenseId = "debug-license",
            licenseVersion = 1,
            refreshAfter = Instant.MAX,
        ),
    )
    override val state: StateFlow<EntitlementState> = current

    override suspend fun refresh(reason: RefreshReason): EntitlementResult = EntitlementResult.Unchanged(current.value)
    override suspend fun restore(): EntitlementResult = EntitlementResult.Unchanged(current.value)
}

internal fun createEntitlementManager(context: Context): EntitlementManager = DefaultEntitlementManager(DebugEntitlementProvider())
