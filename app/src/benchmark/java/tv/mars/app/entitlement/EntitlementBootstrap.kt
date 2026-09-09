package tv.mars.app.entitlement

import android.content.Context

internal fun createEntitlementManager(context: Context): EntitlementManager = DefaultEntitlementManager(FreeEntitlementProvider())
