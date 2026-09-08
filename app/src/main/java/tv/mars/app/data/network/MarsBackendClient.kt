package tv.mars.app.data.network

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Duration

class MarsBackendClient(baseUrl: String) {
    val baseUrl: HttpUrl = baseUrl.toHttpUrl().also {
        require(it.isHttps) { "MarsTV backend must use HTTPS" }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(45))
        .writeTimeout(Duration.ofSeconds(15))
        // The production shared host advertises HTTP/2 but can close its upgraded
        // response stream before OkHttp receives the JSON body.
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun request(path: String): Request.Builder {
        require(path.startsWith('/') && !path.contains("://")) { "Backend path must be canonical" }
        val url = baseUrl.newBuilder().encodedPath(path).query(null).build()
        require(url.isHttps && url.host == baseUrl.host) { "Backend request escaped configured origin" }
        return Request.Builder().url(url)
    }
}
