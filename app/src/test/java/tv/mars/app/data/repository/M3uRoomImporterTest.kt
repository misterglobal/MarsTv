package tv.mars.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.IptvAccount
import tv.mars.app.core.SourceType
import tv.mars.app.data.network.M3uBatch
import tv.mars.app.data.network.M3uParser

class M3uRoomImporterTest {
    private val account = IptvAccount(
        id = "account",
        name = "Test",
        sourceType = SourceType.M3U,
        m3uUrl = "https://example.com/list.m3u",
    )

    @Test
    fun `successful import commits staged generation`() = runBlocking {
        val session = RecordingSession()
        val importer = M3uRoomImporter(M3uParser(), session)

        importer.import(account, validPlaylist().byteInputStream())

        assertTrue(session.batches.isNotEmpty())
        assertTrue(session.committed)
        assertFalse(session.discarded)
    }

    @Test
    fun `parse failure discards staging without commit`() = runBlocking {
        val session = RecordingSession()
        val importer = M3uRoomImporter(M3uParser(), session)

        runCatching { importer.import(account, "not an m3u".byteInputStream()) }

        assertFalse(session.committed)
        assertTrue(session.discarded)
    }

    @Test
    fun `write cancellation discards staging without commit`() = runBlocking {
        val session = RecordingSession(cancelOnWrite = true)
        val importer = M3uRoomImporter(M3uParser(), session)

        try {
            importer.import(account, validPlaylist().byteInputStream())
            throw AssertionError("Expected import cancellation")
        } catch (_: CancellationException) {
            assertFalse(session.committed)
            assertTrue(session.discarded)
        }
    }

    private fun validPlaylist() = buildString {
        appendLine("#EXTM3U")
        repeat(500) { index ->
            appendLine("#EXTINF:-1 group-title=\"Live\",Channel $index")
            appendLine("https://example.com/live/$index.ts")
        }
    }

    private class RecordingSession(private val cancelOnWrite: Boolean = false) : StagedM3uImport {
        val batches = mutableListOf<M3uBatch>()
        var committed = false
        var discarded = false

        override suspend fun write(batch: M3uBatch) {
            if (cancelOnWrite) throw CancellationException("cancel write")
            batches += batch
        }

        override suspend fun commit() {
            committed = true
        }

        override suspend fun discard() {
            discarded = true
        }
    }
}
