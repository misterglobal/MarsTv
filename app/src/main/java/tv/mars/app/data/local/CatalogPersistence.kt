package tv.mars.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import tv.mars.app.core.CatalogBundle
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class CatalogPersistence(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(context.cacheDir, "catalogs")

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun save(catalog: CatalogBundle) {
        withContext(Dispatchers.IO) {
            val file = File(cacheDir, "${catalog.accountId}.json.gz")
            val tempFile = File(cacheDir, "${catalog.accountId}.json.gz.tmp")
            
            val success = runCatching {
                tempFile.outputStream().use { fos ->
                    GZIPOutputStream(fos).use { gzip ->
                        json.encodeToStream(catalog, gzip)
                    }
                }
                tempFile.renameTo(file)
            }.getOrDefault(false)

            if (!success && tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun load(accountId: String): CatalogBundle? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$accountId.json.gz")
        if (!file.exists()) return@withContext null
        
        runCatching {
            file.inputStream().use { fis ->
                GZIPInputStream(fis).use { gzip ->
                    json.decodeFromStream<CatalogBundle>(gzip)
                }
            }
        }.getOrNull()
    }

    suspend fun clear(accountId: String) {
        withContext(Dispatchers.IO) {
            File(cacheDir, "$accountId.json.gz").delete()
            File(cacheDir, "$accountId.episodes.json.gz").delete()
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveEpisodes(accountId: String, episodes: Map<String, List<tv.mars.app.core.Episode>>) {
        withContext(Dispatchers.IO) {
            val file = File(cacheDir, "$accountId.episodes.json.gz")
            val tempFile = File(cacheDir, "$accountId.episodes.json.gz.tmp")
            
            runCatching {
                tempFile.outputStream().use { fos ->
                    GZIPOutputStream(fos).use { gzip ->
                        json.encodeToStream(episodes, gzip)
                    }
                }
                tempFile.renameTo(file)
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadEpisodes(accountId: String): Map<String, List<tv.mars.app.core.Episode>>? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$accountId.episodes.json.gz")
        if (!file.exists()) return@withContext null
        
        runCatching {
            file.inputStream().use { fis ->
                GZIPInputStream(fis).use { gzip ->
                    json.decodeFromStream<Map<String, List<tv.mars.app.core.Episode>>>(gzip)
                }
            }
        }.getOrNull()
    }
}
