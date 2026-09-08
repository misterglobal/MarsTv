package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.mars.app.data.network.ActivationSession
import tv.mars.app.data.network.MarsLicensingApi
import java.io.IOException

internal class DirectEntitlementProvider(
    private val store: EntitlementStore,
    private val verifier: EntitlementTokenVerifier,
    private val identity: DeviceIdentity,
    private val api: MarsLicensingApi,
    private val appVersionCode: Int,
) : EntitlementProvider {
    private val current = MutableStateFlow<EntitlementState>(EntitlementState.Loading)
    override val state: StateFlow<EntitlementState> = current
    private val currentActivation = MutableStateFlow<ActivationSession?>(null)
    val activationSession: StateFlow<ActivationSession?> = currentActivation

    override suspend fun restore(): EntitlementResult {
        val stored = store.load()
        if (stored == null || stored.compactJws.isBlank()) return update(EntitlementState.Free)
        val verified = verifier.verifyCached(stored.compactJws, identity.deviceUuid(), identity.publicKeyThumbprint())
            .getOrElse { return failFree(EntitlementIssue.INVALID_TOKEN) }
        if (verified.state.licenseId != stored.licenseId ||
            verified.state.licenseVersion != stored.licenseVersion ||
            verified.state.licenseVersion < stored.revocationFloor
        ) return failFree(EntitlementIssue.INVALID_TOKEN)
        return update(verified.state)
    }

    override suspend fun refresh(reason: RefreshReason): EntitlementResult = try {
        if (!identity.isRegistered()) {
            if (reason != RefreshReason.USER_REQUEST) {
                if (current.value is EntitlementState.Loading) return update(EntitlementState.Free)
                return EntitlementResult.Unchanged(current.value)
            }
            currentActivation.value = api.register(identity, appVersionCode)
            if (current.value is EntitlementState.Loading) update(EntitlementState.Free)
            else EntitlementResult.Unchanged(current.value)
        } else {
            applyStatus(api.status(identity))
        }
    } catch (_: IOException) {
        preserveOnNetworkFailure()
    } catch (_: SecurityException) {
        failFree(EntitlementIssue.DEVICE_KEY_MISSING)
    } catch (_: Exception) {
        preserveOnNetworkFailure()
    }

    private fun applyStatus(response: tv.mars.app.data.network.DeviceStatusResponse): EntitlementResult = when (response.status) {
        "free" -> if (current.value is EntitlementState.Pro) EntitlementResult.Unchanged(current.value) else update(EntitlementState.Free)
        "pro" -> {
            val token = response.entitlement ?: return failRefresh(EntitlementIssue.INVALID_TOKEN)
            val verified = verifier.verifyReceived(token, identity.deviceUuid(), identity.publicKeyThumbprint(), response.server_time)
                .getOrElse { return failRefresh(EntitlementIssue.INVALID_TOKEN) }
            val existing = store.load()
            if (existing != null && existing.licenseId == verified.state.licenseId &&
                verified.state.licenseVersion < maxOf(existing.licenseVersion, existing.revocationFloor)
            ) return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
            store.save(StoredEntitlement(token, verified.state.licenseId, verified.state.licenseVersion, existing?.revocationFloor ?: 0))
            currentActivation.value = null
            update(verified.state)
        }
        "revoked" -> {
            val token = response.revocation ?: return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
            val revocation = verifier.verifyRevocation(token, identity.deviceUuid(), response.server_time)
                .getOrElse { return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN) }
            val existing = store.load()
            if (existing != null && existing.licenseId == revocation.licenseId &&
                revocation.licenseVersion <= maxOf(existing.licenseVersion, existing.revocationFloor)
            ) return EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
            store.clearTokenKeepingFloor(revocation.licenseId, revocation.licenseVersion)
            update(EntitlementState.ActionRequired(EntitlementIssue.REVOKED))
        }
        else -> EntitlementResult.Failed(EntitlementIssue.INVALID_TOKEN)
    }

    private fun preserveOnNetworkFailure(): EntitlementResult {
        if (current.value is EntitlementState.Loading) current.value = EntitlementState.Free
        return EntitlementResult.Failed(EntitlementIssue.NETWORK)
    }

    private fun failFree(issue: EntitlementIssue): EntitlementResult {
        current.value = EntitlementState.Free
        return EntitlementResult.Failed(issue)
    }

    private fun failRefresh(issue: EntitlementIssue): EntitlementResult {
        if (current.value !is EntitlementState.Pro) current.value = EntitlementState.Free
        return EntitlementResult.Failed(issue)
    }

    private fun update(next: EntitlementState): EntitlementResult {
        val changed = current.value != next
        current.value = next
        return if (changed) EntitlementResult.Updated(next) else EntitlementResult.Unchanged(next)
    }
}
