package com.pocketds.hub.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketds.hub.HubActivity
import com.pocketds.hub.R
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.model.OfflineProgressSyncBody
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.OfflineSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

/** One serial, resumable original-file transfer queue owned by a foreground service. */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: OfflineRepository
    private lateinit var api: HubClient
    private var worker: Job? = null
    @Volatile private var activeId: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = OfflineRepository.get(this)
        api = HubClient(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every entry point uses startForegroundService so commands are safe
        // when Android has already reclaimed the previous worker. A pause or
        // remove command can legitimately arrive after the last byte completed;
        // acknowledge foreground startup before inspecting queue state or the
        // OS terminates the whole app five seconds later.
        startForeground(NOTIFICATION_ID, notification("Updating download queue", 0, 0, true))
        when (intent?.action) {
            ACTION_PAUSE_BATCH -> intent.getStringExtra(EXTRA_ID)?.let {
                repository.setBatchPaused(it, true)
                if (repository.download(activeId.orEmpty())?.batchId == it) cancelAndContinue()
            }
            ACTION_RESUME_BATCH -> intent.getStringExtra(EXTRA_ID)?.let {
                repository.setBatchPaused(it, false)
                ensureWorker()
            }
            ACTION_RETRY -> intent.getStringExtra(EXTRA_ID)?.let {
                repository.retry(it); ensureWorker()
            }
            ACTION_PAUSE_ITEM -> intent.getStringExtra(EXTRA_ID)?.let {
                repository.setItemPaused(it, true)
                if (activeId == it) cancelAndContinue()
            }
            ACTION_RESUME_ITEM -> intent.getStringExtra(EXTRA_ID)?.let {
                repository.setItemPaused(it, false); ensureWorker()
            }
            ACTION_REMOVE -> intent.getStringExtra(EXTRA_ID)?.let {
                val active = activeId == it
                if (active) worker?.cancel()
                repository.remove(it)
                if (active) continueSoon() else ensureWorker()
            }
            ACTION_REMOVE_BATCH -> intent.getStringExtra(EXTRA_ID)?.let {
                val active = repository.download(activeId.orEmpty())?.batchId == it
                if (active) worker?.cancel()
                repository.removeBatch(it)
                if (active) continueSoon() else ensureWorker()
            }
            ACTION_CANCEL_BATCH_KEEP -> intent.getStringExtra(EXTRA_ID)?.let {
                val active = repository.download(activeId.orEmpty())?.batchId == it
                if (active) worker?.cancel()
                repository.cancelBatch(it, false)
                if (active) continueSoon() else ensureWorker()
            }
            else -> Unit
        }
        ensureWorker()
        return START_STICKY
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        startForeground(NOTIFICATION_ID, notification("Preparing downloads", 0, 0, true))
        worker = scope.launch { runQueue() }
    }

    private fun cancelAndContinue() {
        worker?.cancel()
        continueSoon()
    }

    private fun continueSoon() {
        android.os.Handler(mainLooper).postDelayed({ ensureWorker() }, 150L)
    }

    private suspend fun runQueue() {
        syncProgress()
        while (scope.isActive) {
            val row = repository.nextQueued()
            if (row == null) {
                if (repository.outbox().isNotEmpty() && !networkAvailable()) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Waiting to sync watch progress", 0, 0, true)
                    )
                    delay(RECHECK_DELAY_MS)
                    continue
                }
                break
            }
            activeId = row.id
            val waitReason = blockedReason(row)
            if (waitReason != null) {
                repository.setState(row.id, OfflineState.WAITING, waitReason)
                updateNotification(row, waitReason)
                delay(RECHECK_DELAY_MS)
                continue
            }
            try {
                download(row)
            } catch (_: CancellationException) {
                repository.download(row.id)?.takeIf { it.state == OfflineState.DOWNLOADING }?.let {
                    repository.setState(it.id, OfflineState.QUEUED)
                }
                throw CancellationException()
            } catch (error: Exception) {
                DebugLog.log("offline", "${row.id} failed: ${error.message}")
                repository.recordFailure(
                    row.id,
                    error.message ?: "Download interrupted",
                    OfflineSettings.maxRetries(this@OfflineDownloadService)
                )
                val failed = repository.download(row.id)?.state == OfflineState.FAILED
                updateNotification(row, if (failed) "Needs attention" else "Waiting to retry")
                if (!failed) delay(RECHECK_DELAY_MS)
            } finally {
                activeId = null
            }
        }
        syncProgress()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun download(initial: OfflineDownload) {
        var row = repository.download(initial.id) ?: return
        var manifest = row.manifest
        var existing = repository.mediaFile(row).takeIf { it.isFile }?.length() ?: 0L
        if (existing > row.totalBytes) {
            repository.mediaFile(row).delete(); existing = 0
        }
        if (existing > 0) repository.updateProgress(row.id, existing)
        var response = api.offlineDownloadCall(manifest.mediaUrl, existing).execute()
        if (response.code == 410) {
            response.close()
            when (val renewed = api.renewOffline(manifest.grantId)) {
                is HubResult.Ok -> {
                    manifest = renewed.value
                    repository.updateManifest(row.id, manifest)
                    response = api.offlineDownloadCall(manifest.mediaUrl, existing).execute()
                }
                is HubResult.Failed -> throw IOException(renewed.message)
            }
        }
        response.use { streamResponse ->
            if (!validContentRange(
                    existing,
                    manifest.source.sizeBytes,
                    streamResponse.code,
                    streamResponse.header("Content-Range")
                )
            ) throw IOException("Hub returned an invalid resume range")
            val writeMode = transferWriteMode(existing, manifest.source.sizeBytes, streamResponse.code)
            if (writeMode == TransferWriteMode.COMPLETE) {
                repository.finish(row.id)
                (repository.download(row.id) ?: row).let {
                    downloadSubtitles(it)
                    downloadArtwork(it)
                }
                return
            }
            val append = writeMode == TransferWriteMode.APPEND
            if (existing > 0 && writeMode == TransferWriteMode.RESTART) {
                repository.mediaFile(row).delete(); existing = 0
            }
            val body = streamResponse.body ?: throw IOException("Hub returned an empty media stream")
            val file = repository.mediaFile(row)
            file.parentFile?.mkdirs()
            FileOutputStream(file, append).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    var lastUpdate = 0L
                    val transferStartedAt = android.os.SystemClock.elapsedRealtime()
                    val transferStartedBytes = existing
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 500L) {
                            lastUpdate = now
                            val elapsed = (now - transferStartedAt).coerceAtLeast(1L)
                            val speed = ((written - transferStartedBytes) * 1_000L / elapsed).coerceAtLeast(0L)
                            repository.updateProgress(row.id, written, speedBytesPerSecond = speed)
                            updateNotification(
                                row.copy(bytesDownloaded = written, speedBytesPerSecond = speed),
                                "Downloading"
                            )
                        }
                    }
                    output.fd.sync()
                    repository.updateProgress(row.id, written, speedBytesPerSecond = 0L)
                }
            }
        }
        repository.finish(row.id)
        row = repository.download(row.id) ?: return
        if (row.state == OfflineState.COMPLETE) {
            downloadSubtitles(row)
            downloadArtwork(row)
        }
    }

    private fun downloadSubtitles(row: OfflineDownload) {
        row.manifest.subtitles.forEach { subtitle ->
            val target = repository.subtitleFile(row, subtitle.track.index, subtitle.track.codec)
            val temporary = File(target.absolutePath + ".part")
            runCatching {
                api.offlineDownloadCall(subtitle.url, 0).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("subtitle HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("empty subtitle")
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("could not store subtitle")
            }.onFailure {
                temporary.delete()
                // The original file remains fully playable and still contains
                // all embedded tracks. Missing external sidecars can be retried
                // by removing and re-queueing this item.
                DebugLog.log("offline", "subtitle ${subtitle.track.index} failed: ${it.message}")
            }
        }
    }

    private fun downloadArtwork(row: OfflineDownload) {
        listOf(
            "thumb" to row.manifest.item.thumb,
            "poster" to row.manifest.item.poster,
            "backdrop" to row.manifest.item.backdrop
        ).filter { it.second.isNotBlank() }.forEach { (kind, path) ->
            val target = repository.artworkFile(row, kind)
            if (target.isFile && target.length() > 0) return@forEach
            val temporary = File(target.absolutePath + ".part")
            runCatching {
                api.offlineDownloadCall(path, 0).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("artwork HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("empty artwork")
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("could not store artwork")
            }.onFailure { temporary.delete() }
        }
    }

    private suspend fun syncProgress() {
        if (!networkAvailable()) return
        while (true) {
            val events = repository.outbox()
            if (events.isEmpty()) return
            when (val result = api.syncOfflineProgress(OfflineProgressSyncBody(events))) {
                is HubResult.Ok -> repository.removeOutbox(result.value.results.map { it.clientEventKey })
                is HubResult.Failed -> {
                    DebugLog.log("offline", "progress sync failed: ${result.message}")
                    return
                }
            }
            if (events.size < 50) return
        }
    }

    private fun blockedReason(row: OfflineDownload): String? {
        if (!networkAvailable()) return "Waiting for a network connection"
        if (OfflineSettings.wifiOnly(this) && !onUnmeteredNetwork()) return "Waiting for Wi-Fi"
        if (OfflineSettings.chargingOnly(this) && !isCharging()) return "Waiting for charging"
        val remaining = (row.totalBytes - row.bytesDownloaded).coerceAtLeast(0)
        val reserve = OfflineSettings.minimumFreeMb(this) * 1024L * 1024L
        val available = repository.availableBytes(row)
        if (available <= 0L) return "Waiting for the selected storage device"
        if (available < remaining + reserve) return "Not enough free space"
        return null
    }

    private fun networkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun onUnmeteredNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isCharging(): Boolean =
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            .let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }

    private fun updateNotification(row: OfflineDownload, status: String) {
        val total = row.totalBytes.coerceAtLeast(1)
        val progress = (row.bytesDownloaded * 100.0 / total).roundToInt().coerceIn(0, 100)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID, notification("${row.manifest.item.title} · $status", progress, 100, false)
        )
    }

    private fun notification(text: String, progress: Int, max: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_offline)
            .setContentTitle("Offline downloads")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0, Intent(this, HubActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .setProgress(max, progress, indeterminate)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        worker?.cancel(); scope.cancel(); super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "offline_downloads"
        private const val NOTIFICATION_ID = 2401
        private const val RECHECK_DELAY_MS = 15_000L
        private const val ACTION_START = "com.pocketds.hub.offline.START"
        private const val ACTION_PAUSE_BATCH = "com.pocketds.hub.offline.PAUSE_BATCH"
        private const val ACTION_RESUME_BATCH = "com.pocketds.hub.offline.RESUME_BATCH"
        private const val ACTION_RETRY = "com.pocketds.hub.offline.RETRY"
        private const val ACTION_PAUSE_ITEM = "com.pocketds.hub.offline.PAUSE_ITEM"
        private const val ACTION_RESUME_ITEM = "com.pocketds.hub.offline.RESUME_ITEM"
        private const val ACTION_REMOVE = "com.pocketds.hub.offline.REMOVE"
        private const val ACTION_REMOVE_BATCH = "com.pocketds.hub.offline.REMOVE_BATCH"
        private const val ACTION_CANCEL_BATCH_KEEP = "com.pocketds.hub.offline.CANCEL_BATCH_KEEP"
        private const val EXTRA_ID = "id"

        fun start(context: Context) = command(context, ACTION_START)
        fun pauseBatch(context: Context, id: String) = command(context, ACTION_PAUSE_BATCH, id)
        fun resumeBatch(context: Context, id: String) = command(context, ACTION_RESUME_BATCH, id)
        fun retry(context: Context, id: String) = command(context, ACTION_RETRY, id)
        fun pauseItem(context: Context, id: String) = command(context, ACTION_PAUSE_ITEM, id)
        fun resumeItem(context: Context, id: String) = command(context, ACTION_RESUME_ITEM, id)
        fun remove(context: Context, id: String) = command(context, ACTION_REMOVE, id)
        fun removeBatch(context: Context, id: String) = command(context, ACTION_REMOVE_BATCH, id)
        fun cancelBatchKeepCompleted(context: Context, id: String) = command(context, ACTION_CANCEL_BATCH_KEEP, id)

        private fun command(context: Context, action: String, id: String = "") {
            val intent = Intent(context, OfflineDownloadService::class.java).setAction(action)
            if (id.isNotEmpty()) intent.putExtra(EXTRA_ID, id)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
