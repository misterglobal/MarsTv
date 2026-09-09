package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerializationException
import tv.mars.app.data.network.BackendHttpException
import tv.mars.app.data.network.MarsLicensingApi
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.URI
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

internal class DirectEntitlementProvider(
    private val store: EntitlementStore,
    private val verifier: EntitlementTokenVerifier,
    private val identity: DeviceIdentity,
    private val api: MarsLicensingApi,
    private val appVersionCode: Int,
) : EntitlementProvider {
    private val current = MutableStateFlow<EntitlementState>(EntitlementState.Loading)
    override val state: StateFlow<EntitlementState> = current
    private val currentActivation = MutableStateFlow<ActivationState>(ActivationState.Idle)
    override val activationState: StateFlow<ActivationState> = currentActivation

    override suspend fun beginActivation(): ActivationState {
        if (!verifier.isConfigured) return activationFailure("Activation is not configured in this build")
        currentActivation.value = ActivationState.Loading
        return try {
            val session = if (identity.isRegistered()) api.createActivationSession(identity)
            else api.register(identity, appVersionCode)
            val ready = session.toActivationState(identity.deviceUuid())
            currentActivation.value = ready
            ready
        } catch (_: SecurityException) {
            activationFailure("This device could not create a secure activation identity")
        } catch (error: BackendHttpException) {
            activationFailure("Activation service returned HTTP ${error.statusCode}")
        } catch (_: SerializationException) {
            activationFailure("Activation service returned malformed JSON")
        } catch (_: IllegalArgumentException) {
            activationFailure("Activation service returned invalid session details")
        } catch (_: SocketTimeoutException) {
            activationFailure("MarsTV activation timed out. Please try again")
        } catch (_: UnknownHostException) {
            activationFailure("MarsTV activation DNS lookup failed")
        } catch (_: SSLException) {
            activationFailure("MarsTV activation TLS connection failed")
        } catch (_: ConnectException) {
            activationFailure("MarsTV activation connection was refused or reset")
        } catch (_: EOFException) {
            activationFailure("MarsTV activation response ended early")
        } catch (_: ProtocolException) {
            activationFailure("MarsTV activation response was incomplete")
        } catch (error: IOException) {
            activationFailure("MarsTV activation network error (${error.javaClass.simpleName})")
        } catch (_: Exception) {
            activationFailure("Activation failed unexpectedly")
        }
    }

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
            beginActivation()
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
            currentActivation.value = ActivationState.Activated
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

    private fun activationFailure(message: String): ActivationState.Failed =
        ActivationState.Failed(message).also { currentActivation.value = it }
}

internal fun tv.mars.app.data.network.ActivationSession.toActivationState(expectedDeviceId: String): ActivationState.Ready {
    require(deviceId == expectedDeviceId) { "Activation session belongs to another device" }
    require(deviceCode.matches(Regex("MARS-[A-HJ-NP-Z2-9]{4,12}"))) { "Invalid device code" }
    require(activationCode.matches(Regex("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}"))) { "Invalid activation code" }
    val activationUri = URI(activationUrl)
    val qrUri = URI(qrPayload)
    require(activationUri.scheme == "https" && activationUri.host == "marstv.online") { "Invalid activation URL" }
    require(qrUri.scheme == "https" && qrUri.host == "marstv.online") { "Invalid QR payload" }
    val expiry = Instant.parse(expiresAt)
    require(expiry.isAfter(Instant.now())) { "Activation session has expired" }
    return ActivationState.Ready(deviceCode, activationCode, activationUrl, qrPayload, expiry)
}
