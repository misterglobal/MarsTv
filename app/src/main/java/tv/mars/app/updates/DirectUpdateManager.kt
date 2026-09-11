package tv.mars.app.updates

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import tv.mars.app.BuildConfig
import tv.mars.app.MainActivity
import tv.mars.app.data.network.MarsBackendClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateState(
    val release: UpdateManifest? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val ready: Boolean = false,
    val message: String? = null,
)

/** Direct-distribution updater only. A future Play variant must use its own implementation. */
class DirectUpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val preferences = context.getSharedPreferences("direct_updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "updates")
    private val apk = File(directory, "release.apk")
    private val partial = File(directory, "release.part")
    private val backend = MarsBackendClient(BuildConfig.MARS_BACKEND_URL)
    private val client = backend.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()
    private val certificate = runCatching { certificateOf(context.packageManager.getPackageInfo(context.packageName, signatureFlags())) }.getOrDefault("")
    private val verifier = UpdateManifestVerifier(BuildConfig.UPDATE_PUBLIC_KEY_PEM, BuildConfig.UPDATE_KEY_ID, certificate)
    val configured: Boolean get() = verifier.configured
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val visible = MutableStateFlow(false)
    val dialogVisible = visible.asStateFlow()
    private var job: Job? = null
    @Volatile private var activeCall: Call? = null

    init {
        // APKs are never resumed across process restarts without revalidation.
        directory.mkdirs()
        partial.delete()
        if (System.currentTimeMillis() - apk.lastModified() > TimeUnit.DAYS.toMillis(1)) apk.delete()
        val cached = preferences.getString("manifest", null)
        if (cached != null) runCatching { verifier.verify(cached) }.getOrNull()
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            ?.let { mutable.value = UpdateState(release = it) }
    }

    fun open() { visible.value = true }
    fun dismiss() {
        visible.value = false
        state.value.release?.let { preferences.edit().putLong("deferred_version", it.versionCode).apply() }
    }
    fun checkNow() { scope.launch { checkForUpdates(manual = true) } }

    suspend fun checkForUpdates(manual: Boolean = false): Boolean = mutex.withLock {
        if (!configured) {
            if (manual) mutable.value = state.value.copy(message = "Updates are not configured in this build.")
            return@withLock true
        }
        if (state.value.downloading) return@withLock true
        val now = System.currentTimeMillis()
        val last = preferences.getLong("last_attempt", 0)
        val interval = if (manual) 60_000L else TimeUnit.HOURS.toMillis(6)
        if (now >= last && now - last < interval) {
            if (manual) mutable.value = state.value.copy(message = "Checked recently. Please try again in a minute.")
            return@withLock true
        }
        preferences.edit().putLong("last_attempt", now).apply()
        mutable.value = state.value.copy(checking = true, message = null)
        try {
            val response = fetchManifest()
            val release = response?.second?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            if (release == null) {
                preferences.edit().remove("manifest").apply()
                apk.delete()
                mutable.value = UpdateState(message = if (manual) "MarsTV is up to date. No newer release is currently offered." else null)
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            } else {
                preferences.edit().putString("manifest", response.first).apply()
                val unchanged = release == state.value.release
                if (!unchanged) apk.delete()
                mutable.value = UpdateState(release = release, ready = unchanged && state.value.ready && apk.exists())
                if (release.priority != "optional" && preferences.getLong("deferred_version", 0) != release.versionCode) open()
                notifyRelease(release)
            }
            true
        } catch (cancelled: CancellationException) {
            mutable.value = state.value.copy(checking = false)
            throw cancelled
        } catch (_: Exception) {
            mutable.value = state.value.copy(checking = false, message = "Could not verify an update. Your current version remains usable.")
            false
        }
    }

    private fun fetchManifest(): Pair<String, UpdateManifest>? {
        val request = backend.request("/api/v1/releases/direct-stable").header("Accept", "application/jose").build()
        client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 204) return null
            check(response.isSuccessful) { "Update server unavailable" }
            check(response.header("Content-Type")?.substringBefore(';')?.trim() == "application/jose")
            val body = response.body ?: throw IOException("Missing manifest")
            check(body.contentLength() <= UpdateManifestVerifier.MAX_MANIFEST_BYTES)
            val bytes = body.byteStream().readBytesBounded(UpdateManifestVerifier.MAX_MANIFEST_BYTES)
            val token = bytes.toString(Charsets.UTF_8).trim()
            return token to verifier.verify(token)
        }
    }

    fun download() {
        if (job?.isActive == true) return
        job = scope.launch {
            mutex.withLock {
                val selected = state.value.release ?: return@withLock
                mutable.value = state.value.copy(downloading = true, checking = false, ready = false, progress = 0, message = null)
                apk.delete()
                try {
                    // Recheck before download so a withdrawn or replaced release is not installed from a cached offer.
                    val fresh = fetchManifest()?.second
                    check(fresh == selected && fresh.versionCode > BuildConfig.VERSION_CODE) { "Release changed or withdrawn" }
                    check(directory.usableSpace > selected.sizeBytes + 16 * 1024 * 1024) { "Not enough storage" }
                    val call = client.newCall(Request.Builder().url(selected.apkUrl).header("Accept-Encoding", "identity").build())
                    activeCall = call
                    call.execute().use { response ->
                        check(response.isSuccessful)
                        val body = response.body ?: throw IOException("Missing APK")
                        check(body.contentLength() == -1L || body.contentLength() == selected.sizeBytes)
                        val coroutine = currentCoroutineContext()
                        body.byteStream().use { input -> partial.outputStream().use { output ->
                            ApkTransfer.copy(input, output, selected.sizeBytes, selected.sha256) { progress ->
                                coroutine.ensureActive()
                                mutable.value = state.value.copy(progress = progress)
                            }
                        } }
                    }
                    verifyArchive(partial, selected)
                    check(partial.renameTo(apk))
                    mutable.value = state.value.copy(downloading = false, ready = true, message = "Download verified. Select Install update to continue.")
                } catch (cancelled: CancellationException) {
                    partial.delete()
                    mutable.value = state.value.copy(downloading = false, ready = false, message = "Download cancelled.")
                    throw cancelled
                } catch (_: Exception) {
                    partial.delete()
                    apk.delete()
                    mutable.value = state.value.copy(downloading = false, ready = false,
                        message = "Download could not be verified. Check your connection and storage, then check for updates again.")
                } finally { activeCall = null }
            }
        }
    }

    fun cancel() { job?.cancel(); activeCall?.cancel() }

    /** Called only by an explicit user action; never by a worker or notification receiver. */
    fun install() {
        if (!state.value.ready || job?.isActive == true) return
        job = scope.launch {
            mutex.withLock {
                val release = state.value.release ?: return@withLock
                mutable.value = state.value.copy(message = "Rechecking the release before installation…")
                try {
                    check(fetchManifest()?.second == release) { "Release withdrawn" }
                    verifyArchive(apk, release)
                    withContext(Dispatchers.Main) {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            openInstallSettings()
                            mutable.value = state.value.copy(message = "Allow MarsTV to install updates, then return and select Install update again.")
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            mutable.value = state.value.copy(message = "Confirm in Android's installer. If you cancel, you can retry here.")
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ActivityNotFoundException) {
                    mutable.value = state.value.copy(message = "Open your device settings and allow MarsTV to install apps, then return and select Install update. If this device has no installer, use the website download on a supported device.")
                }
                catch (_: SecurityException) {
                    mutable.value = state.value.copy(message = "Your device blocked the installer. Check its app installation permissions, then try Install update again.")
                }
                catch (_: Exception) {
                    apk.delete()
                    mutable.value = state.value.copy(ready = false, message = "Could not open a verified update. Check for updates and try again. Your current app has not changed.")
                }
            }
        }
    }

    private fun openInstallSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Some TV firmware exposes only the general security settings page.
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun verifyArchive(file: File, release: UpdateManifest) {
        check(file.length() == release.sizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().hex().equals(release.sha256, true))
        val info = context.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: error("Invalid APK")
        @Suppress("DEPRECATION") val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        check(info.packageName == context.packageName && version == release.versionCode && version > BuildConfig.VERSION_CODE)
        check(info.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true)
        check(certificateOf(info).equals(certificate, true))
    }

    @Suppress("MissingPermission")
    private fun notifyRelease(release: UpdateManifest) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled() || preferences.getLong("notified_version", 0) == release.versionCode) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("app_updates", "MarsTV updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("show_updates", true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 410, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, "app_updates")
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("MarsTV update available")
                .setContentText("Version ${release.versionName} is ready to download.").setContentIntent(pending).setAutoCancel(true).build())
            preferences.edit().putLong("notified_version", release.versionCode).apply()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 410
        @Suppress("DEPRECATION")
        private fun signatureFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        private fun certificateOf(info: PackageInfo): String {
            val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            check(signatures?.size == 1)
            return MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray()).hex()
        }
        internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        check(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
