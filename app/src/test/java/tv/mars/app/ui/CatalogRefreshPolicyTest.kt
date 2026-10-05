package tv.mars.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRefreshPolicyTest {
    private val now = 2_000_000_000_000L

    @Test fun completeRecentCatalogIsReusedAcrossSessions() {
        assertFalse(shouldAutomaticallyRefreshCatalog(now - AUTOMATIC_CATALOG_REFRESH_INTERVAL_MS + 1, null, now))
    }

    @Test fun staleCatalogRefreshesWhenThereWasNoRecentAttempt() {
        assertTrue(shouldAutomaticallyRefreshCatalog(now - AUTOMATIC_CATALOG_REFRESH_INTERVAL_MS, null, now))
    }

    @Test fun recentAttemptThrottlesRepeatedRefreshOfUsableCatalog() {
        val staleCatalog = now - AUTOMATIC_CATALOG_REFRESH_INTERVAL_MS
        assertFalse(shouldAutomaticallyRefreshCatalog(staleCatalog, now - CATALOG_REFRESH_RETRY_COOLDOWN_MS + 1, now))
        assertTrue(shouldAutomaticallyRefreshCatalog(staleCatalog, now - CATALOG_REFRESH_RETRY_COOLDOWN_MS, now))
    }

    @Test fun interruptedPostCatalogGuideImportRetriesAfterCooldown() {
        val freshlyCommittedCatalog = now - 1_000
        assertFalse(shouldAutomaticallyRefreshCatalog(freshlyCommittedCatalog, now - 1_000, now))
        assertTrue(
            shouldAutomaticallyRefreshCatalog(
                freshlyCommittedCatalog,
                now - CATALOG_REFRESH_RETRY_COOLDOWN_MS,
                now,
            ),
        )
    }

    @Test fun missingInterruptedOrInvalidCatalogTimestampsAlwaysRefresh() {
        assertTrue(shouldAutomaticallyRefreshCatalog(0L, now, now))
        assertTrue(shouldAutomaticallyRefreshCatalog(-1L, now, now))
        assertTrue(shouldAutomaticallyRefreshCatalog(now + 1, now, now))
    }
}
