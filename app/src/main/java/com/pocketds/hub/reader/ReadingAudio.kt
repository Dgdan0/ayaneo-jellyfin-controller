package com.pocketds.hub.reader

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.settings.ListeningSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An audiobook on the reading-audio player: its parts on this device, and how to open its screen again. */
data class ReadingAudioBook(
    val workId: String,
    val sourceItemId: String,
    val title: String,
    val parts: List<AudiobookPart>,
    /** Where its place is kept on this device (the `audiobook_positions` key). */
    val positionKey: String,
    /** Opens its screen again, from the mini player. */
    val reopen: (() -> Screen)? = null
) {
    fun isSame(other: ReadingAudioBook?) = other != null && other.workId == workId && other.sourceItemId == sourceItemId
}

/** What the reading-audio player is doing, for its screen and the mini player. */
data class ListeningState(
    val book: ReadingAudioBook? = null,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val part: Int = 0,
    val positionMs: Long = 0,
    val partMs: Long = 0,
    /** Each part's length once read from its file; null while unknown. */
    val partsMs: List<Long?> = emptyList(),
    val speed: Float = 1f,
    val sleep: SleepTimer? = null
) {
    val partLeftMs: Long get() = Listening.partLeft(positionMs, partMs, speed)
    val bookLeftMs: Long? get() = Listening.bookLeft(part, positionMs, partsMs, speed)
}

