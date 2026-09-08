package tv.mars.app.entitlement

import android.content.Context
import tv.mars.app.BuildConfig
import tv.mars.app.data.network.MarsBackendClient
import tv.mars.app.data.network.MarsLicensingApi

internal fun createEntitlementManager(context: Context): EntitlementManager = DefaultEntitlementManager(
    DirectEntitlementProvider(
        store = EncryptedEntitlementStore(context),
        verifier = EntitlementTokenVerifier.fromPem(BuildConfig.ENTITLEMENT_PUBLIC_KEY_PEM),
        identity = DeviceIdentity(context),
        api = MarsLicensingApi(MarsBackendClient(BuildConfig.MARS_BACKEND_URL)),
        appVersionCode = BuildConfig.VERSION_CODE,
    ),
)
