package tv.mars.app.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import tv.mars.app.MarsTvApplication
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as MarsTvApplication).updates.checkForUpdates()
        // Network failures wait for the next scheduled check; never block playback or install in the background.
        return Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setInitialDelay(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("direct-update-check", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
