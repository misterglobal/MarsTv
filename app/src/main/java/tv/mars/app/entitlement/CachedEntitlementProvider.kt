package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class CachedEntitlementProvider(
    private val store: EntitlementStore,
    private val verifier: EntitlementTokenVerifier,
    private val identity: DeviceIdentity,
) : EntitlementProvider {
    private val current = MutableStateFlow<EntitlementState>(EntitlementState.Loading)
    override val state: StateFlow<EntitlementState> = current

    override suspend fun restore(): EntitlementResult {
        val stored = store.load()
        if (stored == null || stored.compactJws.isBlank()) {
            current.value = EntitlementState.Free
            return EntitlementResult.Updated(current.value)
        }
        val verified = verifier.verifyCached(stored.compactJws, identity.deviceUuid(), identity.publicKeyThumbprint())
            .getOrElse {
                current.value = EntitlementState.Free
                return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
            }
        if (verified.state.licenseId != stored.licenseId ||
            verified.state.licenseVersion != stored.licenseVersion ||
            verified.state.licenseVersion < stored.revocationFloor
        ) {
            current.value = EntitlementState.Free
            return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
        }
        current.value = verified.state
        return EntitlementResult.Updated(verified.state)
    }

    override suspend fun refresh(reason: RefreshReason): EntitlementResult = EntitlementResult.Unchanged(current.value)
}
