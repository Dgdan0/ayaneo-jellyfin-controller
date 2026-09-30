package com.pocketds.hub.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.pocketds.hub.model.PlaybackCapabilities
import com.pocketds.hub.model.PlaybackDevice
import com.pocketds.hub.model.PlaybackEventBody
import com.pocketds.hub.model.PlaybackPrepareBody
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** The Cast SDK keeps the receiver session alive while the player screen is hidden. */
internal object CastPlaybackCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var plan: PlaybackPrepareResponse? = null
    private var client: RemoteMediaClient? = null
    private var api: HubApi? = null
    private var userId = ""
    private var sequence = 0L
    private var lastPaused: Boolean? = null
    private var lastPosition = 0L
    private var stopping = false
    private val eventMutex = Mutex()

    val activePlan: PlaybackPrepareResponse? get() = plan
    val isActive: Boolean get() = plan != null && client != null
    val positionMillis: Long get() = client?.approximateStreamPosition?.coerceAtLeast(0) ?: lastPosition
    val isPlaying: Boolean get() = client?.isPlaying == true
    val deviceName: String get() = runCatching {
        CastContext.getSharedInstance(appContext ?: return@runCatching "TV")
            .sessionManager.currentCastSession?.castDevice?.friendlyName ?: "TV"
    }.getOrDefault("TV")
    private var appContext: Context? = null

    private val receiverCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val remote = client ?: return
            if (plan == null) return
            if (remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                remote.mediaStatus?.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                finish(stopReceiver = false)
                return
            }
            val paused = remote.isPaused
            if (lastPaused != null && paused != lastPaused && (remote.isPaused || remote.isPlaying)) {
                report(if (paused) "paused" else "unpaused", paused)
            }
            if (remote.isPaused || remote.isPlaying) lastPaused = paused
        }
    }

    private val progress = Runnable {
        if (isActive) {
            lastPosition = positionMillis
            if (isPlaying) report("progress", false)
            handler.postDelayed(progressTick, 10_000L)
        }
    }
    private val progressTick: Runnable get() = progress

    suspend fun transfer(
        context: Context,
        source: HubApi,
        current: PlaybackPrepareResponse,
        position: Long,
        startMode: String = "resume"
    ): String? {
        val receiver = runCatching {
            CastContext.getSharedInstance(context).sessionManager.currentCastSession?.remoteMediaClient
        }.getOrNull() ?: return "Connect to a Chromecast or Google TV first"
        val base = HubSettings.castBaseUrl(context)
        if (CastTransferPolicy.receiverUrl(base, "/v1/cast/check/stream") == null) {
            return "Set a public HTTPS Hub address for TV playback in Settings"
        }
        val pinnedUser = HubSettings.userId(context)
        val localDevice = PlaybackCapabilitiesProbe.prepare(context, "resume").device
        val castBody = PlaybackPrepareBody(
            startMode = startMode,
            positionMillis = position.coerceAtLeast(0),
            mediaSourceId = current.selectedMediaSourceId.takeIf { it.isNotEmpty() },
            audioStreamIndex = current.selectedAudioIndex,
            subtitleStreamIndex = current.selectedSubtitleIndex,
            maxBitrate = 20_000_000,
            forceTranscode = true,
            device = PlaybackDevice(localDevice.id + "-cast", "Pocket DS to Google TV", localDevice.version),
            capabilities = PlaybackCapabilities(
                width = 1920, height = 1080, maxAudioChannels = 2,
                videoCodecs = listOf("h264"), audioCodecs = listOf("aac", "mp3")
            )
        )
        val prepared = when (val result = source.preparePlayback(current.item.id, castBody)) {
            is HubResult.Ok -> result.value
            is HubResult.Failed -> return result.message
        }
        val grant = when (val result = source.castGrant(prepared.sessionId, pinnedUser)) {
            is HubResult.Ok -> result.value
            is HubResult.Failed -> {
                source.deletePlayback(prepared.sessionId, pinnedUser)
                return result.message
            }
        }
        val mediaUrl = CastTransferPolicy.receiverUrl(base, grant.mediaUrl)
        if (mediaUrl == null) {
            source.deletePlayback(prepared.sessionId, pinnedUser)
            return "The TV cannot reach this Hub address"
        }
        val tracks = prepared.subtitleTracks.mapNotNull { track ->
            val path = grant.subtitleUrls[track.index.toString()] ?: return@mapNotNull null
            val url = CastTransferPolicy.receiverUrl(base, path) ?: return@mapNotNull null
            MediaTrack.Builder(track.index.toLong() + 1000L, MediaTrack.TYPE_TEXT)
                .setName(track.label)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(url)
                .setContentType("text/vtt")
                .setLanguage(track.language.ifBlank { "und" })
                .build()
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, prepared.item.displayTitle())
        }
        val media = MediaInfo.Builder(mediaUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(if (prepared.mediaUrl.contains("/hls/")) "application/vnd.apple.mpegurl" else prepared.mimeType)
            .setStreamDuration(prepared.durationMillis)
            .setMetadata(metadata)
            .setMediaTracks(tracks)
            .build()
        val selectedTrack = prepared.selectedSubtitleIndex?.toLong()?.plus(1000L)
            ?.takeIf { id -> tracks.any { it.id == id } }
        val load = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setCurrentTime(prepared.positionMillis)
            .setAutoplay(true)
            .setActiveTrackIds(selectedTrack?.let { longArrayOf(it) } ?: longArrayOf())
            .build()
        val accepted = try {
            suspendCancellableCoroutine<Boolean> { continuation ->
                receiver.load(load).setResultCallback { result ->
                    if (continuation.isActive) continuation.resume(result.status.isSuccess)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            receiver.stop()
            withContext(NonCancellable) { source.deletePlayback(prepared.sessionId, pinnedUser) }
            throw cancelled
        }
        if (!accepted) {
            source.deletePlayback(prepared.sessionId, pinnedUser)
            return "The TV could not open this stream"
        }
        if (isActive) finish(stopReceiver = false)
        appContext = context.applicationContext
        api = HubClient.shared(context)
        userId = pinnedUser
        plan = prepared
        client = receiver
        sequence = 0L
        lastPosition = prepared.positionMillis
        lastPaused = false
        stopping = false
        receiver.registerCallback(receiverCallback)
        report("started", false, prepared.positionMillis)
        handler.removeCallbacks(progressTick)
        handler.postDelayed(progressTick, 10_000L)
        return null
    }

    fun togglePlay() {
        val remote = client ?: return
        if (remote.isPlaying) remote.pause() else remote.play()
    }

    fun seekTo(positionMillis: Long) {
        val target = positionMillis.coerceAtLeast(0)
        client?.seek(MediaSeekOptions.Builder().setPosition(target).build())
        lastPosition = target
        report("seek", !isPlaying, target)
    }

    fun selectSubtitle(index: Int?) {
        val current = plan ?: return
        val allowed = index?.takeIf { candidate ->
            current.subtitleTracks.any { it.index == candidate && it.external }
        }
        client?.setActiveMediaTracks(allowed?.let { longArrayOf(it.toLong() + 1000L) } ?: longArrayOf())
        plan = current.copy(selectedSubtitleIndex = allowed)
    }

    fun stop() = finish(stopReceiver = true)

    private fun report(kind: String, paused: Boolean, positionOverride: Long? = null) {
        val current = plan ?: return
        val hub = api ?: return
        val pinned = userId
        val position = positionOverride ?: positionMillis
        sequence++
        val number = sequence
        scope.launch {
            eventMutex.withLock {
                hub.playbackEvent(current.sessionId, PlaybackEventBody(kind, number, position, paused), pinned)
            }
        }
    }

    private fun finish(stopReceiver: Boolean) {
        if (stopping) return
        val current = plan ?: return
        stopping = true
        val remote = client
        val hub = api
        val pinned = userId
        val position = positionMillis
        remote?.unregisterCallback(receiverCallback)
        handler.removeCallbacks(progressTick)
        if (stopReceiver) remote?.stop()
        sequence++
        val number = sequence
        plan = null
        client = null
        scope.launch {
            if (hub != null) {
                eventMutex.withLock {
                    hub.playbackEvent(current.sessionId, PlaybackEventBody("stopped", number, position), pinned)
                    hub.deletePlayback(current.sessionId, pinned)
                }
            }
            stopping = false
        }
    }
}
