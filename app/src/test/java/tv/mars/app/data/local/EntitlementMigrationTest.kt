package tv.mars.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.LocalState
import tv.mars.app.core.SourceType
import tv.mars.app.core.ViewerProfile

class EntitlementMigrationTest {
    @Test
    fun `migration preserves valid active selections and all data`() {
        val accounts = listOf(account("one"), account("two"))
        val profiles = listOf(ViewerProfile(id = "first", name = "First"), ViewerProfile(id = "second", name = "Second"))
        val original = LocalState(accounts = accounts, activeAccountId = "two", profiles = profiles, activeProfileId = "second")

        val migrated = original.migrateForFreeGates()

        assertEquals(accounts, migrated.accounts)
        assertEquals(profiles, migrated.profiles)
        assertEquals("two", migrated.activeAccountId)
        assertEquals("second", migrated.activeProfileId)
        assertEquals(1, migrated.entitlementMigrationVersion)
        assertEquals(migrated, migrated.migrateForFreeGates())
    }

    @Test
    fun `migration deterministically repairs dangling selections`() {
        val migrated = LocalState(
            accounts = listOf(account("first"), account("second")),
            activeAccountId = "missing",
            profiles = listOf(ViewerProfile(id = "profile-first", name = "First")),
            activeProfileId = "missing",
        ).migrateForFreeGates()

        assertEquals("first", migrated.activeAccountId)
        assertEquals("profile-first", migrated.activeProfileId)
    }

    @Test
    fun `migration leaves empty account selection and creates one profile`() {
        val migrated = LocalState().migrateForFreeGates()

        assertNull(migrated.activeAccountId)
        assertEquals(1, migrated.profiles.size)
        assertEquals(migrated.profiles.single().id, migrated.activeProfileId)
    }

    private fun account(id: String) = IptvAccount(id = id, name = id, sourceType = SourceType.M3U)
}
