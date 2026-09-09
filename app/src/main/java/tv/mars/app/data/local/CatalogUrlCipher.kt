package tv.mars.app.data.local

private const val ENCRYPTED_CATALOG_VALUE_PREFIX = "marstv-encrypted:v1:"
private const val XTREAM_REFERENCE_PREFIX = "marstv-xtream:"

internal object CatalogUrlCipher {
    private val cipher = CredentialCipher("mars_tv_catalog_url_key_v1")

    fun seal(value: String): String = when {
        value.isBlank() || value.startsWith(XTREAM_REFERENCE_PREFIX) -> value
        value.startsWith(ENCRYPTED_CATALOG_VALUE_PREFIX) -> value
        else -> ENCRYPTED_CATALOG_VALUE_PREFIX + cipher.encrypt(value)
    }

    fun open(value: String): String {
        if (!value.startsWith(ENCRYPTED_CATALOG_VALUE_PREFIX)) return value
        return cipher.decrypt(value.removePrefix(ENCRYPTED_CATALOG_VALUE_PREFIX))
    }
}
