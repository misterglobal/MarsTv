package tv.mars.app.ui.screens

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import tv.mars.app.MainActivity
import tv.mars.app.core.PlayerRequest
import tv.mars.app.data.network.NetworkClient
import tv.mars.app.ui.components.MarsButton
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed
import tv.mars.app.ui.theme.MarsWhite

@Composable
fun PlayerScreen(request: PlayerRequest, onClose: (positionMs: Long, durationMs: Long) -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val player = remember(request.url) {
        val networkClient = tv.mars.app.data.network.NetworkClient()
        val dataSourceFactory = OkHttpDataSource.Factory(networkClient.client)
            .setUserAgent(tv.mars.app.data.network.NetworkClient.USER_AGENT)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
    }
    var errorMessage by remember(request.url) { mutableStateOf<String?>(null) }
    var playbackLimitReached by remember(request.url) { mutableStateOf(false) }

    fun close() {
        onClose(player.currentPosition.coerceAtLeast(0L), player.duration.coerceAtLeast(0L))
    }

    BackHandler(onBack = ::close)

    DisposableEffect(player) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "This stream could not be played. It may be offline or use a format this device does not support."
            }
        }
        player.addListener(listener)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, request.url) {
        errorMessage = null
        player.setMediaItem(MediaItem.fromUri(request.url))
        player.prepare()
        if (request.resumePositionMs > 0L) player.seekTo(request.resumePositionMs)
        player.playWhenReady = true
    }

    LaunchedEffect(player, request.url, request.playbackLimitMs) {
        if (request.playbackLimitMs <= 0L) return@LaunchedEffect
        while (true) {
            delay(100)
            if (player.currentPosition >= request.playbackLimitMs) {
                player.pause()
                player.seekTo(request.playbackLimitMs)
                playbackLimitReached = true
                errorMessage = "Free playback ends at 10:00. Upgrade to MarsTV Pro to watch the full title."
                break
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerShowTimeoutMs = 4_000
                    keepScreenOn = true
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        Text(
            text = request.title,
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().background(MarsMidnight.copy(alpha = 0.64f)).padding(16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MarsWhite,
        )

        errorMessage?.let { message ->
            Column(
                modifier = Modifier.align(Alignment.Center).background(MarsMidnight.copy(alpha = 0.94f)).padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MarsRed)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (playbackLimitReached) "Free playback limit reached" else "Playback problem",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(message, color = MarsMuted)
                Spacer(Modifier.height(16.dp))
                MarsButton(text = "Go back", onClick = ::close)
            }
        }
    }
}
