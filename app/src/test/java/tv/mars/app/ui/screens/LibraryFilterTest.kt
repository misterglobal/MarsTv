package tv.mars.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.core.ContentKind

class LibraryFilterTest {
    @Test fun allIncludesEveryContentKind() {
        ContentKind.entries.forEach { assertTrue(matchesLibraryFilter(it, 0)) }
    }

    @Test fun filtersSeparateChannelsMoviesAndSeriesIncludingEpisodes() {
        ContentKind.entries.forEach { kind ->
            assertTrue(matchesLibraryFilter(kind, when (kind) {
                ContentKind.LIVE -> 1
                ContentKind.MOVIE -> 2
                else -> 3
            }))
        }
        assertFalse(matchesLibraryFilter(ContentKind.MOVIE, 1))
        assertFalse(matchesLibraryFilter(ContentKind.LIVE, 2))
        assertFalse(matchesLibraryFilter(ContentKind.EPISODE, 2))
        assertFalse(matchesLibraryFilter(ContentKind.MOVIE, 3))
    }
}
