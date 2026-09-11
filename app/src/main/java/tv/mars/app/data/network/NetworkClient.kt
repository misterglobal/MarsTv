package tv.mars.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class NetworkPayload(
    val bytes: ByteArray,
    val contentType: String,
    val contentEncoding: String,
)

class NetworkClient {
    companion object {
        const val USER_AGENT = "TiviMate/4.7.0 (Linux; Android 11)"
        private const val STREAM_ATTEMPTS = 2
    }

    private val trustAllCerts = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val sslContext = SSLContext.getInstance("SSL").apply {
        init(null, arrayOf<TrustManager>(trustAllCerts), SecureRandom())
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts)
        .hostnameVerifier { hostname: String?, session: javax.net.ssl.SSLSession? -> true }
        .build()

    suspend fun getText(url: String): String = get(url).bytes.toString(Charsets.UTF_8)

    suspend fun getStream(
        url: String,
        retryOnFailure: Boolean = true,
        block: suspend (java.io.InputStream) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val attempts = if (retryOnFailure) STREAM_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            try {
                val request = streamRequest(url)
                client.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) throw SourceHttpException(response.code, responseError(response.code))
                    block(response.body.byteStream())
                }
                return@withContext
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (error is SourceHttpException || attempt == attempts - 1) throw error
            }
        }
    }

    suspend fun get(url: String): NetworkPayload = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) {
                throw SourceHttpException(response.code, responseError(response.code))
            }
            NetworkPayload(
                bytes = response.body.bytes(),
                contentType = response.header("Content-Type").orEmpty(),
                contentEncoding = response.header("Content-Encoding").orEmpty(),
            )
        }
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ -> cancelledResponse.close() }
            }
        })
    }

    private fun streamRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "*/*")
        .header("Connection", "keep-alive")
        .header("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun responseError(code: Int): String = when (code) {
        884 -> "Account restriction: The provider has blocked M3U access or connection limits reached (Error 884)."
        451 -> "Unavailable for legal reasons: Access to this source is blocked in your region or by your ISP."
        403 -> "Access forbidden: Check your credentials or if your IP is whitelisted."
        else -> "Source returned HTTP $code"
    }


}

internal class SourceHttpException(val statusCode: Int, message: String) : IOException(message)