/**
 * The reading-audio player (#16, A1): an audiobook that outlives its screen.
 * [ReadingAudioService] owns the player and its media session, so it plays on
 * with the screen off, answers a headset and the lock screen, and the app can
 * be browsed meanwhile with a mini player in the top bar. This object is how
 * the app talks to it, on the main thread: it loads a book once the service
 * is up, keeps [state], saves the place every [SAVE_MS] while playing (on this
 * device only), runs the sleep timer with its fade and smart rewind (A2), and
 * reports to [AudioHandoff], so video, narration and an audiobook never play
 * over each other.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object ReadingAudio {
    private val mutable = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = mutable

    private var service: ReadingAudioService? = null
    private var waiting: Pending? = null
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastTick = 0L
    private var lastSave = 0L
    private val pauser: () -> Unit = { player()?.pause() }

    private class Pending(val book: ReadingAudioBook, val part: Int, val offsetMs: Long, val play: Boolean)

    private fun player(): Player? = service?.player

    /**
     * Puts [book] on the player at its saved place, or [part] and [offsetMs]
     * when given. A book already on the player is left as it is.
     */
    fun open(context: Context, book: ReadingAudioBook, part: Int? = null, offsetMs: Long? = null, play: Boolean = false) {
        app = context.applicationContext
        if (book.isSame(mutable.value.book) && service != null) return
        val saved = positions(context)
        val start = Pending(book,
            (part ?: saved.getInt("${book.positionKey}:part", 0)).coerceIn(0, (book.parts.size - 1).coerceAtLeast(0)),
            (offsetMs ?: saved.getLong("${book.positionKey}:ms", 0)).coerceAtLeast(0), play)
        val running = service
        if (running == null) {
            waiting = start
            context.startService(Intent(context, ReadingAudioService::class.java))
        } else load(start)
    }

    fun toggle() {
        val player = player() ?: return
        if (player.isPlaying) player.pause() else play()
    }

    fun play() {
        val player = player() ?: return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        player.play()
    }

    fun pause() { player()?.pause() }

    /** A jump within the book, across parts, by [deltaMs] of recording. */
    fun seekBy(deltaMs: Long) {
        val player = player() ?: return
        val durations = mutable.value.partsMs
        var part = player.currentMediaItemIndex
        var offset = player.currentPosition + deltaMs
        while (offset < 0 && part > 0) { part--; offset += durations.getOrNull(part) ?: 0L }
        while (part < player.mediaItemCount - 1) {
            val length = durations.getOrNull(part)?.takeIf { it > 0 } ?: player.duration.takeIf { part == player.currentMediaItemIndex && it > 0 } ?: break
            if (offset < length) break
            offset -= length; part++
        }
        player.seekTo(part, offset.coerceAtLeast(0))
        save()
        publish()
    }

    fun seekTo(part: Int, offsetMs: Long) {
        val player = player() ?: return
        player.seekTo(part.coerceIn(0, (player.mediaItemCount - 1).coerceAtLeast(0)), offsetMs.coerceAtLeast(0))
        save()
        publish()
    }

    /** The part before (from its start once three seconds in) or the next. */
    fun part(delta: Int) {
        val player = player() ?: return
        if (delta < 0) { if (player.currentPosition > 3_000) player.seekTo(player.currentMediaItemIndex, 0) else player.seekToPreviousMediaItem() }
        else player.seekToNextMediaItem()
        save()
        publish()
    }

    fun setSpeed(speed: Float) {
        val book = mutable.value.book ?: return
        val value = Listening.clampSpeed(speed)
        app?.let { ListeningSettings.setSpeed(it, book.workId, value) }
        player()?.setPlaybackSpeed(value)
        publish()
    }

    /** Sets the sleep timer, or [choice] null to cancel it. */
    fun setSleep(choice: SleepChoice?) = runSleep(choice?.let { SleepTimer.start(it, mutable.value.partLeftMs) })

    /** Runs [timer] as it stands (a fixture starts one seconds from its end). */
    internal fun runSleep(timer: SleepTimer?) {
        player()?.volume = 1f
        lastTick = SystemClock.elapsedRealtime()
        publish(timer)
        if (timer != null) tickSoon()
    }

    /** Any button while the sleep timer fades keeps you listening. */
    fun touched() {
        val now = mutable.value
        val timer = now.sleep ?: return
        if (!timer.fading) return
        val next = now.partsMs.getOrNull(now.part + 1)?.let { Listening.heard(it, now.speed) }
        player()?.volume = 1f
        publish(timer.extended(now.partLeftMs, next))
    }

    /** Takes the book off the player, its place kept, and lets the service go. */
    fun stop() {
        save()
        handler.removeCallbacks(tick)
        waiting = null
        // The book goes first: clearing the player reports a move to no part at 0:00,
        // which would otherwise be saved over the place just kept.
        mutable.value = ListeningState()
        player()?.let { it.stop(); it.clearMediaItems() }
        service?.stopSelf()
    }

    // ---------------------------------------------------------- the service's side

    internal fun attach(owner: ReadingAudioService) {
        service = owner
        app = owner.applicationContext
        owner.player.addListener(listener)
        AudioHandoff.register(AudioSource.AUDIOBOOK, pauser)
        waiting?.let { waiting = null; load(it) }
    }

    internal fun detach(owner: ReadingAudioService) {
        if (service !== owner) return
        save()
        owner.player.removeListener(listener)
        AudioHandoff.unregister(AudioSource.AUDIOBOOK, pauser)
        handler.removeCallbacks(tick)
        service = null
        mutable.value = ListeningState()
    }

    private fun load(start: Pending) {
        val player = player() ?: return
        val context = app ?: return
        val book = start.book
        // Another book on the player comes off first, its place kept: the new parts
        // going on report a move, which would otherwise be saved as the old book's place.
        if (mutable.value.book != null) {
            player.pause()
            save()
            mutable.value = ListeningState()
        }
        player.setMediaItems(book.parts.mapIndexed { index, part ->
            MediaItem.Builder().setUri(Uri.fromFile(part.file)).setMediaId("${book.sourceItemId}:$index")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(part.title)
                    .setTrackNumber(index + 1).setTotalTrackCount(book.parts.size).build())
                .build()
        }, start.part, start.offsetMs)
        val speed = ListeningSettings.speed(context, book.workId)
        player.setPlaybackSpeed(speed)
        player.volume = 1f
        player.prepare()
        if (start.play) player.play()
        mutable.value = ListeningState(book = book, part = start.part, positionMs = start.offsetMs, speed = speed,
            partsMs = List(book.parts.size) { null })
        lastSave = SystemClock.elapsedRealtime()
        // The parts' lengths, for the time left in the book; read once, beside the player.
        scope.launch {
            val lengths = withContext(Dispatchers.IO) { book.parts.map { length(it.file) } }
            if (mutable.value.book?.isSame(book) == true) mutable.value = mutable.value.copy(partsMs = lengths)
        }
    }

    private fun length(file: java.io.File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(file.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            finally { release() }
        }
    }.getOrNull()?.takeIf { it > 0 }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val context = app
            if (isPlaying && context != null) AudioHandoff.started(context, AudioSource.AUDIOBOOK)
            if (!isPlaying) { AudioHandoff.stopped(AudioSource.AUDIOBOOK); save() }
            lastTick = SystemClock.elapsedRealtime()
            publish()
            if (isPlaying) tickSoon()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val now = mutable.value
            val player = player()
            val timer = now.sleep
            if (player != null && timer != null && timer.endsWithPart && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The part ran out under an end-of-part timer: stop, back over what faded at its end.
                val previous = (player.currentMediaItemIndex - 1).coerceAtLeast(0)
                val length = now.partsMs.getOrNull(previous) ?: now.partMs
                player.pause()
                player.seekTo(previous, SmartRewind.afterSleep(length))
                player.volume = 1f
                publish(null)
                save()
                return
            }
            publish(timer?.partChanged(Listening.heard(now.partsMs.getOrNull(player?.currentMediaItemIndex ?: 0) ?: 0L, now.speed)))
            save()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) publish()
            // Finished: the next listen starts at the beginning.
            if (playbackState == Player.STATE_ENDED) save(completed = true)
        }
    }

    private fun tickSoon() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val player = player() ?: return
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastTick).coerceAtLeast(0)
            lastTick = now
            var timer = mutable.value.sleep
            if (timer != null && player.isPlaying) {
                val current = state(player)
                timer = timer.tick(elapsed, current.partLeftMs)
                if (timer.runsOut) {
                    // Asleep: pause, then back over what faded so it is heard again.
                    player.pause()
                    player.seekTo(player.currentMediaItemIndex, SmartRewind.afterSleep(player.currentPosition))
                    player.volume = 1f
                    timer = null
                    save()
                } else player.volume = timer.volume
            }
            publish(timer)
            if (player.isPlaying && now - lastSave >= SAVE_MS) save()
            if (player.isPlaying || timer != null) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun state(player: Player): ListeningState = mutable.value.copy(
        playing = player.isPlaying,
        ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
        part = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        partMs = player.duration.takeIf { it > 0 } ?: mutable.value.partsMs.getOrNull(player.currentMediaItemIndex) ?: 0L,
        speed = player.playbackParameters.speed
    )

    private fun publish(sleep: SleepTimer? = mutable.value.sleep) {
        val player = player() ?: return
        if (mutable.value.book == null) return
        mutable.value = state(player).copy(sleep = sleep)
    }

    /** The place, on this device only: an audiobook's progress has no server to go to yet (A4). */
    private fun save(completed: Boolean = false) {
        val player = player() ?: return
        val book = mutable.value.book ?: return
        val context = app ?: return
        lastSave = SystemClock.elapsedRealtime()
        positions(context).edit()
            .putInt("${book.positionKey}:part", if (completed) 0 else player.currentMediaItemIndex.coerceAtLeast(0))
            .putLong("${book.positionKey}:ms", if (completed) 0 else player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun positions(context: Context) = context.getSharedPreferences("audiobook_positions", 0)

    /** How often the place is saved while playing. */
    const val SAVE_MS = 10_000L
    private const val TICK_MS = 500L
}
