package tv.mars.app.ui

import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.mars.app.MainActivity
import tv.mars.app.ui.components.FocusSurface
import tv.mars.app.ui.components.MarsButton
import tv.mars.app.ui.components.RemoteNavigation
import tv.mars.app.ui.theme.MarsTvTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RemoteSelectionTest {
    @Test fun firstOkActivatesOnceOnOpeningAndAfterScreenReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstFocused = AtomicBoolean()
        val secondFocused = AtomicBoolean()
        val firstClicks = AtomicInteger()
        val secondClicks = AtomicInteger()
        instrumentation.setInTouchMode(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    var screen by remember { mutableStateOf(0) }
                    MarsTvTheme {
                        RemoteNavigation(true, screen, Modifier.fillMaxSize()) {
                            Column {
                                if (screen == 0) {
                                    FocusSurface(onClick = { firstClicks.incrementAndGet(); screen = 1 },
                                        modifier = Modifier.onFocusChanged { firstFocused.set(it.isFocused) }) {
                                        Text("Open categories")
                                    }
                                } else {
                                    MarsButton("Select category", onClick = { secondClicks.incrementAndGet() },
                                        modifier = Modifier.onFocusChanged { secondFocused.set(it.isFocused) })
                                }
                            }
                        }
                    }
                }
            }
            fun await(description: String, condition: () -> Boolean) {
                val deadline = System.currentTimeMillis() + 5_000
                while (!condition() && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    Thread.sleep(50)
                }
                assertTrue(description, condition())
            }
            await("TV must focus its first control before any remote key", firstFocused::get)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("One OK should open the next screen", secondFocused::get)
            assertEquals(1, firstClicks.get())
            assertEquals("The opening press must not also activate the next screen", 0, secondClicks.get())
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            await("One OK should select the category", { secondClicks.get() == 1 })
            instrumentation.waitForIdleSync()
            assertEquals(1, secondClicks.get())
        }
    }
}
