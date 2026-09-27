package tv.mars.app.data.network

import java.util.PriorityQueue
import tv.mars.app.core.Programme

/** Bounded retention independent of feed order: historical listings cannot evict upcoming TV. */
internal class GuideProgrammeRetention(private val limit: Int, private val now: Long) {
    private val upcoming = PriorityQueue<Programme>(compareByDescending { it.startMs })
    private val past = PriorityQueue<Programme>(compareBy { it.endMs })

    fun add(programme: Programme) {
        val queue = if (programme.endMs > now) upcoming else past
        queue.add(programme)
        // Share unused capacity, but retain at most limit objects across both pools.
        if (upcoming.size + past.size > limit) {
            if (upcoming.size > limit - limit / 4) upcoming.poll() else past.poll()
        }
    }

    fun programmes(): List<Programme> {
        val future = upcoming.sortedBy { it.startMs }
        val history = past.sortedByDescending { it.endMs }
        val futureCount = minOf(future.size, maxOf(limit - limit / 4, limit - history.size))
        return (future.take(futureCount) + history.take(limit - futureCount)).sortedBy { it.startMs }
    }
}
