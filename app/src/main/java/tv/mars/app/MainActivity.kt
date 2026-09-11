package tv.mars.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import tv.mars.app.ui.MarsTvRoot
import tv.mars.app.ui.MarsTvViewModel
import tv.mars.app.ui.theme.MarsTvTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { (application as MarsTvApplication).updates.checkForUpdates() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("show_updates", false)) (application as MarsTvApplication).updates.open()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("show_updates", false)) (application as MarsTvApplication).updates.open()
        enableEdgeToEdge()
        val isTelevision = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        setContent {
            MarsTvTheme {
                val viewModel: MarsTvViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                MarsTvRoot(viewModel = viewModel, isTelevision = isTelevision)
            }
        }
    }
}
