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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
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
    private var subtitleWorker: Job? = null
    @Volatile private var activeId: String? = null
    @Volatile private var activeCall: Call? = null

    override fun onCreate() {
        super.onCreate()
        repository = OfflineRepository.get(this)
        api = HubClient.shared(this)
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
                if (active) cancelActiveWorker()
                repository.remove(it)
                if (active) continueSoon() else ensureWorker()
            }
            ACTION_REMOVE_BATCH -> intent.getStringExtra(EXTRA_ID)?.let {
                val active = repository.download(activeId.orEmpty())?.batchId == it
                if (active) cancelActiveWorker()
                repository.removeBatch(it)
                if (active) continueSoon() else ensureWorker()
            }
            ACTION_CANCEL_BATCH_KEEP -> intent.getStringExtra(EXTRA_ID)?.let {
                val active = repository.download(activeId.orEmpty())?.batchId == it
                if (active) cancelActiveWorker()
                repository.cancelBatch(it, false)
                if (active) continueSoon() else ensureWorker()
            }
            else -> Unit
        }
        ensureWorker()
        return START_STICKY
    }

    private fun ensureWorker() {
        ensureSubtitleWorker()
        // A cancelled worker may still be unwinding a blocking HTTP read.
        // Wait for it to finish before allowing another media stream.
        if (worker?.isCompleted == false) return
        startForeground(NOTIFICATION_ID, notification("Preparing downloads", 0, 0, true))
        worker = scope.launch { runQueue() }
    }

    private fun ensureSubtitleWorker() {
        if (subtitleWorker?.isActive == true || repository.nextSubtitleSyncRetryAt() == null) return
        subtitleWorker = scope.launch {
            while (isActive) {
                val subtitle = repository.nextSubtitleSync()
                if (subtitle == null) {
                    val retryAt = repository.nextSubtitleSyncRetryAt() ?: break
                    delay((retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L).coerceAtMost(RECHECK_DELAY_MS))
                    continue
                }
                if (!networkAvailable()) {
                    delay(RECHECK_DELAY_MS)
                    continue
                }
                try {
                    OfflineSubtitleSync(repository, api).sync(subtitle)
                    repository.clearSubtitleSync(subtitle.rowId,subtitle.expectedLanguage)
                    repository.download(subtitle.rowId)?.let { row -> publishAlert(row,"subtitle","ready","Subtitles are available offline. Your downloaded video was kept.","subtitles") }
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (error: Exception) {
                    DebugLog.log("offline", "subtitle sync ${subtitle.rowId} failed: ${error.message}")
                    repository.recordSubtitleSyncFailure(
                        subtitle.rowId, subtitle.expectedLanguage,error.message ?: "Could not update offline subtitles",
                        error is SubtitleSyncBlocked
                    )
                    repository.download(subtitle.rowId)?.let { row ->
                        if(repository.subtitleSyncForItem(row.manifest.item.id)?.retryAt?.let { it<0 }==true)
                            publishAlert(row,"subtitle","failed","Subtitle update needs attention. Open to review and retry.","subtitles")
                    }
                }
            }
        }
    }

    private fun cancelAndContinue() {
        cancelActiveWorker()
        continueSoon()
    }

    private fun cancelActiveWorker() {
        worker?.cancel()
        activeCall?.cancel()
    }

    private fun continueSoon() {
        android.os.Handler(mainLooper).postDelayed({
            if (worker?.isCompleted == false) continueSoon() else ensureWorker()
        }, 150L)
    }

    private suspend fun runQueue() {
        syncProgress()
        while (currentCoroutineContext().isActive) {
            val row = repository.nextQueued()
            if (row == null) {
                val retryAt = repository.nextRetryAt()
                if (retryAt != null) {
                    val waitMillis = (retryAt - System.currentTimeMillis()).coerceAtLeast(1_000L)
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Waiting to retry a download", 0, 0, true)
                    )
                    delay(waitMillis.coerceAtMost(RECHECK_DELAY_MS))
                    continue
                }
                if (repository.outbox().isNotEmpty() && !networkAvailable()) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("Waiting to sync watch progress", 0, 0, true)
                    )
                    delay(RECHECK_DELAY_MS)
                    continue
                }
                if (subtitleWorker?.isActive == true) {
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
                repository.download(row.id)?.takeIf { it.state==OfflineState.COMPLETE }?.let {
                    publishAlert(it,"download","ready","Downloaded and ready on this AYANEO.","offline")
                }
            } catch (_: CancellationException) {
                repository.download(row.id)?.takeIf { it.state == OfflineState.DOWNLOADING }?.let {
                    repository.setState(it.id, OfflineState.QUEUED)
                }
                throw CancellationException()
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) {
                    repository.download(row.id)?.takeIf { it.state == OfflineState.DOWNLOADING }?.let {
                        repository.setState(it.id, OfflineState.QUEUED)
                    }
                    throw CancellationException()
                }
                DebugLog.log("offline", "${row.id} failed: ${error.message}")
                repository.recordFailure(
                    row.id,
                    error.message ?: "Download interrupted",
                    OfflineSettings.maxRetries(this@OfflineDownloadService)
                )
                val failed = repository.download(row.id)?.state == OfflineState.FAILED
                updateNotification(row, if (failed) "Needs attention" else "Waiting to retry")
                if(failed) publishAlert(row,"download","failed","Download needs attention. Open to review the error and retry.","offline")
                // A transient network error must not block every later item in
                // a series. recordFailure schedules this item for a resumable
                // retry, so the next loop can advance the queue immediately.
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
        var response = executeMediaCall(manifest.mediaUrl, existing)
        if (response.code == 410) {
            response.close()
            activeCall = null
            when (val renewed = api.renewOffline(manifest.grantId)) {
                is HubResult.Ok -> {
                    manifest = renewed.value
                    repository.updateManifest(row.id, manifest)
                    response = executeMediaCall(manifest.mediaUrl, existing)
                }
                is HubResult.Failed -> throw IOException(renewed.message)
            }
        }
        try {
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
                        val speedSamples = ArrayDeque<Pair<Long, Long>>()
                        speedSamples.addLast(transferStartedAt to existing)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            currentCoroutineContext().ensureActive()
                            output.write(buffer, 0, count)
                            written += count
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 500L) {
                                lastUpdate = now
                                speedSamples.addLast(now to written)
                                while (speedSamples.size > 2 && speedSamples.first().first < now - 10_000L) {
                                    speedSamples.removeFirst()
                                }
                                val (sampleTime, sampleBytes) = speedSamples.first()
                                val speed = ((written - sampleBytes) * 1_000L /
                                    (now - sampleTime).coerceAtLeast(1L)).coerceAtLeast(0L)
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
        } finally {
            activeCall = null
        }
        repository.finish(row.id)
        row = repository.download(row.id) ?: return
        if (row.state == OfflineState.COMPLETE) {
            downloadSubtitles(row)
            downloadArtwork(row)
        }
    }

    private suspend fun executeMediaCall(url: String, offset: Long): Response {
        var call = api.offlineDownloadCall(url, offset)
        activeCall = call
        try {
            currentCoroutineContext().ensureActive()
            val privateCall = api.offlineDownloadCall(url, offset, usePrivateRoute = true)
            val canFallBack = privateCall.request().url != call.request().url
            var firstError: IOException? = null
            val firstResponse = try {
                call.execute()
            } catch (error: IOException) {
                firstError = error
                null
            }
            if (!canFallBack || (firstResponse != null && firstResponse.code != 404 &&
                    firstResponse.code !in 500..599)) {
                return firstResponse ?: throw firstError!!
            }
            firstResponse?.close()
            currentCoroutineContext().ensureActive()
            DebugLog.log("offline", "public media route unavailable; trying private Hub route")
            call = privateCall
            activeCall = call
            return call.execute()
        } catch (error: Exception) {
            if (activeCall === call) activeCall = null
            throw error
        }
    }

    private suspend fun downloadSubtitles(row: OfflineDownload) {
        row.manifest.subtitles.forEach { subtitle ->
            val target = repository.subtitleFile(row, subtitle.track.index, subtitle.track.codec)
            val temporary = File(target.absolutePath + ".part")
            try {
                executeMediaCall(subtitle.url, 0).use { response ->
                    if (!response.isSuccessful) throw IOException("subtitle HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("empty subtitle")
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("could not store subtitle")
            } catch (error: Exception) {
                temporary.delete()
                if (!currentCoroutineContext().isActive) throw CancellationException()
                // The original file remains fully playable and still contains
                // all embedded tracks. Missing external sidecars can be retried
                // by removing and re-queueing this item.
                DebugLog.log("offline", "subtitle ${subtitle.track.index} failed: ${error.message}")
            } finally {
                activeCall = null
            }
        }
    }

    private suspend fun downloadArtwork(row: OfflineDownload) {
        listOf(
            "thumb" to row.manifest.item.thumb,
            "poster" to row.manifest.item.poster,
            "backdrop" to row.manifest.item.backdrop
        ).filter { it.second.isNotBlank() }.forEach { (kind, path) ->
            val target = repository.artworkFile(row, kind)
            if (target.isFile && target.length() > 0) return@forEach
            val temporary = File(target.absolutePath + ".part")
            try {
                executeMediaCall(path, 0).use { response ->
                    if (!response.isSuccessful) throw IOException("artwork HTTP ${response.code}")
                    response.body?.byteStream()?.use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IOException("empty artwork")
                }
                if (target.exists()) target.delete()
                if (!temporary.renameTo(target)) throw IOException("could not store artwork")
            } catch (error: Exception) {
                temporary.delete()
                if (!currentCoroutineContext().isActive) throw CancellationException()
            } finally {
                activeCall = null
            }
        }
    }

    private suspend fun syncProgress() {
        if (!networkAvailable()) return
        while (true) {
            val events = repository.outbox()
            if (events.isEmpty()) return
            when (val result = api.syncOfflineProgress(OfflineProgressSyncBody(events))) {
                is HubResult.Ok -> {
                    val durations = events.associate { it.clientEventKey to it.durationMillis }
                    result.value.results.filter { it.status == "server_newer" && it.serverLastPlayedAt > 0 }.forEach {
                        repository.adoptServerWatch(it.itemId, OfflineCatalogProgress.fromServer(
                            it.serverPositionMillis, durations[it.clientEventKey] ?: 0L,
                            it.serverPlayed, it.serverLastPlayedAt
                        ))
                    }
                    repository.removeOutbox(result.value.results.map { it.clientEventKey })
                }
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
        // Waits rather than failing: each item used to try, get a 401 and move
        // straight to the next, so a queued series could hand the Hub the five
        // failures that ban this device. Fixing the token resumes the queue.
        api.credentialProblem()?.let { return it }
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

    private fun publishAlert(row: OfflineDownload, family: String, state: String, message: String, destination: String) {
        com.pocketds.hub.settings.LocalAlerts.publish(this,
            com.pocketds.hub.settings.LocalAlert("$family:${row.id}:$state",row.manifest.item.id,row.manifest.item.title,message,destination,
                eventKey=if(family=="subtitle" && state=="ready") row.updatedAt.toString() else ""),row.userId)
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
        worker?.cancel(); activeCall?.cancel(); subtitleWorker?.cancel(); scope.cancel(); super.onDestroy()
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
