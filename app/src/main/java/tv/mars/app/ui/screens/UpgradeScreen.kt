package tv.mars.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import tv.mars.app.entitlement.ActivationState
import tv.mars.app.ui.components.MarsButton
import tv.mars.app.ui.theme.MarsMidnight
import tv.mars.app.ui.theme.MarsRed
import java.time.Instant

@Composable
fun UpgradeScreen(lockedFeatureName: String?, activationState: ActivationState, onActivate: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(if (lockedFeatureName == null) Icons.Default.Check else Icons.Default.Lock, null, tint = MarsRed)
        Spacer(Modifier.height(10.dp))
        Text(
            if (activationState is ActivationState.Ready) "Activate MarsTV Pro" else "MarsTV Pro",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color.White,
        )
        Text("US $12.99 · one-time lifetime licence · one device", color = Color.White)
        if (lockedFeatureName != null) Text("$lockedFeatureName requires MarsTV Pro", color = Color.White)
        Spacer(Modifier.height(16.dp))
        when (activationState) {
            ActivationState.Idle -> Benefits(onActivate)
            ActivationState.Loading -> CircularProgressIndicator(color = MarsRed)
            is ActivationState.Failed -> {
                Text(activationState.message, color = Color.White)
                Spacer(Modifier.height(12.dp))
                MarsButton("Retry activation", onClick = onActivate)
            }
            is ActivationState.Ready -> ActivationDetails(activationState, onActivate)
            ActivationState.Activated -> Text("MarsTV Pro is active on this device.", color = Color.White)
        }
        Spacer(Modifier.height(14.dp))
        MarsButton("Back", onClick = onBack)
    }
}

@Composable
private fun Benefits(onActivate: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        listOf("Multiple TV sources and profiles", "Full programme guide and catch-up", "Unlimited movie and series playback", "Global search, full history, and resume", "Unlimited favourites and parental controls").forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = MarsRed)
                Text(it, Modifier.padding(start = 10.dp), color = Color.White)
            }
        }
    }
    Spacer(Modifier.height(18.dp))
    MarsButton("Activate Pro", onClick = onActivate)
}

@Composable
private fun ActivationDetails(state: ActivationState.Ready, onRenew: () -> Unit) {
    var remaining by remember(state.expiresAt) { mutableLongStateOf(secondsRemaining(state.expiresAt)) }
    LaunchedEffect(state.expiresAt) {
        while (remaining > 0) { delay(1_000); remaining = secondsRemaining(state.expiresAt) }
    }
    if (remaining == 0L) {
        Text("This activation code has expired.", color = Color.White)
        Spacer(Modifier.height(10.dp))
        MarsButton("Get a new code", onClick = onRenew)
        return
    }
    QrCode(state.qrPayload)
    Spacer(Modifier.height(10.dp))
    Text("Visit ${state.activationUrl}", fontWeight = FontWeight.Bold, color = Color.White)
    Text("Device ID: ${state.deviceCode}", color = Color.White)
    Surface(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MarsMidnight,
        contentColor = Color.White,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ACTIVATION CODE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(state.activationCode, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        }
    }
    Text("Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", color = Color.White)
    Text("Waiting for activation…", color = Color.White)
}

@Composable
private fun QrCode(payload: String) {
    val bitmap = remember(payload) {
        runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 240, 240)
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height) }
        }.getOrNull()
    }
    if (bitmap != null) Image(bitmap.asImageBitmap(), "Scan to activate MarsTV Pro", Modifier.size(180.dp))
}

private fun secondsRemaining(expiresAt: Instant) = (expiresAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
