package tv.mars.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import tv.mars.app.MainActivity
import tv.mars.app.core.*
import tv.mars.app.entitlement.EntitlementState
import tv.mars.app.core.ContentKind
import tv.mars.app.core.PlayerRequest
import tv.mars.app.ui.screens.PlayerScreen
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GuidePlayerTest {
    @Test fun previewFullscreenAndBackKeepTheSamePlayingPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = File(instrumentation.targetContext.cacheDir, "guide-player-test.wav")
        // Local, silent 30-second PCM fixture: no provider, external network, or credentials needed.
        val dataSize = 8_000 * 2 * 30
        val wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        wav.put("data".toByteArray()).putInt(dataSize)
        audio.writeBytes(wav.array())
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        kotlin.concurrent.thread(isDaemon = true) {
            runCatching {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${wav.array().size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(wav.array())
                        flush()
                    }
                }
            }
        }
        val account = IptvAccount(id = "fixture", name = "Fixture", sourceType = SourceType.XTREAM)
        val request = PlayerRequest("fixture", "fixture", "Test channel", "http://127.0.0.1:${server.localPort}/test.wav", ContentKind.LIVE)
        val state = mutableStateOf(MarsUiState(
            local = LocalState(accounts = listOf(account), activeAccountId = account.id),
            playerRequest = request, entitlementState = EntitlementState.Free,
        ))
        var playerView: PlayerView? = null
        fun findPlayer(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findPlayer(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        PlayerScreen(
                            state.value.playerRequest!!,
                            onClose = { _, _ -> state.value = state.value.returnToLiveGuide() },
                            compact = state.value.overlay != OverlayScreen.PLAYER,
                            modifier = if (state.value.overlay != OverlayScreen.PLAYER) Modifier.size(160.dp, 90.dp) else Modifier.fillMaxSize(),
                        )
                    }
                }
                val deadline = System.currentTimeMillis() + 10_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        playerView = findPlayer(it.window.decorView)
                        ready = playerView?.player?.playbackState == Player.STATE_READY
                    }
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("Local fixture should play", ready)
                var original: Player? = null
                scenario.onActivity {
                    original = playerView!!.player
                    assertFalse(playerView!!.useController)
                    state.value = state.value.copy(destination = MainDestination.SEARCH, overlay = OverlayScreen.PLAYER)
                }
                val expandDeadline = System.currentTimeMillis() + 5_000
                var expanded = false
                while (!expanded && System.currentTimeMillis() < expandDeadline) {
                    scenario.onActivity { expanded = playerView!!.useController }
                    if (!expanded) Thread.sleep(50)
                }
                scenario.onActivity {
                    assertSame(original, playerView!!.player)
                    assertTrue(playerView!!.useController)
                    assertTrue(original!!.isPlaying)
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                val collapseDeadline = System.currentTimeMillis() + 5_000
                var collapsed = false
                while (!collapsed && System.currentTimeMillis() < collapseDeadline) {
                    scenario.onActivity { collapsed = !playerView!!.useController }
                    if (!collapsed) Thread.sleep(50)
                }
                scenario.onActivity {
                    assertEquals(OverlayScreen.NONE, state.value.overlay)
                    assertEquals(MainDestination.LIVE, state.value.destination)
                    assertSame(request, state.value.playerRequest)
                    assertSame(original, playerView!!.player)
                    assertFalse(playerView!!.useController)
                    assertTrue(original!!.isPlaying)
                }
            }
        } finally {
            server.close()
            audio.delete()
        }
    }
}
