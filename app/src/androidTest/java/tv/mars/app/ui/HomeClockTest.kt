package tv.mars.app.ui

import android.text.format.DateFormat
import android.graphics.Rect
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextClock
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.MainActivity
import tv.mars.app.ui.components.HomeClock
import tv.mars.app.ui.theme.MarsTvTheme
import tv.mars.app.core.MainDestination
import tv.mars.app.entitlement.EntitlementState
import java.util.Date
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

class HomeClockTest {
    @Test fun homeClockHasSameBottomRightPositionForFreeAndProInBothLayouts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (television in listOf(false, true)) {
                for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                    val positions = mutableListOf<Rect>()
                    val tiers = listOf(
                        EntitlementState.Free,
                        EntitlementState.Pro("test", emptySet(), "test", "test", 1, Instant.MAX),
                    )
                    for (destination in listOf(MainDestination.LIVE, MainDestination.MOVIES)) {
                        positions.clear()
                        for (tier in tiers) {
                            val rendered = CountDownLatch(1)
                            scenario.onActivity { activity ->
                                val viewModel = ViewModelProvider(activity)[MarsTvViewModel::class.java]
                                activity.setContent {
                                    MarsTvTheme {
                                        CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                            key(television, direction, tier, destination) {
                                                Box(Modifier.fillMaxSize().onGloballyPositioned { rendered.countDown() }) {
                                                    HomeShell(
                                                        state = MarsUiState(destination = destination, entitlementState = tier),
                                                        viewModel = viewModel,
                                                        isTelevision = television,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            assertTrue("Home layout must finish rendering", rendered.await(10, TimeUnit.SECONDS))
                            instrumentation.waitForIdleSync()
                            scenario.onActivity { activity ->
                                val clock = requireNotNull(findClock(activity.window.decorView))
                                val bounds = Rect()
                                assertTrue(clock.getGlobalVisibleRect(bounds))
                                val screen = Rect()
                                activity.window.decorView.getGlobalVisibleRect(screen)
                                assertTrue("Clock must be in the physical bottom-right", bounds.centerX() > screen.centerX() && bounds.centerY() > screen.centerY())
                                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                                    ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                                val edgeTolerance = (32 * activity.resources.displayMetrics.density).toInt()
                                val bottomGap = screen.bottom - (insets?.bottom ?: 0) - bounds.bottom
                                val rightGap = screen.right - (insets?.right ?: 0) - bounds.right
                                assertTrue("Clock must be at the bottom screen edge, gap=$bottomGap", bottomGap in 0..edgeTolerance)
                                assertTrue("Clock must be at the right screen edge, gap=$rightGap", rightGap in 0..edgeTolerance)
                                positions.add(bounds)
                            }
                            if (television && direction == LayoutDirection.Ltr && destination == MainDestination.LIVE) {
                                instrumentation.uiAutomation.waitForIdle(100, 5_000)
                                val accessibilityRoot = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                                val imageFile = File(instrumentation.targetContext.getExternalFilesDir(null), "home-clock-tv.png")
                                imageFile.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                screenshot.recycle()
                                val windowBounds = Rect()
                                accessibilityRoot.getBoundsInScreen(windowBounds)
                                for (label in listOf("Live TV", "Movies", "Series", "Search", "My TV", "Settings")) {
                                    val labelNode = findLabel(accessibilityRoot, label)
                                    assertNotNull("TV navigation must show $label", labelNode)
                                    val labelBounds = Rect()
                                    requireNotNull(labelNode).getBoundsInScreen(labelBounds)
                                    assertTrue("TV navigation must fully display $label", labelNode.isVisibleToUser && !labelBounds.isEmpty && windowBounds.contains(labelBounds))
                                }
                            }
                        }
                        assertEquals("Tier must not affect the right anchor", positions[0].right, positions[1].right)
                        assertEquals("Tier must not affect the bottom anchor", positions[0].bottom, positions[1].bottom)
                    }
                }
            }
        }
    }

    @Test fun clockUsesDeviceTimeAndRemainsNonInteractiveAfterResume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MarsTvTheme { HomeClock() } }
            }
            instrumentation.waitForIdleSync()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val clock = findClock(activity.window.decorView)
                assertNotNull("HomeClock must render a live platform clock", clock)
                requireNotNull(clock)
                assertFalse("Clock must not take D-pad focus", clock.isFocusable)
                assertFalse("Clock must not intercept selections", clock.isClickable)
                assertEquals(DateFormat.is24HourFormat(activity), clock.is24HourModeEnabled)
                val pattern = if (clock.is24HourModeEnabled) clock.format24Hour else clock.format12Hour
                val now = System.currentTimeMillis()
                val validTimes = listOf(now, now - 1_000).map { DateFormat.format(pattern, Date(it)).toString() }
                assertTrue("Clock must show current device time", clock.text.toString() in validTimes)
            }
            var initialText = ""
            scenario.onActivity { activity -> initialText = requireNotNull(findClock(activity.window.decorView)).text.toString() }
            val updated = AtomicBoolean(false)
            val deadline = android.os.SystemClock.elapsedRealtime() + 65_000
            while (!updated.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(250)
                scenario.onActivity { activity ->
                    updated.set(requireNotNull(findClock(activity.window.decorView)).text.toString() != initialText)
                }
            }
            assertTrue("Clock must update across a minute boundary", updated.get())
        }
    }

    private fun findLabel(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findLabel(child, label)?.let { return it }
        }
        return null
    }

    private fun findClock(view: View): TextClock? {
        if (view is TextClock) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findClock(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
