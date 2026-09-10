package com.pocketds.hub.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.pocketds.hub.HubActivity
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.PlaybackEventBody
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.settings.HubSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Owns ExoPlayer, the MediaSession, and ordered Jellyfin progress reporting. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var api: HubClient
    private lateinit var offline: OfflineRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private var plan: PlaybackPrepareResponse? = null
    private var sequence = 0L
    private var started = false
    private var ended = false
    private var stopAfterCleanup = false
    private var lastProgressAt = 0L
    private var pinnedUserId = ""
    private var fallbackAttempted = false
    private var subtitleOffsetMillis = 0L
    private var audioGateGeneration = 0L
    private var audioGateScheduled = false
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                    lastProgressAt = now
                    plan?.let { enqueueEvent(it, "progress", false) }
                }
            }
            progressHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        api = HubClient(applicationContext)
        offline = OfflineRepository.get(applicationContext)
        val headers = buildMap {
            HubSettings.token(this@PlaybackService).takeIf { it.isNotEmpty() }?.let {
                put("Authorization", "Bearer $it")
            }
            HubSettings.userId(this@PlaybackService).takeIf { it.isNotEmpty() }?.let {
                put("X-Jellyfin-User", it)
            }
        }
        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val path = request.url.encodedPath.let { value ->
                    when {
                        "/hls/" in value -> value.substringBefore("/hls/") + "/hls/{resource}"
                        else -> value
                    }
                }
                DebugLog.log(
                    "media",
                    "${request.method} $path -> ${response.code}"
                )
                response
            }
            .build()
        val upstream = OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers)
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, upstream))
        val trackSelector = DefaultTrackSelector(this)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        DebugLog.log(
            "player",
            "FFmpeg audio available=${FfmpegLibrary.isAvailable()} " +
                "AC3=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3)} " +
                "EAC3=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_E_AC3)}"
        )
        player = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSources)
            .build()
        // ExoPlayer initially assumes full app volume. Hold it silent until the
        // audio route is ready so Android has time to apply an existing device mute.
        player.volume = 0f
        player.addListener(listener)
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .build()
        serviceScope.launch {
            for (operation in operations) process(operation)
        }
        progressHandler.post(progressTick)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> intent.getStringExtra(EXTRA_PLAN)?.let { encoded ->
                runCatching { JSON.decodeFromString<PlaybackPrepareResponse>(encoded) }
                    .onSuccess {
                        load(
                            it,
                            intent.getBooleanExtra(EXTRA_AUTO_PLAY, true),
                            intent.getLongExtra(EXTRA_SUBTITLE_OFFSET, 0L)
                        )
                    }
                    .onFailure { DebugLog.log("player", "invalid playback plan: ${it.message}") }
            }
            ACTION_PAUSE -> if (::player.isInitialized && player.isPlaying) player.pause()
            ACTION_SUBTITLE_OFFSET -> applySubtitleOffset(intent.getLongExtra(EXTRA_SUBTITLE_OFFSET, 0L))
            ACTION_STOP -> stopPlayback()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun load(next: PlaybackPrepareResponse, autoPlay: Boolean, subtitleOffset: Long) {
        val previous = plan
        val sameSession = previous?.sessionId == next.sessionId
        if (previous != null && !sameSession) {
            enqueueStop(previous, player.currentPosition.coerceAtLeast(0), delete = true)
        }
        plan = next
        publishedPlan = next
        if (!sameSession || pinnedUserId.isEmpty()) pinnedUserId = HubSettings.userId(this)
        if (!sameSession) {
            sequence = 0
            started = false
        }
        fallbackAttempted = next.playMethod.equals("Transcode", ignoreCase = true)
        ended = false
        stopAfterCleanup = false
        lastProgressAt = 0
        subtitleOffsetMillis = subtitleOffset.coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        audioGateGeneration++
        audioGateScheduled = false
        player.volume = 0f
        DebugLog.log("player", "audio start gate closed for ${next.item.id}")

        val selectedAudio = next.audioTracks.firstOrNull { it.index == next.selectedAudioIndex }
        val selectedSubtitle = next.subtitleTracks.firstOrNull { it.index == next.selectedSubtitleIndex }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(selectedAudio?.language?.takeIf { it.isNotEmpty() })
            .setPreferredTextLanguage(selectedSubtitle?.language?.takeIf { it.isNotEmpty() })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, selectedSubtitle == null)
            .build()
        player.setMediaItem(mediaItem(next), next.positionMillis)
        player.prepare()
        player.playWhenReady = autoPlay
    }

    private fun mediaItem(value: PlaybackPrepareResponse): MediaItem {
        // Media3 prepares every side-loaded subtitle in the media item. One
        // unusable track in a large language set can therefore stop the video
        // itself. A track change returns a new plan and rebuilds this item, so
        // only the selected external subtitle needs to be attached here.
        val subtitles = value.subtitleTracks.filter {
            it.external && it.externalUrl.isNotEmpty() && it.index == value.selectedSubtitleIndex
        }
            .map { track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl(track.externalUrl, value.offline)))
                    .setId(track.index.toString())
                    .setMimeType(when (track.codec.lowercase()) {
                        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                        "ttml" -> MimeTypes.APPLICATION_TTML
                        else -> MimeTypes.TEXT_VTT
                    })
                    .setLanguage(track.language.ifEmpty { null })
                    .setLabel(track.label.ifEmpty { null })
                    .setSelectionFlags(
                        if (track.index == value.selectedSubtitleIndex) C.SELECTION_FLAG_DEFAULT else 0
                    )
                    .build()
            }
        return MediaItem.Builder()
            .setMediaId(value.item.id)
            .setUri(if (value.offline) Uri.parse(value.mediaUrl) else Uri.parse(api.playbackUrl(value.mediaUrl)))
            .setMimeType(value.mimeType)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(value.item.title)
                    .setArtist(value.item.seriesTitle.takeIf { it.isNotEmpty() })
                    .setSubtitle(value.item.displayTitle())
                    .build()
            )
            .build()
    }

    private fun subtitleUrl(path: String, local: Boolean = false): String {
        val absolute = if (local) path else api.playbackUrl(path)
        if (local) return absolute
        if (subtitleOffsetMillis == 0L) return absolute
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}offsetMillis=$subtitleOffsetMillis"
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val current = plan ?: return
            if (isPlaying) {
                enqueueEvent(current, if (started) "unpaused" else "started", false)
                started = true
                ended = false
            } else if (started && !ended && player.playbackState == Player.STATE_READY) {
                enqueueEvent(current, "paused", true)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                plan?.let { enqueueEvent(it, "seek", !player.isPlaying) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val current = plan ?: return
            if (playbackState == Player.STATE_READY) openAudioGate(current)
            if (playbackState == Player.STATE_ENDED && !ended) {
                ended = true
                enqueueEvent(current, "stopped", false, current.durationMillis)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            DebugLog.log("player", "Media3 ${error.errorCodeName}: ${error.message}")
            val current = plan ?: return
            if (current.offline) return
            if (fallbackAttempted || current.playMethod.equals("Transcode", ignoreCase = true)) return

            fallbackAttempted = true
            val sessionId = current.sessionId
            val userId = pinnedUserId
            val position = player.currentPosition.coerceAtLeast(current.positionMillis)
            val autoPlay = player.playWhenReady
            DebugLog.log("player", "requesting compatible Jellyfin transcode for ${current.item.id}")
            serviceScope.launch {
                val selected = api.selectPlayback(
                    sessionId,
                    com.pocketds.hub.model.PlaybackSelectBody(
                        positionMillis = position,
                        forceTranscode = true
                    ),
                    userId
                )
                when (selected) {
                    is com.pocketds.hub.net.HubResult.Ok -> withContext(Dispatchers.Main) {
                        if (plan?.sessionId == sessionId) load(selected.value, autoPlay, subtitleOffsetMillis)
                    }
                    is com.pocketds.hub.net.HubResult.Failed -> DebugLog.log(
                        "player",
                        "transcode fallback failed: ${selected.message}"
                    )
                }
            }
        }

    }

    private fun openAudioGate(current: PlaybackPrepareResponse) {
        if (audioGateScheduled) return
        audioGateScheduled = true
        val generation = audioGateGeneration
        progressHandler.postDelayed({
            if (
                generation == audioGateGeneration &&
                plan?.sessionId == current.sessionId &&
                ::player.isInitialized
            ) {
                // Restore ExoPlayer's normal app volume. Android's media-stream
                // volume and mute remain authoritative after route initialization.
                player.volume = 1f
                DebugLog.log("player", "audio start gate opened for ${current.item.id}")
            }
        }, AUDIO_START_GATE_MS)
    }

    private fun applySubtitleOffset(offsetMillis: Long) {
        val current = plan ?: return
        val selected = current.subtitleTracks.firstOrNull { it.index == current.selectedSubtitleIndex }
        if (selected?.external != true) return
        val safeOffset = offsetMillis.coerceIn(-MAX_SUBTITLE_OFFSET_MS, MAX_SUBTITLE_OFFSET_MS)
        if (safeOffset == subtitleOffsetMillis) return
        val position = player.currentPosition.coerceAtLeast(0)
        val autoPlay = player.playWhenReady
        subtitleOffsetMillis = safeOffset
        audioGateGeneration++
        audioGateScheduled = false
        player.volume = 0f
        player.setMediaItem(mediaItem(current), position)
        player.prepare()
        player.playWhenReady = autoPlay
        DebugLog.log("player", "subtitle offset set to ${safeOffset}ms")
    }

    private fun enqueueEvent(
        value: PlaybackPrepareResponse,
        type: String,
        paused: Boolean,
        position: Long = player.currentPosition.coerceAtLeast(0)
    ) {
        if (value.offline) {
            val completed = type == "stopped" && (
                ended || value.durationMillis > 0 && position >= value.durationMillis - 30_000L
            )
            serviceScope.launch {
                offline.rememberPlayback(value.item.id, position, value.durationMillis, completed)
            }
            return
        }
        sequence++
        operations.trySend(
            Operation.Event(
                value.sessionId,
                pinnedUserId,
                PlaybackEventBody(type, sequence, position, paused, player.isDeviceMuted, 100)
            )
        )
    }

    private fun enqueueStop(value: PlaybackPrepareResponse, position: Long, delete: Boolean) {
        if (started && !ended) enqueueEvent(value, "stopped", true, position)
        if (delete && !value.offline) operations.trySend(Operation.Delete(value.sessionId, pinnedUserId))
    }

    private fun stopPlayback() {
        val current = plan
        val position = player.currentPosition.coerceAtLeast(0)
        if (current != null) {
            PlaybackProgressStore.remember(
                this,
                pinnedUserId,
                current.item,
                position,
                current.durationMillis
            )
        }
        audioGateGeneration++
        audioGateScheduled = false
        player.volume = 0f
        player.pause()
        player.clearMediaItems()
        plan = null
        publishedPlan = null
        if (current == null) {
            stopSelf()
            return
        }
        stopAfterCleanup = true
        enqueueStop(current, position, delete = true)
    }

    private suspend fun process(operation: Operation) {
        when (operation) {
            is Operation.Event -> {
                val result = api.playbackEvent(operation.sessionId, operation.body, operation.userId)
                if (result is com.pocketds.hub.net.HubResult.Failed) {
                    DebugLog.log("player", "event ${operation.body.type} failed: ${result.message}")
                }
            }
            is Operation.Delete -> {
                val result = api.deletePlayback(operation.sessionId, operation.userId)
                if (result is com.pocketds.hub.net.HubResult.Failed) {
                    DebugLog.log("player", "session close failed: ${result.message}")
                }
                if (stopAfterCleanup && plan == null) stopSelf()
            }
        }
    }

    override fun onDestroy() {
        progressHandler.removeCallbacksAndMessages(null)
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        publishedPlan = null
        operations.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun sessionActivity(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, HubActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )

    private sealed interface Operation {
        data class Event(
            val sessionId: String,
            val userId: String,
            val body: PlaybackEventBody
        ) : Operation
        data class Delete(val sessionId: String, val userId: String) : Operation
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private const val ACTION_LOAD = "com.pocketds.hub.playback.LOAD"
        private const val ACTION_PAUSE = "com.pocketds.hub.playback.PAUSE"
        private const val ACTION_SUBTITLE_OFFSET = "com.pocketds.hub.playback.SUBTITLE_OFFSET"
        private const val ACTION_STOP = "com.pocketds.hub.playback.STOP"
        private const val EXTRA_PLAN = "plan"
        private const val EXTRA_AUTO_PLAY = "auto_play"
        private const val EXTRA_SUBTITLE_OFFSET = "subtitle_offset"
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val AUDIO_START_GATE_MS = 250L
        private const val MAX_SUBTITLE_OFFSET_MS = 10 * 60 * 1_000L
        @Volatile private var publishedPlan: PlaybackPrepareResponse? = null

        fun currentPlan(): PlaybackPrepareResponse? = publishedPlan

        fun load(
            context: Context,
            plan: PlaybackPrepareResponse,
            autoPlay: Boolean = true,
            subtitleOffsetMillis: Long = 0L
        ) {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = ACTION_LOAD
                putExtra(EXTRA_PLAN, JSON.encodeToString(plan))
                putExtra(EXTRA_AUTO_PLAY, autoPlay)
                putExtra(EXTRA_SUBTITLE_OFFSET, subtitleOffsetMillis)
            })
        }

        fun setSubtitleOffset(context: Context, offsetMillis: Long) {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                action = ACTION_SUBTITLE_OFFSET
                putExtra(EXTRA_SUBTITLE_OFFSET, offsetMillis)
            })
        }

        fun pause(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }

        fun sessionToken(context: Context) =
            androidx.media3.session.SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }
}
