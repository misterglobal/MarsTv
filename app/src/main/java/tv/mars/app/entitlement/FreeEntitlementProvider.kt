package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FreeEntitlementProvider : EntitlementProvider {
    private val current = MutableStateFlow<EntitlementState>(EntitlementState.Free)
    override val state: StateFlow<EntitlementState> = current

    override suspend fun refresh(reason: RefreshReason): EntitlementResult = EntitlementResult.Unchanged(current.value)

    override suspend fun restore(): EntitlementResult = EntitlementResult.Unchanged(current.value)
}
