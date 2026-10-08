package com.pocketds.hub.reader

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

/**
 * Narration owned by its reader. Leaving the book pauses it; the screen going off does not (#49): once it
 * plays, [NarrationService] holds it with a media notification, and the wake lock below keeps the device
 * streaming. It never starts on opening a book. It reports to [AudioHandoff] (#16, A1), so starting
 * it pauses video or an audiobook and either of those pauses it.
 *
 * Each stretch of the [timeline] plays from its [NarrationSource] (#19): a
 * track the hub streams, read through the reading players' cache
 * ([AudioStreams]), or a file taken out of the whole edition; clipped from
 * where its file begins in the source plus its first sentence's begin.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReadAlongPlayback(
    context: Context,
    val timeline: ReadAlongTimeline,
    sources: List<NarrationSource>,
    initial: ReadAlongPosition?,
    private val onSegment: (ReadAlongSegment?) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onSave: (ReadAlongPosition, Boolean) -> Unit,
    private val onError: () -> Unit,
    /** The book's title, for the notification and the lock screen. */
    private val title: String = ""
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app = context.applicationContext
    private val player = ExoPlayer.Builder(app).setMediaSourceFactory(AudioStreams.mediaSources(app)).build()
    private val pauser: () -> Unit = { pause() }
    private var active: ReadAlongSegment? = null
    private var lastSaved = 0L
    private var engaged = false
    private var pauseGeneration = 0
    val isPlaying get() = player.isPlaying
    /**
     * Playing, or about to once a seek has buffered: a sentence step or a jump
     * leaves the player buffering for a moment, which [isPlaying] reports as
     * stopped. A page turn, the pill and the dock go by this (A5).
     */
    val isOn get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
    val position get() = ReadAlongPosition(player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0))
    var speed: Float
        get() = player.playbackParameters.speed
        set(value) { player.setPlaybackSpeed(Listening.clampSpeed(value)) }

    init {
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        player.setHandleAudioBecomingNoisy(true)
        // Playing on with the screen off, streaming: the wake lock and the Wi-Fi lock while it plays (#49).
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) AudioHandoff.started(app, AudioSource.NARRATION) else AudioHandoff.stopped(AudioSource.NARRATION)
                onState(isPlaying)
                if (!isPlaying && engaged && player.playbackState != Player.STATE_IDLE) save()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) { onSegment(null); save(completed = true) }
            }
            override fun onPlayerError(error: PlaybackException) { pause(); onError() }
        })
        player.setMediaItems(timeline.tracks.mapIndexed { index, track ->
            val source = sources[index]
            MediaItem.Builder().setUri(Uri.parse(source.uri)).setCustomCacheKey(source.cacheKey.ifEmpty { null })
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { null }).setArtist("Read along").build())
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(source.startMs + track.startMs)
                    .setEndPositionMs(source.startMs + track.startMs + track.durationMs).build()
            ).build()
        })
        initial?.let { seek(it) }
        player.prepare()
        AudioHandoff.register(AudioSource.NARRATION, pauser)
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
            // The service that holds the voice with the screen off starts with it (#49).
            NarrationHost.engage(app, this)
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
    /**
     * L1 and R1 read along (#16, A5): the sentence before or after, its
     * glow and the page with it. False at either end of the book.
     */
    fun stepSentence(delta: Int): Boolean {
        val target = timeline.step(position, delta) ?: return false
        seek(target)
        onSegment(timeline.active(target.track, target.offsetMs))
        save()
        return true
    }

    /**
     * What the media session drives (#49): the player, but play and pause go through [toggle] and [pause] so the
     * place is kept as from the dock, and the notification's skips are the dock's jumps by the seek step the
     * player uses everywhere. A sentence is too small to skip from a lock screen, and a track is a stretch of the
     * recording the book is cut into, not a chapter.
     */
    internal fun sessionPlayer(): Player = object : ForwardingPlayer(player) {
        private fun skipMs() = com.pocketds.hub.settings.PlaybackSettings.seekSeconds(app) * 1_000L
        // A book of one recording has no next track, which would leave the notification without a skip forward.
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SEEK_BACK).build()
        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        override fun play() { if (!player.playWhenReady) toggle() }
        override fun pause() { this@ReadAlongPlayback.pause() }
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
        override fun seekForward() = jump(skipMs())
        override fun seekBack() = jump(-skipMs())
        override fun seekToNext() = jump(skipMs())
        override fun seekToPrevious() = jump(-skipMs())
        override fun seekToNextMediaItem() = jump(skipMs())
        override fun seekToPreviousMediaItem() = jump(-skipMs())
    }

    private fun save(completed: Boolean = player.playbackState == Player.STATE_ENDED) = onSave(position, completed)
    fun release() {
        pause()
        NarrationHost.release(this)
        AudioHandoff.unregister(AudioSource.NARRATION, pauser)
        scope.cancel()
        player.release()
    }
}
