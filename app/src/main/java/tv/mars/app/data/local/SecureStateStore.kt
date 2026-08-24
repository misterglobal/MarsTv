package tv.mars.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.LocalState
import tv.mars.app.core.ViewerProfile
import tv.mars.app.core.WatchRecord
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.marsDataStore by preferencesDataStore(name = "mars_secure_state")

class SecureStateStore(private val context: Context) {
    private val blobKey = stringPreferencesKey("encrypted_state_v1")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val cipher = CredentialCipher()

    val state: Flow<LocalState> = context.marsDataStore.data.map { preferences ->
        decode(preferences[blobKey]).withDefaultProfile()
    }

    suspend fun addAccount(account: IptvAccount) = mutate { current ->
        current.copy(
            accounts = current.accounts.filterNot { it.id == account.id } + account,
            activeAccountId = account.id,
        )
    }

    suspend fun removeAccount(accountId: String) = mutate { current ->
        val remaining = current.accounts.filterNot { it.id == accountId }
        current.copy(
            accounts = remaining,
            activeAccountId = if (current.activeAccountId == accountId) remaining.firstOrNull()?.id else current.activeAccountId,
        )
    }

    suspend fun setActiveAccount(accountId: String) = mutate { current ->
        if (current.accounts.none { it.id == accountId }) current else current.copy(activeAccountId = accountId)
    }

    suspend fun addProfile(name: String) = mutate { current ->
        val profile = ViewerProfile(name = name.trim().ifBlank { "Profile" }, avatarIndex = current.profiles.size % 6)
        current.copy(profiles = current.profiles + profile, activeProfileId = profile.id)
    }

    suspend fun updateProfile(profile: ViewerProfile) = mutate { current ->
        current.copy(profiles = current.profiles.map { if (it.id == profile.id) profile else it })
    }

    suspend fun removeProfile(profileId: String) = mutate { current ->
        if (current.profiles.size <= 1) return@mutate current
        val remaining = current.profiles.filterNot { it.id == profileId }
        current.copy(
            profiles = remaining,
            activeProfileId = if (current.activeProfileId == profileId) remaining.first().id else current.activeProfileId,
            favouriteKeysByProfile = current.favouriteKeysByProfile - profileId,
            watchHistoryByProfile = current.watchHistoryByProfile - profileId,
        )
    }

    suspend fun setActiveProfile(profileId: String) = mutate { current ->
        if (current.profiles.none { it.id == profileId }) current else current.copy(activeProfileId = profileId)
    }

    suspend fun toggleFavourite(profileId: String, contentKey: String) = mutate { current ->
        val existing = current.favouriteKeysByProfile[profileId].orEmpty()
        val next = if (contentKey in existing) existing - contentKey else existing + contentKey
        current.copy(favouriteKeysByProfile = current.favouriteKeysByProfile + (profileId to next))
    }

    suspend fun recordWatch(profileId: String, record: WatchRecord) = mutate { current ->
        val history = current.watchHistoryByProfile[profileId].orEmpty()
        val next = listOf(record) + history.filterNot { it.contentKey == record.contentKey }
        current.copy(watchHistoryByProfile = current.watchHistoryByProfile + (profileId to next.take(100)))
    }

    suspend fun clearHistory(profileId: String) = mutate { current ->
        current.copy(watchHistoryByProfile = current.watchHistoryByProfile - profileId)
    }

    private suspend fun mutate(transform: (LocalState) -> LocalState) {
        context.marsDataStore.edit { preferences ->
            val current = decode(preferences[blobKey]).withDefaultProfile()
            preferences[blobKey] = cipher.encrypt(json.encodeToString(transform(current)))
        }
    }

    private fun decode(blob: String?): LocalState {
        if (blob.isNullOrBlank()) return LocalState()
        return runCatching { json.decodeFromString<LocalState>(cipher.decrypt(blob)) }
            .getOrElse { LocalState() }
    }

    private fun LocalState.withDefaultProfile(): LocalState {
        if (profiles.isNotEmpty()) {
            return if (activeProfileId == null) copy(activeProfileId = profiles.first().id) else this
        }
        val main = ViewerProfile(id = UUID.randomUUID().toString(), name = "Main")
        return copy(profiles = listOf(main), activeProfileId = main.id)
    }

    companion object {
        fun hashPin(pin: String): String = MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

private class CredentialCipher {
    private val alias = "mars_tv_state_key_v1"

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return listOf(cipher.iv, encrypted).joinToString(".") {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
    }

    fun decrypt(encoded: String): String {
        val parts = encoded.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted state" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val payload = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }
}
