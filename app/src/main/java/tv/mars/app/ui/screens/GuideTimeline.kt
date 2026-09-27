package tv.mars.app.ui.screens

import tv.mars.app.core.Programme

internal data class GuideSlot(val startMs: Long, val endMs: Long, val programme: Programme?)

/** Clip programmes to one shared time scale and retain gaps rather than shifting later shows left. */
internal fun guideSlots(programmes: List<Programme>, startMs: Long, endMs: Long): List<GuideSlot> {
    require(endMs > startMs)
    val slots = mutableListOf<GuideSlot>()
    var cursor = startMs
    for (programme in programmes.sortedBy { it.startMs }) {
        val start = maxOf(cursor, programme.startMs)
        val end = minOf(endMs, programme.endMs)
        if (end <= start) continue
        if (start > cursor) slots += GuideSlot(cursor, start, null)
        slots += GuideSlot(start, end, programme)
        cursor = end
    }
    if (cursor < endMs) slots += GuideSlot(cursor, endMs, null)
    return slots
}
