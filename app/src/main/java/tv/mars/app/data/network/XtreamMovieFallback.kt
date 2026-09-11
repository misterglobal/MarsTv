package tv.mars.app.data.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Some providers return an empty bulk VOD list but serve category-specific lists. */
internal suspend fun streamMoviesWithCategoryFallback(
    categoryIds: List<String>,
    fetch: suspend (String?, suspend (JsonObject) -> Unit) -> Unit,
    consume: suspend (JsonObject, String?) -> Unit,
): Int {
    val seen = HashSet<String>()
    suspend fun read(category: String?) {
        currentCoroutineContext().ensureActive()
        fetch(category) { item ->
            val id = (item["stream_id"] as? JsonPrimitive)?.content.orEmpty()
            if (id.isNotBlank() && id != "null" && seen.add(id)) consume(item, category)
        }
    }
    try {
        read(null)
    } catch (error: java.io.IOException) {
        // Retry an unavailable bulk endpoint only before any movies were emitted.
        if (seen.isNotEmpty() || categoryIds.none(String::isNotBlank)) throw error
    }
    if (seen.isEmpty()) categoryIds.filter(String::isNotBlank).distinct().forEach { read(it) }
    return seen.size
}
