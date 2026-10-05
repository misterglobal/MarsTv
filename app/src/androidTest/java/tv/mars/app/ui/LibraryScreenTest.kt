package tv.mars.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.MainActivity
import tv.mars.app.core.CatalogLookup
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.MediaContent
import tv.mars.app.core.WatchRecord
import tv.mars.app.ui.components.RemoteNavigation
import tv.mars.app.ui.screens.LibraryScreen
import tv.mars.app.ui.theme.MarsTvTheme
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class LibraryScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun favouriteTitlesUseVerticalRowsWithSeparateRemoveActions() {
        val channels = listOf(channel("World News HD"), channel("Cinema Classics"))
        for (television in listOf(false, true)) {
            withLibrary(CatalogLookup(channels = channels), television = television) {
                awaitLabel("My TV")
                awaitControlLabel("Favourites")
                awaitControlLabel("Continue watching")
                awaitControlLabel("History")
                val filters = listOf("All", "Channels", "Movies", "Series").map { bounds(awaitControlLabel(it)) }
                filters.zipWithNext().forEach { (left, right) ->
                    assertTrue("Type filters must run horizontally", left.right <= right.left && left.centerY() == right.centerY())
                }
                val first = requireNotNull(clickable(awaitLabel(channels[0].name)))
                val second = requireNotNull(clickable(awaitLabel(channels[1].name)))
                val firstBounds = bounds(first)
                val secondBounds = bounds(second)
                assertTrue("Favourite titles must occupy separate vertical rows", firstBounds.bottom <= secondBounds.top)
                val remove = nodes().filter { hasLabel(it, "Remove favourite") && it.isVisibleToUser }
                    .mapNotNull(::clickable).first { bounds(it).centerY() in firstBounds.top..firstBounds.bottom }
                val removeBounds = bounds(remove)
                assertTrue("Removal must be separate from playback", firstBounds.right <= removeBounds.left)
                val rowBounds = Rect(firstBounds).apply { union(removeBounds) }
                assertTrue("Favourite rows must use the available width", rowBounds.width() >= bounds(root()).width() * 0.85f)
            }
        }
    }

    @Test fun largeFavouriteListScrollsVerticallyToLastTitleAndPlaysCorrectChannel() {
        val channels = List(60) { channel("Favourite channel ${it + 1}") }
        withLibrary(CatalogLookup(channels = channels)) { events ->
            awaitLabel(channels.first().name)
            assertAbsent(channels.last().name)
            clickNode(scrollToLabel(channels.last().name))
            awaitEvents(events, listOf("channel:${channels.last().key}"))
            assertAbsent(channels.first().name)
        }
    }

    @Test fun tvDpadScrollsLongFavouriteListAndOkPlaysOnlyFocusedChannel() {
        val channels = List(40) { channel("Remote channel ${it + 1}") }
        val target = channels[24]
        withLibrary(CatalogLookup(channels = channels), television = true, remoteNavigation = true) { events ->
            awaitLabel(channels.first().name)
            assertAbsent(target.name)
            saveScreenshot("library-tv-populated.png")
            focusLabel(channels.first().name)
            for (channel in channels.subList(1, 25)) {
                sendRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocusedLabel(channel.name)
            }

            awaitLabel(target.name)
            assertAbsent(channels.first().name)
            awaitEvents(events, emptyList())
            saveScreenshot("library-tv-scrolled.png")
            sendRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitEvents(events, listOf("channel:${target.key}"))
        }
    }

    @Test fun favouriteFiltersAndPlaybackAndRemovalCallbacksStayIndependent() {
        val channel = channel("Fixture news")
        val movie = media("Fixture movie", ContentKind.MOVIE)
        val series = media("Fixture series", ContentKind.SERIES)
        withLibrary(CatalogLookup(listOf(channel), listOf(movie, series))) { events ->
            clickLabel("Channels")
            clickLabel(channel.name)
            assertAbsent(movie.title)
            assertAbsent(series.title)
            clickLabel("Remove favourite: ${channel.name}")
            awaitEvents(events, listOf("channel:${channel.key}", "remove:${channel.key}"))

            clickLabel("Movies")
            clickLabel(movie.title)
            assertAbsent(channel.name)
            assertAbsent(series.title)
            clickLabel("Remove favourite: ${movie.title}")
            awaitEvents(events, listOf("channel:${channel.key}", "remove:${channel.key}", "media:${movie.key}", "remove:${movie.key}"))

            clickLabel("Series")
            clickLabel(series.title)
            assertAbsent(channel.name)
            assertAbsent(movie.title)
            clickLabel("Remove favourite: ${series.title}")
            awaitEvents(events, listOf("channel:${channel.key}", "remove:${channel.key}", "media:${movie.key}", "remove:${movie.key}", "media:${series.key}", "remove:${series.key}"))

            clickLabel("All")
            scrollToLabel(channel.name)
            scrollToLabel(movie.title)
            scrollToLabel(series.title)
        }
    }

    @Test fun lockedFavouritesRemainVisibleButCannotPlayOrBeRemoved() {
        val channel = channel("Locked news")
        val movie = media("Locked film", ContentKind.MOVIE)
        withLibrary(locked = CatalogLookup(listOf(channel), listOf(movie))) { events ->
            for ((filter, title) in listOf("Channels" to channel.name, "Movies" to movie.title)) {
                clickLabel(filter)
                val titleNode = awaitLabel(title)
                assertTrue("Locked favourite must have no clickable ancestor", clickable(titleNode) == null)
                assertFalse("Locked title must reject activation", titleNode.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertAbsent("Remove favourite")
            }
            instrumentation.waitForIdleSync()
            assertEquals(emptyList<String>(), events.toList())
        }
    }

    @Test fun continueWatchingAndHistoryFilterByKindAndDispatchExactRecords() {
        val history = listOf(
            record("Watched news", ContentKind.LIVE),
            record("Watched movie", ContentKind.MOVIE),
            record("Watched series", ContentKind.SERIES),
            record("Watched episode", ContentKind.EPISODE),
        )
        val continuing = listOf(record("Resume movie", ContentKind.MOVIE), record("Resume episode", ContentKind.EPISODE))
        withLibrary(continuing = continuing, history = history) { events ->
            clickLabel("Continue watching")
            assertAbsent("Clear history")
            clickLabel("Movies")
            clickLabel(continuing[0].title)
            assertAbsent(continuing[1].title)
            clickLabel("Series")
            clickLabel(continuing[1].title)
            assertAbsent(continuing[0].title)
            clickLabel("Channels")
            continuing.forEach { assertAbsent(it.title) }

            clickLabel("History")
            val expected = continuing.map { "history:${it.contentKey}:${it.positionMs}" }.toMutableList()
            for ((filter, kinds) in listOf(
                "Channels" to setOf(ContentKind.LIVE),
                "Movies" to setOf(ContentKind.MOVIE),
                "Series" to setOf(ContentKind.SERIES, ContentKind.EPISODE),
            )) {
                clickLabel(filter)
                history.filter { it.kind in kinds }.forEach {
                    clickNode(scrollToLabel(it.title))
                    expected += "history:${it.contentKey}:${it.positionMs}"
                }
                history.filter { it.kind !in kinds }.forEach { assertAbsent(it.title) }
                continuing.forEach { assertAbsent(it.title) }
                awaitEvents(events, expected)
            }
            clickLabel("All")
            history.forEach { scrollToLabel(it.title) }
            clickLabel("Clear history")
            awaitEvents(events, expected + "clear")
        }
    }

    @Test fun emptyLibraryTabsShowTheirOwnEmptyStates() {
        withLibrary {
            awaitLabel("No favourites in this view")
            clickLabel("Continue watching")
            awaitLabel("Nothing to continue in this view")
            assertAbsent("No favourites in this view")
            assertAbsent("Clear history")
            clickLabel("History")
            awaitLabel("No viewing history in this view")
            assertAbsent("Nothing to continue in this view")
            assertAbsent("Clear history")
        }
    }

    private fun withLibrary(
        favourites: CatalogLookup = CatalogLookup(),
        locked: CatalogLookup = CatalogLookup(),
        continuing: List<WatchRecord> = emptyList(),
        history: List<WatchRecord> = emptyList(),
        television: Boolean = false,
        remoteNavigation: Boolean = false,
        assertions: (ConcurrentLinkedQueue<String>) -> Unit,
    ) {
        val events = ConcurrentLinkedQueue<String>()
        val favouriteKeys = (favourites.channels.map { it.key } + favourites.media.map { it.key }).toSet()
        val lockedKeys = (locked.channels.map { it.key } + locked.media.map { it.key }).toSet()
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MarsTvTheme {
                        RemoteNavigation(remoteNavigation, "library", Modifier.fillMaxSize()) {
                            LibraryScreen(
                                accountId = "fixture", catalogRevision = 0,
                                favouriteKeys = favouriteKeys, lockedFavouriteKeys = lockedKeys,
                                blockedCategoryKeys = emptySet(),
                                favouritesSource = { keys, _ ->
                                    CatalogLookup(
                                        (favourites.channels + locked.channels).filter { it.key in keys },
                                        (favourites.media + locked.media).filter { it.key in keys },
                                    )
                                },
                                continueWatching = continuing, history = history, isTelevision = television,
                                onPlayChannel = { events.add("channel:${it.key}") },
                                onOpenMedia = { events.add("media:${it.key}") },
                                onPlayHistory = { events.add("history:${it.contentKey}:${it.positionMs}") },
                                onToggleFavourite = { events.add("remove:$it") },
                                onClearHistory = { events.add("clear") },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            awaitLabel("Favourites")
            assertions(events)
        }
    }

    private fun root(): AccessibilityNodeInfo = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            result += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }

    private fun hasLabel(node: AccessibilityNodeInfo, label: String): Boolean =
        listOfNotNull(node.text, node.contentDescription).any { text ->
            text.toString().lineSequence().any {
                it == label || (label == "Remove favourite" && it.startsWith("Remove favourite: "))
            }
        }

    private fun visibleLabel(label: String): AccessibilityNodeInfo? =
        nodes().firstOrNull { it.isVisibleToUser && hasLabel(it, label) && !bounds(it).isEmpty }

    private fun awaitLabel(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            visibleLabel(label)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Visible library label missing: $label")
    }

    private fun assertAbsent(label: String) {
        awaitCondition("Unexpected visible library label: $label") { visibleLabel(label) == null }
    }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable && candidate.isEnabled) return candidate
            candidate = candidate.parent
        }
        return null
    }

    private fun clickLabel(label: String) = clickNode(
        if (label in tabLabels || label in filterLabels) awaitControlLabel(label) else awaitLabel(label),
    )

    private val tabLabels = listOf("Favourites", "Continue watching", "History")
    private val filterLabels = listOf("All", "Channels", "Movies", "Series")

    private fun awaitControlLabel(label: String): AccessibilityNodeInfo {
        val labels = if (label in tabLabels) tabLabels else filterLabels
        fun control(): AccessibilityNodeInfo? = nodes().firstOrNull {
            it.isVisibleToUser && hasLabel(it, label) && clickable(it) != null && !bounds(it).isEmpty
        }
        instrumentation.waitForIdleSync()
        control()?.let { return it }
        val target = nodes().firstOrNull { hasLabel(it, label) && clickable(it) != null }
        if (target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true) {
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            control()?.let { return it }
        }
        fun containsControl(node: AccessibilityNodeInfo): Boolean = labels.any { hasLabel(node, it) } ||
            (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsControl) == true }
        for (action in listOf(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
        )) {
            var steps = 0
            while (steps++ < 8) {
                control()?.let { return it }
                val row = nodes().firstOrNull { action in it.actionList && containsControl(it) } ?: break
                if (!row.performAction(action.id)) break
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(100, 5_000)
            }
        }
        return requireNotNull(control()) { "Horizontal library control could not be revealed: $label" }
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        val label = requireNotNull(node.text ?: node.contentDescription).toString().lineSequence().first()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val fresh = nodes().firstOrNull { it.isVisibleToUser && hasLabel(it, label) && clickable(it) != null }
            if (fresh != null && clickable(fresh)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                awaitUiSettled()
                return
            }
            Thread.sleep(50)
        }
        throw AssertionError("Library control must accept activation: $label")
    }

    private fun scrollToLabel(label: String): AccessibilityNodeInfo {
        repeat(80) {
            instrumentation.waitForIdleSync()
            visibleLabel(label)?.let { return it }
            val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            val list = nodes().firstOrNull { it.isScrollable && action in it.actionList }
                ?: throw AssertionError("No vertical list can reveal $label")
            val before = visibleSnapshot()
            assertTrue("Library list must scroll towards $label", list.performAction(action.id))
            awaitCondition("Library scroll made no visible progress towards $label") {
                visibleSnapshot() != before
            }
            awaitUiSettled()
        }
        throw AssertionError("Library list never revealed $label")
    }

    private fun awaitEvents(events: ConcurrentLinkedQueue<String>, expected: List<String>) {
        awaitCondition("Callbacks did not settle to $expected; actual=${events.toList()}") { events.toList() == expected }
        assertEquals("Each action must dispatch only its own callback", expected, events.toList())
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var satisfiedSince: Long? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            val now = SystemClock.elapsedRealtime()
            if (condition()) {
                val since = satisfiedSince ?: now.also { satisfiedSince = it }
                if (now - since >= 250) return
            } else {
                satisfiedSince = null
            }
            Thread.sleep(50)
        }
        throw AssertionError(description)
    }

    private fun visibleSnapshot(): List<String> = nodes().filter { it.isVisibleToUser }.map {
        "${it.text}|${it.contentDescription}|${bounds(it)}|${it.isClickable}|${it.isEnabled}"
    }

    private fun awaitUiSettled() {
        var previous = visibleSnapshot()
        awaitCondition("Library accessibility layout did not settle") {
            val current = visibleSnapshot()
            val unchanged = current == previous
            previous = current
            unchanged
        }
    }

    private fun sendRemoteKey(keyCode: Int) {
        instrumentation.sendKeyDownUpSync(keyCode)
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
    }

    private fun focusedHasLabel(label: String): Boolean {
        val focused = instrumentation.uiAutomation.rootInActiveWindow
            ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        fun containsLabel(node: AccessibilityNodeInfo): Boolean = hasLabel(node, label) ||
            (0 until node.childCount).any { index -> node.getChild(index)?.let(::containsLabel) == true }
        return containsLabel(focused)
    }

    private fun focusLabel(label: String) {
        val node = requireNotNull(clickable(awaitLabel(label))) { "Library row is not focusable: $label" }
        assertTrue("Library row must accept input focus: $label", node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        awaitFocusedLabel(label)
    }

    private fun awaitFocusedLabel(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            if (focusedHasLabel(label)) return
            Thread.sleep(50)
        }
        throw AssertionError("D-pad focus did not reach library control: $label")
    }

    private fun saveScreenshot(name: String) {
        instrumentation.uiAutomation.waitForIdle(100, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            File(directory, name).outputStream().use { output ->
                assertTrue("Library screenshot must be saved", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun channel(title: String) = Channel(title, title, "fixture", title, "live", "Live", "", "", "")

    private fun media(title: String, kind: ContentKind) = MediaContent(title, title, "fixture", title, kind, "media", "Media")

    private fun record(title: String, kind: ContentKind) = WatchRecord(
        contentKey = title, accountId = "fixture", title = title, playbackUrl = "", kind = kind,
        positionMs = 60_000, durationMs = 600_000, watchedAt = 1_700_000_000_000,
    )
}
