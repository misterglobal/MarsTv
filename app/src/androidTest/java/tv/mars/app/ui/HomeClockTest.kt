package tv.mars.app.ui

import android.text.format.DateFormat
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
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
                    for (tier in tiers) {
                        val rendered = CountDownLatch(1)
                        scenario.onActivity { activity ->
                            val viewModel = ViewModelProvider(activity)[MarsTvViewModel::class.java]
                            activity.setContent {
                                MarsTvTheme {
                                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                        key(television, direction, tier) {
                                            Box(Modifier.fillMaxSize().onGloballyPositioned { rendered.countDown() }) {
                                                HomeShell(
                                                    state = MarsUiState(destination = MainDestination.MOVIES, entitlementState = tier),
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
                            positions.add(bounds)
                        }
                    }
                    assertEquals("Tier must not affect the right anchor", positions[0].right, positions[1].right)
                    assertEquals("Tier must not affect the bottom anchor", positions[0].bottom, positions[1].bottom)
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
