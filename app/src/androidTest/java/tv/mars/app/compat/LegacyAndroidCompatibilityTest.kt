package tv.mars.app.compat

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import tv.mars.app.data.network.MarsBackendClient
import tv.mars.app.entitlement.DeviceIdentity
import tv.mars.app.entitlement.EncryptedEntitlementStore
import tv.mars.app.entitlement.StoredEntitlement
import tv.mars.app.updates.UpdateManifestVerifier
import tv.mars.app.updates.DirectUpdateManager
import tv.mars.app.updates.UpdateManifest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Run on API 25: JVM tests cannot detect missing Android runtime APIs. */
class LegacyAndroidCompatibilityTest {
    private val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("compat_test_$name", mode)
        override fun getCacheDir() = java.io.File(super.getCacheDir(), "compat_test").apply { mkdirs() }
    }

    @Test fun signedManifestVerifiesWithDesugaredBase64AndTime() {
        fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
            .open("updates/$name").bufferedReader().use { it.readText() }
        val verifier = UpdateManifestVerifier(asset("python-public-key.txt"), "release-2026-01", "ab".repeat(32))
        val release = verifier.verify(asset("python-signed.jws"), Instant.parse("2026-09-11T00:00:00Z"))
        assertEquals(3L, release.versionCode)
        // Exercise actual notification dispatch on pre-channel Android versions.
        val manager = DirectUpdateManager(context)
        DirectUpdateManager::class.java.getDeclaredMethod("notifyRelease", UpdateManifest::class.java).apply {
            isAccessible = true
        }.invoke(manager, release)
        assertEquals("20260910000000 +0000", DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
            .withZone(ZoneOffset.UTC).format(Instant.parse(release.publishedAt)))
        assertEquals("marstv.online", MarsBackendClient("https://marstv.online").request("/api/v1/releases/direct-stable").build().url.host)
    }

    @Test fun deviceIdentityAndEncryptedEntitlementSurviveReload() {
        // Isolated preferences keep the user's activation data untouched.
        val identity = DeviceIdentity(context)
        assertEquals(identity.deviceUuid(), DeviceIdentity(context).deviceUuid())
        assertArrayEquals(identity.publicKeySpki(), Base64.getDecoder().decode(identity.publicKeySpkiBase64()))
        val value = StoredEntitlement("compatibility-test", "api25-test", 1)
        context.getSharedPreferences("mars_entitlement_v1", 0).edit().clear().commit()
        try {
            EncryptedEntitlementStore(context).save(value)
            assertEquals(value, EncryptedEntitlementStore(context).load())
        } finally {
            context.getSharedPreferences("mars_entitlement_v1", 0).edit().clear().commit()
        }
    }
}
