package tv.mars.app.entitlement

import android.content.Context
import tv.mars.app.BuildConfig

internal fun createEntitlementManager(context: Context): EntitlementManager = DefaultEntitlementManager(
    CachedEntitlementProvider(
        store = EncryptedEntitlementStore(context),
        verifier = EntitlementTokenVerifier.fromPem(BuildConfig.ENTITLEMENT_PUBLIC_KEY_PEM),
        identity = DeviceIdentity(context),
    ),
)
