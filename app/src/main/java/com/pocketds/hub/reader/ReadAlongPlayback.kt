package com.pocketds.hub.reader

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlinx.coroutines.*

/** Foreground narration owned by its reader. Pauses on hide/background; never starts on opening a book. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadAlongPlayback(
    context: Context,
    val timeline: ReadAlongTimeline,
    files: List<File>,
    initial: ReadAlongPosition?,
    private val onSegment: (ReadAlongSegment?) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onSave: (ReadAlongPosition, Boolean) -> Unit,
    private val onError: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private var active: ReadAlongSegment? = null
    private var lastSaved = 0L
    private var engaged = false
    private var pauseGeneration = 0
    val isPlaying get() = player.isPlaying
    val position get() = ReadAlongPosition(player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0))
    var speed: Float
        get() = player.playbackParameters.speed
        set(value) { player.setPlaybackSpeed(value.coerceIn(.5f, 2f)) }

    init {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onState(isPlaying)
                if (!isPlaying && engaged && player.playbackState != Player.STATE_IDLE) save()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { onSegment(null); save(completed = true) }
            }
            override fun onPlayerError(error: PlaybackException) { pause(); onError() }
        })
        player.setMediaItems(timeline.tracks.mapIndexed { index, track ->
            MediaItem.Builder().setUri(Uri.fromFile(files[index])).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(track.startMs)
                    .setEndPositionMs(track.startMs + track.durationMs).build()
            ).build()
        })
        initial?.let { seek(it) }
        player.prepare()
        scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    val next = timeline.active(position.track, position.offsetMs)
                    if (next != active) { active = next; onSegment(next) }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSaved >= 10_000) { save(); lastSaved = now }
                }
                delay(100)
            }
        }
    }

    fun toggle() {
        if (player.playWhenReady) pause() else {
            pauseGeneration++
            engaged = true
            if (player.playbackState == Player.STATE_ENDED) seek(ReadAlongPosition(0, 0))
            player.play()
        }
    }
    fun pause(settle: Boolean = true) {
        player.pause()
        if (engaged) {
            save()
            // The renderer may settle a few milliseconds after the pause command.
            val generation = ++pauseGeneration
            if (settle) scope.launch { delay(150); if (generation == pauseGeneration && !player.playWhenReady) save() }
        }
        engaged = false
        if (!settle) pauseGeneration++
    }
    fun seek(where: ReadAlongPosition) {
        pauseGeneration++
        val track = timeline.tracks.getOrNull(where.track) ?: return
        player.seekTo(where.track, where.offsetMs.coerceIn(0, track.durationMs.coerceAtLeast(1) - 1))
        active = null
    }
    fun jump(deltaMs: Long) {
        var target = position.track
        var offset = position.offsetMs + deltaMs
        while (offset < 0 && target > 0) { target--; offset += timeline.tracks[target].durationMs }
        while (offset >= timeline.tracks[target].durationMs && target < timeline.tracks.lastIndex) {
            offset -= timeline.tracks[target].durationMs; target++
        }
        seek(ReadAlongPosition(target, offset))
        onSegment(timeline.active(position.track, position.offsetMs))
        save()
    }
    private fun save(completed: Boolean = player.playbackState == Player.STATE_ENDED) = onSave(position, completed)
    fun release() { pause(); scope.cancel(); player.release() }
}
