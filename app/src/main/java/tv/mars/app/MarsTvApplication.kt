package tv.mars.app

import android.app.Application
import tv.mars.app.updates.DirectUpdateManager
import tv.mars.app.updates.UpdateWorker

class MarsTvApplication : Application() {
    val updates by lazy { DirectUpdateManager(this) }
    override fun onCreate() {
        super.onCreate()
        if (updates.configured) UpdateWorker.schedule(this)
    }
}
