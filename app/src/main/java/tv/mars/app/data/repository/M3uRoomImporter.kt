package tv.mars.app.data.repository

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tv.mars.app.core.IptvAccount
import tv.mars.app.data.local.RoomCatalogStore
import tv.mars.app.data.network.M3uBatch
import tv.mars.app.data.network.M3uParser
import tv.mars.app.data.network.M3uStreamResult
import java.io.InputStream

class M3uRoomImporter private constructor(
    private val parser: M3uParser,
    private val sessionFactory: (String, Boolean) -> StagedM3uImport,
) {
    constructor(parser: M3uParser, store: RoomCatalogStore) : this(
        parser = parser,
        sessionFactory = { accountId, publishEarly ->
            val session = store.beginImport(accountId, publishEarly = publishEarly)
            object : StagedM3uImport {
                override suspend fun write(batch: M3uBatch) = session.write(batch)
                override suspend fun commit() = session.commit()
                override suspend fun discard() = session.discard()
            }
        },
    )

    internal constructor(parser: M3uParser, session: StagedM3uImport) : this(parser, { _, _ -> session })

    suspend fun import(
        account: IptvAccount,
        inputStream: InputStream,
        publishEarly: Boolean = false,
        onFirstBatchCommitted: suspend () -> Unit = {},
    ): M3uStreamResult {
        val session = sessionFactory(account.id, publishEarly)
        var firstBatchCommitted = false
        return try {
            parser.parseStreaming(account, inputStream) {
                session.write(it)
                if (publishEarly && !firstBatchCommitted) {
                    firstBatchCommitted = true
                    onFirstBatchCommitted()
                }
            }
                .also { session.commit() }
        } catch (error: Throwable) {
            withContext(NonCancellable) { session.discard() }
            throw error
        }
    }

}

internal interface StagedM3uImport {
    suspend fun write(batch: M3uBatch)
    suspend fun commit()
    suspend fun discard()
}
