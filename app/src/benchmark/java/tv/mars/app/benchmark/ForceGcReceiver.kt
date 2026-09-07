package tv.mars.app.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.util.Log

class ForceGcReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FORCE_GC) return
        val pendingResult = goAsync()
        Thread {
            try {
                repeat(2) {
                    Runtime.getRuntime().gc()
                    System.runFinalization()
                    Thread.sleep(GC_SETTLE_MILLIS)
                }
                val runtime = Runtime.getRuntime()
                val usedBytes = runtime.totalMemory() - runtime.freeMemory()
                val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                Log.i(
                    LOG_TAG,
                    "forced_gc_complete javaUsedBytes=$usedBytes " +
                        "dalvikPssKiB=${memoryInfo.dalvikPss} totalPssKiB=${memoryInfo.totalPss}",
                )
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private companion object {
        const val ACTION_FORCE_GC = "tv.mars.app.benchmark.FORCE_GC"
        const val LOG_TAG = "MarsCatalogMetrics"
        const val GC_SETTLE_MILLIS = 500L
    }
}
