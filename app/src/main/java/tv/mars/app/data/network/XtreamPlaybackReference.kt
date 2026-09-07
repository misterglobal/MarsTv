package tv.mars.app.data.network

import tv.mars.app.core.IptvAccount
import java.net.URLEncoder
import java.util.Base64

private const val REFERENCE_PREFIX = "marstv-xtream:"
private val ALLOWED_SECTIONS = setOf("live", "movie", "series")

internal fun xtreamPlaybackReference(section: String, id: String, extension: String): String {
    require(section in ALLOWED_SECTIONS) { "Unsupported Xtream playback section" }
    return listOf(section, id, extension.ifBlank { "ts" }).joinToString(":", prefix = REFERENCE_PREFIX) {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }
}

internal fun resolveXtreamPlaybackReference(account: IptvAccount, value: String): String {
    if (!value.startsWith(REFERENCE_PREFIX)) return value
    val parts = value.removePrefix(REFERENCE_PREFIX).split(':')
    if (parts.size != 3) return ""
    return runCatching {
        val decoder = Base64.getUrlDecoder()
        val section = String(decoder.decode(parts[0]), Charsets.UTF_8)
        val id = String(decoder.decode(parts[1]), Charsets.UTF_8)
        val extension = String(decoder.decode(parts[2]), Charsets.UTF_8)
        require(section in ALLOWED_SECTIONS && id.isNotBlank())
        "${account.serverUrl.trimEnd('/')}/$section/${account.username.pathSegment()}/" +
            "${account.password.pathSegment()}/${id.pathSegment()}.${extension.pathSegment()}"
    }.getOrDefault("")
}

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
