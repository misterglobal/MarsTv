package tv.mars.app.ui

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.paging.PagingData
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.MainActivity
import tv.mars.app.core.Category
import tv.mars.app.core.Channel
import tv.mars.app.core.ContentKind
import tv.mars.app.core.PlayerRequest
import tv.mars.app.core.Programme
import tv.mars.app.ui.screens.LiveGuideScreen
import tv.mars.app.ui.theme.MarsTvTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic schedules keep layout/navigation checks independent of accounts and provider availability. */
class LiveGuideSmokeTest {
    @Test fun tvGuideShowsEightChannelsAndUpcomingDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val watched = AtomicInteger()
        val halfHour = 1_800_000L
        val start = System.currentTimeMillis() / halfHour * halfHour
        val channels = List(20) {
            Channel("channel-$it", "$it", "fixture", "Channel ${it + 1}", "news", "News", "", "epg-$it", "")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MarsTvTheme {
                        LiveGuideScreen(
                            accountId = "fixture",
                            categoriesSource = { flowOf(listOf(Category("news", "news", "News", ContentKind.LIVE))) },
                            channelsSource = { _, _ -> flowOf(PagingData.from(channels, sourceLoadStates = androidx.paging.LoadStates(androidx.paging.LoadState.NotLoading(false), androidx.paging.LoadState.NotLoading(true), androidx.paging.LoadState.NotLoading(true)))) },
                            programmesSource = { id, _, _ -> flowOf(List(4) {
                                Programme(id, if (it == 0) "Live news" else "Upcoming $it", "Programme description", start + it * halfHour, start + (it + 1) * halfHour)
                            }) },
                            blockedCategoryKeys = emptySet(), favouriteKeys = emptySet(), fullEpgEnabled = true,
                            profileHasPin = false, isCategoryLocked = { false }, pinMatches = { false }, onUnlockCategory = {},
                            onPlayChannel = { watched.incrementAndGet() }, onPlayProgramme = { _, _ -> watched.incrementAndGet() },
                            onToggleFavourite = {}, onOpenSettings = {}, isTelevision = true,
                            previewRequest = PlayerRequest("channel-0", "fixture", "Channel 1", "", ContentKind.LIVE),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun screenshot() {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(instrumentation.targetContext.filesDir, "live-guide-smoke.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            fun find(text: String): List<AccessibilityNodeInfo> {
                val matches = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) {
                    if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) matches += node
                    for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
                }
                val root = instrumentation.uiAutomation.rootInActiveWindow
                    ?: instrumentation.uiAutomation.windows.firstNotNullOfOrNull { it.root }
                root?.let(::visit)
                return matches
            }
            fun awaitText(text: String): List<AccessibilityNodeInfo> {
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    val nodes = find(text).filter { it.isVisibleToUser }
                    if (nodes.isNotEmpty()) return nodes
                    Thread.sleep(100)
                }
                screenshot()
                throw AssertionError("Visible text missing: $text; root=${instrumentation.uiAutomation.rootInActiveWindow}")
            }
            awaitText("Channel 8")
            fun focusedContains(text: String): Boolean {
                val focused = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
                fun contains(node: AccessibilityNodeInfo): Boolean =
                    node.text?.contains(text) == true || (0 until node.childCount).any { index ->
                        node.getChild(index)?.let(::contains) == true
                    }
                return contains(focused)
            }
            fun awaitFocus(text: String) {
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    if (focusedContains(text)) return
                    Thread.sleep(50)
                }
                throw AssertionError("Remote focus did not reach $text; focused=${instrumentation.uiAutomation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)}")
            }
            // First D-pad press switches this phone-style API 25 image out of touch mode.
            repeat(4) {
                if (!focusedContains("Channel 1")) {
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                    instrumentation.waitForIdleSync()
                    Thread.sleep(100)
                }
            }
            awaitFocus("Channel 1")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            awaitText("News")
            instrumentation.waitForIdleSync()
            awaitFocus("All channels")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            awaitFocus("Channel 1")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            awaitText("News")
            awaitFocus("All channels")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            awaitFocus("News")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            awaitText("Channel 8")
            instrumentation.waitForIdleSync()
            awaitFocus("Channel 1")
            assertTrue("Browsing categories must not tune a channel", watched.get() == 0)
            val upcoming = awaitText("Upcoming 1").first()
            var actionNode: AccessibilityNodeInfo? = upcoming
            while (actionNode != null && !actionNode.isClickable) actionNode = actionNode.parent
            assertTrue("Programme should accept remote focus", actionNode?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            awaitText("Watch channel")
            assertTrue("Viewing upcoming details must not tune a channel", watched.get() == 0)
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            awaitText("Channel 8")
            screenshot()
        }
    }
}
