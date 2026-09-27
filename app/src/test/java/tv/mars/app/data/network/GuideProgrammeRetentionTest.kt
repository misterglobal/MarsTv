package tv.mars.app.data.network

import org.junit.Assert.*
import org.junit.Test
import tv.mars.app.core.Programme

class GuideProgrammeRetentionTest {
    @Test fun `old listings cannot fill the budget before current and upcoming shows`() {
        val feed = (-200..100).map { programme(it * 100L, (it + 1) * 100L) }
        val forward = select(feed)
        assertEquals(forward, select(feed.reversed()))
        assertEquals(8, forward.size)
        assertTrue(forward.any { it.startMs == 0L })
        assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L), forward.filter { it.endMs > 50 }.map { it.startMs })
        assertEquals(listOf(-200L, -100L), forward.filter { it.endMs <= 50 }.map { it.startMs })
    }

    @Test fun `unused history budget goes to upcoming programmes`() {
        val result = select((0..20).map { programme(it * 100L, (it + 1) * 100L) })
        assertEquals(8, result.size)
        assertEquals(700L, result.last().startMs)
    }

    @Test fun `past-only feed retains most recent catch-up`() {
        val result = select((-100..-1).map { programme(it * 100L, (it + 1) * 100L) })
        assertEquals(8, result.size)
        assertEquals(-800L, result.first().startMs)
        assertEquals(0L, result.last().endMs)
    }

    private fun select(feed: List<Programme>): List<Programme> = GuideProgrammeRetention(8, 50).apply {
        feed.forEach(::add)
    }.programmes()
    private fun programme(start: Long, end: Long) = Programme("channel", "$start", "", start, end)
}
