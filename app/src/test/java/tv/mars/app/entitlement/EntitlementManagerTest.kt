package tv.mars.app.entitlement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EntitlementManagerTest {
    @Test
    fun `free entitlement grants no pro features`() {
        val manager = DefaultEntitlementManager(FreeEntitlementProvider())

        assertFalse(manager.hasFeature(ProFeature.MULTIPLE_ACCOUNTS))
        assertFalse(manager.hasFeature(ProFeature.CATCH_UP))
    }

    @Test
    fun `manager grants only features in verified state`() {
        val provider = FakeProvider(
            EntitlementState.Pro("plan", setOf(ProFeature.CATCH_UP), "device", "license", 1, Instant.MAX),
        )
        val manager = DefaultEntitlementManager(provider)

        assertTrue(manager.hasFeature(ProFeature.CATCH_UP))
        assertFalse(manager.hasFeature(ProFeature.MULTIPLE_ACCOUNTS))
    }

    private class FakeProvider(initial: EntitlementState) : EntitlementProvider {
        private val current = MutableStateFlow(initial)
        override val state: StateFlow<EntitlementState> = current
        override suspend fun refresh(reason: RefreshReason) = EntitlementResult.Unchanged(current.value)
        override suspend fun restore() = EntitlementResult.Unchanged(current.value)
    }
}
