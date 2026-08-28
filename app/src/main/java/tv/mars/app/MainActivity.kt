package tv.mars.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.mars.app.ui.MarsTvRoot
import tv.mars.app.ui.MarsTvViewModel
import tv.mars.app.ui.theme.MarsTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val isTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        setContent {
            MarsTvTheme {
                val viewModel: MarsTvViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                MarsTvRoot(viewModel = viewModel, isTelevision = isTelevision)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN) {
            try {
                ViewModelProvider(this).get(MarsTvViewModel::class.java).clearHiddenCaches()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }
}
