package tv.mars.app.data.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomCatalogStoreTest {
    @Test
    fun `database batches never exceed import limit and preserve order`() = runBlocking {
        val input = (0 until 1_237).asSequence()
        val batches = mutableListOf<List<Int>>()

        input.forEachDatabaseBatch { batches += it.toList() }

        assertEquals(listOf(500, 500, 237), batches.map(List<Int>::size))
        assertTrue(batches.all { it.size <= RoomCatalogStore.IMPORT_BATCH_SIZE })
        assertEquals((0 until 1_237).toList(), batches.flatten())
    }

    @Test
    fun `empty input performs no transaction`() = runBlocking {
        var calls = 0

        emptySequence<Int>().forEachDatabaseBatch { calls++ }

        assertEquals(0, calls)
    }
}
