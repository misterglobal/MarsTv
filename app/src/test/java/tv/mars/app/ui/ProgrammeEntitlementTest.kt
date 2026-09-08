package tv.mars.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.mars.app.core.Programme

class ProgrammeEntitlementTest {
    @Test
    fun `free guide returns current and next by time`() {
        val programmes = listOf(
            programme("future-two", 300, 400),
            programme("past", 0, 100),
            programme("current", 100, 200),
            programme("future-one", 200, 300),
        )

        assertEquals(
            listOf("current", "future-one"),
            currentAndNextProgrammes(programmes, nowMs = 150).map(Programme::title),
        )
    }

    @Test
    fun `free guide returns next when current programme is unavailable`() {
        val programmes = listOf(programme("later", 300, 400), programme("next", 200, 300))

        assertEquals(listOf("next"), currentAndNextProgrammes(programmes, nowMs = 150).map(Programme::title))
    }

    private fun programme(title: String, start: Long, end: Long) = Programme(
        channelEpgId = "channel",
        title = title,
        description = "",
        startMs = start,
        endMs = end,
    )
}
