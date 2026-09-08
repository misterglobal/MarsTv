package tv.mars.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.mars.app.ui.components.MarsButton
import tv.mars.app.ui.theme.MarsMuted
import tv.mars.app.ui.theme.MarsRed

@Composable
fun UpgradeScreen(lockedFeatureName: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (lockedFeatureName == null) Icons.Default.Check else Icons.Default.Lock, null, tint = MarsRed)
        Spacer(Modifier.height(12.dp))
        Text("MarsTV Pro", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
        Text("CAD $14.99 · one-time lifetime licence · one device", color = MarsMuted)
        if (lockedFeatureName != null) {
            Spacer(Modifier.height(14.dp))
            Text("$lockedFeatureName requires MarsTV Pro", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(22.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf(
                "Multiple TV sources and profiles",
                "Full programme guide and catch-up",
                "Unlimited movie and series playback",
                "Global search, full history, and resume",
                "Unlimited favourites and parental controls",
            ).forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, null, tint = MarsRed)
                    Text(feature, Modifier.padding(start = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Device activation and checkout are coming in the next implementation stage.", color = MarsMuted)
        Spacer(Modifier.height(16.dp))
        MarsButton(text = "Back", onClick = onBack)
    }
}
