package tv.mars.app.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import tv.mars.app.core.IptvAccount
import tv.mars.app.data.local.RoomCatalogStore
import tv.mars.app.data.network.XtreamCatalogStats
import tv.mars.app.data.network.XtreamClient
import java.io.IOException

class XtreamRoomImporter(
    private val client: XtreamClient,
    private val store: RoomCatalogStore,
) {
    suspend fun import(
        account: IptvAccount,
        publishEarly: Boolean = false,
        onFirstContentBatchCommitted: suspend () -> Unit = {},
    ): XtreamCatalogStats {
        repeat(IMPORT_ATTEMPTS) { attempt ->
            val session = store.beginImport(account.id, publishEarly = publishEarly)
            var contentPublished = false
            try {
                val stats = client.streamCatalog(account) { batch ->
                    session.write(batch)
                    if (publishEarly && !contentPublished && (batch.channels.isNotEmpty() || batch.media.isNotEmpty())) {
                        contentPublished = true
                        onFirstContentBatchCommitted()
                    }
                }
                session.commit()
                return stats
            } catch (error: Throwable) {
                withContext(NonCancellable) { session.discard() }
                currentCoroutineContext().ensureActive()
                if (error !is IOException || attempt == IMPORT_ATTEMPTS - 1) throw error
            }
        }
        error("Xtream import attempts exhausted")
    }

    private companion object {
        const val IMPORT_ATTEMPTS = 2
    }
}
