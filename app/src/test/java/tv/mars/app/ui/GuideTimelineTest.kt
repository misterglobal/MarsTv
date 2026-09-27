package tv.mars.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.mars.app.core.Programme
import tv.mars.app.ui.screens.guideSlots

class GuideTimelineTest {
    @Test fun `timeline clips edges and keeps missing programme time in place`() {
        val slots = guideSlots(listOf(programme(80, 150), programme(-10, 30)), 0, 100)
        assertEquals(listOf(0L, 30L, 80L), slots.map { it.startMs })
        assertEquals(listOf(30L, 80L, 100L), slots.map { it.endMs })
        assertNull(slots[1].programme)
    }

    @Test fun `overlapping invalid and offscreen shows cannot shift the timeline`() {
        val slots = guideSlots(listOf(programme(-50, -10), programme(0, 60), programme(40, 90), programme(80, 70), programme(150, 200)), 0, 100)
        assertEquals(listOf(0L, 60L, 90L), slots.map { it.startMs })
        assertEquals(100L, slots.sumOf { it.endMs - it.startMs })
    }

    @Test fun `empty guide still offers a full width watch action`() {
        val slot = guideSlots(emptyList(), 100, 200).single()
        assertNull(slot.programme)
        assertEquals(100L, slot.endMs - slot.startMs)
    }

    private fun programme(start: Long, end: Long) = Programme("channel", "show", "", start, end)
}
