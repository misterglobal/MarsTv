package tv.mars.app.entitlement

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class DeviceIdentity(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun deviceUuid(): String = ensureIdentity().first

    fun publicKeySpki(): ByteArray = ensureIdentity().second

    fun publicKeySpkiBase64(): String = Base64.getEncoder().encodeToString(publicKeySpki())

    fun publicKeyThumbprint(): String = base64Url(sha256(publicKeySpki()))

    fun installationFingerprint(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return sha256Hex("MARSTV_INSTALLATION_V1\n$androidId\n${context.packageName}\n${signingCertificateHash()}")
    }

    fun platform(): String = when {
        Build.MANUFACTURER.equals("Amazon", ignoreCase = true) -> "fire_tv"
        context.packageManager.hasSystemFeature("android.software.leanback") -> "android_tv"
        else -> "android"
    }

    fun signChallenge(challenge: DeviceChallenge): String {
        require(challenge.deviceUuid == deviceUuid()) { "Challenge device does not match this installation" }
        val privateKey = keyStore().getKey(KEY_ALIAS, null)
            ?: generateKeyPair().private
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(challenge.signingBytes())
        return base64Url(signature.sign())
    }

    @Synchronized
    private fun ensureIdentity(): Pair<String, ByteArray> {
        val certificate = keyStore().getCertificate(KEY_ALIAS)
        val storedUuid = preferences.getString(DEVICE_UUID, null)
        if (certificate != null && storedUuid != null) return storedUuid to certificate.publicKey.encoded

        // A restored preference without its non-backup Keystore key is a new installation.
        val keyPair = if (certificate == null) generateKeyPair() else null
        val uuid = UUID.randomUUID().toString()
        check(preferences.edit().putString(DEVICE_UUID, uuid).commit()) { "Could not persist device identity" }
        return uuid to (keyPair?.public?.encoded ?: certificate!!.publicKey.encoded)
    }

    @Suppress("DEPRECATION")
    private fun signingCertificateHash(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        }
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: byteArrayOf()
        return sha256Hex(DeviceIdentity.base64Url(certificate))
    }

    private fun generateKeyPair() = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
        initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generateKeyPair()
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        const val KEY_ALIAS = "marstv_device_auth_v1"
        private const val PREFERENCES = "mars_device_identity_v1"
        private const val DEVICE_UUID = "device_uuid"

        internal fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
        internal fun sha256Hex(value: String): String = sha256(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        internal fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}

data class DeviceChallenge(
    val challengeId: String,
    val nonceBase64Url: String,
    val deviceUuid: String,
    val httpMethod: String,
    val canonicalPath: String,
    val bodySha256Base64Url: String,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(httpMethod == httpMethod.uppercase()) { "HTTP method must be uppercase" }
        require(canonicalPath.startsWith('/') && !canonicalPath.contains("://")) { "Invalid canonical path" }
    }

    fun signingBytes(): ByteArray = listOf(
        "MARSTV_DEVICE_AUTH_V1",
        challengeId,
        nonceBase64Url,
        deviceUuid,
        httpMethod,
        canonicalPath,
        bodySha256Base64Url,
        expiresAtEpochSeconds.toString(),
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
}
