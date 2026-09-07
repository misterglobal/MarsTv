package tv.mars.app.entitlement

internal fun createEntitlementManager(): EntitlementManager = DefaultEntitlementManager(FreeEntitlementProvider())
