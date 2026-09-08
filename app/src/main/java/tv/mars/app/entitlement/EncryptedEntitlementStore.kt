package tv.mars.app.entitlement

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class StoredEntitlement(
    val compactJws: String,
    val licenseId: String,
    val licenseVersion: Long,
    val revocationFloor: Long = 0,
)

interface EntitlementStore {
    fun load(): StoredEntitlement?
    fun save(value: StoredEntitlement)
    fun clearTokenKeepingFloor(licenseId: String, revocationFloor: Long)
}

class EncryptedEntitlementStore(context: Context) : EntitlementStore {
    private val preferences = context.getSharedPreferences("mars_entitlement_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false }

    override fun load(): StoredEntitlement? = preferences.getString(BLOB, null)?.let { encoded ->
        runCatching { json.decodeFromString<StoredEntitlement>(decrypt(encoded)) }.getOrNull()
    }

    override fun save(value: StoredEntitlement) {
        val current = load()
        require(current == null || current.licenseId != value.licenseId || value.licenseVersion >= current.licenseVersion) {
            "Refusing entitlement version rollback"
        }
        val retainedFloor = if (current?.licenseId == value.licenseId) current.revocationFloor else 0
        require(retainedFloor == 0L || value.licenseVersion > retainedFloor) { "Entitlement is below the revocation floor" }
        check(
            preferences.edit().putString(
                BLOB,
                encrypt(json.encodeToString(value.copy(revocationFloor = maxOf(value.revocationFloor, retainedFloor)))),
            ).commit(),
        )
    }

    override fun clearTokenKeepingFloor(licenseId: String, revocationFloor: Long) {
        val marker = StoredEntitlement("", licenseId, revocationFloor, revocationFloor)
        check(preferences.edit().putString(BLOB, encrypt(json.encodeToString(marker))).commit())
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.getEncoder().encodeToString(cipher.iv) + "." +
            Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val BLOB = "encrypted_entitlement"
        const val KEY_ALIAS = "marstv_entitlement_cache_v1"
    }
}
