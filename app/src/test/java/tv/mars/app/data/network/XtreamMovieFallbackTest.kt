package tv.mars.app.data.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamMovieFallbackTest {
    private fun movie(id: String) = JsonObject(mapOf("stream_id" to JsonPrimitive(id)))

    @Test fun `unavailable bulk endpoint retries categories`() = runBlocking {
        val count = streamMoviesWithCategoryFallback(listOf("1"), { category, emit ->
            if (category == null) throw java.io.IOException("Source returned HTTP 512")
            emit(movie("42"))
        }) { _, _ -> }
        assertEquals(1, count)
    }

    @Test fun `empty bulk response retries categories and deduplicates movies`() = runBlocking {
        val requests = mutableListOf<String?>()
        val received = mutableListOf<String?>()
        val count = streamMoviesWithCategoryFallback(listOf("1", "2", "2"), { category, emit ->
            requests += category
            if (category != null) emit(movie("42"))
            if (category == "2") emit(movie("43"))
        }) { _, category -> received += category }
        assertEquals(listOf(null, "1", "2"), requests)
        assertEquals(listOf("1", "2"), received)
        assertEquals(2, count)
    }

    @Test fun `nonempty bulk response does not request categories`() = runBlocking {
        val requests = mutableListOf<String?>()
        val count = streamMoviesWithCategoryFallback(listOf("1"), { category, emit ->
            requests += category
            emit(movie("42"))
        }) { _, _ -> }
        assertEquals(listOf<String?>(null), requests)
        assertEquals(1, count)
    }

    @Test fun `failed category propagates rather than committing partial catalog`() = runBlocking {
        var failed = false
        try {
            streamMoviesWithCategoryFallback(listOf("1", "2"), { category, emit ->
                if (category == "1") emit(movie("42"))
                if (category == "2") throw java.io.IOException("Unavailable")
            }) { _, _ -> }
        } catch (_: java.io.IOException) { failed = true }
        org.junit.Assert.assertTrue(failed)
    }
}
