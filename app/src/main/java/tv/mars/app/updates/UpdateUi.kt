package tv.mars.app.updates

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.mars.app.BuildConfig
import tv.mars.app.MarsTvApplication
import tv.mars.app.ui.components.FocusSurface

@Composable
private fun updates() = (LocalContext.current.applicationContext as MarsTvApplication).updates

@Composable
fun UpdateSettings() {
    val manager = updates()
    val state by manager.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("MarsTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        FocusSurface(onClick = { manager.open(); manager.checkNow() }) {
            Text(if (state.release != null) "Update available: ${state.release?.versionName}" else "Check for updates",
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow update notifications") }
        }
    }
}

@Composable
fun UpdateDialog(allowed: Boolean) {
    val manager = updates()
    val state by manager.state.collectAsStateWithLifecycle()
    val visible by manager.dialogVisible.collectAsStateWithLifecycle()
    if (!visible || !allowed) return
    val release = state.release
    AlertDialog(
        onDismissRequest = manager::dismiss,
        title = { Text(if (release == null) "MarsTV updates" else "Update available: ${release.versionName}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Installed: ${BuildConfig.VERSION_NAME}")
                if (state.checking) Text("Checking for updates…")
                if (release != null) {
                    if (release.priority == "critical") Text("This release contains important fixes. Please update soon.")
                    if (BuildConfig.VERSION_CODE < release.minimumSupportedVersionCode) Text("This version is below the recommended support minimum. Local playback remains available.")
                    Text("Download: ${release.sizeBytes / (1024 * 1024)} MB")
                    release.releaseNotes.forEach { Text("• $it") }
                }
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading ${state.progress}%")
                }
                state.message?.let { Text(it) }
                if (release != null && !state.ready) Text("The download will be verified before Android asks you to approve installation.")
            }
        },
        confirmButton = {
            when {
                state.downloading -> TextButton(onClick = manager::cancel) { Text("Cancel download") }
                state.ready -> TextButton(onClick = manager::install) { Text("Install update") }
                release != null -> TextButton(onClick = manager::download, enabled = !state.checking) { Text("Download and install") }
                else -> TextButton(onClick = manager::checkNow, enabled = !state.checking) { Text("Check now") }
            }
        },
        dismissButton = { TextButton(onClick = manager::dismiss) { Text(if (release == null) "Close" else "Later") } },
    )
}
