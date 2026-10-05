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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.ReadingAudioChapter
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.settings.ListeningSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An audiobook on the reading-audio player: its parts, where its place is kept,
 * and how to open its screen again.
 */
data class ReadingAudioBook(
    val workId: String,
    val sourceItemId: String,
    val title: String,
    val parts: List<AudiobookPart>,
    /**
     * Where its place is kept on this device alone (`audiobook_positions`): a book
     * taken out of its ZIP, which the hub cannot keep a place for either.
     */
    val positionKey: String,
    /**
     * Where a streamed book's place goes (#19, A4): its `audio` checkpoint, kept
     * here first and sent to the hub by the outbox. Null for a book out of its ZIP.
     */
    val checkpoint: ReadingCheckpointKey? = null,
    /** The hub's tracks in the order played, for the place: their ids and lengths. */
    val tracks: List<ReadingAudioTrack> = emptyList(),
    /** Chapter marks inside the tracks: the Parts sheet and the part steps go through them. */
    val chapters: List<ReadingAudioChapter> = emptyList(),
    /**
     * Reads the manifest again after the hub said the files changed (412): the
     * same book with its new parts, or null when it can no longer be streamed.
     */
    val reload: (suspend () -> ReadingAudioBook?)? = null,
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
    /** Each part's length: a track's from its manifest, a file's once read from it; null while unknown. */
    val partsMs: List<Long?> = emptyList(),
    val speed: Float = 1f,
    val sleep: SleepTimer? = null,
    /** Why it stopped, when the player could not go on (the stream failed, the files changed); blank otherwise. */
    val problem: String = ""
) {
    val partLeftMs: Long get() = Listening.partLeft(positionMs, partMs, speed)
    val bookLeftMs: Long? get() = Listening.bookLeft(part, positionMs, partsMs, speed)

    /** What the Parts sheet lists: the chapters inside the tracks, else the parts. */
    val contents: List<AudiobookContents.Entry> get() =
        book?.let { AudiobookContents.entries(it.parts, partsMs, it.chapters) }.orEmpty()
}

/**
 * The reading-audio player (#16, A1): an audiobook that outlives its screen.
 * [ReadingAudioService] owns the player and its media session, so it plays on
 * with the screen off, answers a headset and the lock screen, and the app can
 * be browsed meanwhile with a mini player in the top bar. This object is how
 * the app talks to it, on the main thread: it loads a book once the service
 * is up, keeps [state], keeps the place every [SAVE_MS] while playing and on
 * every pause, runs the sleep timer with its fade and smart rewind (A2), and
 * reports to [AudioHandoff], so video, narration and an audiobook never play
 * over each other.
 *
 * A streamed book (#19) plays its tracks from the hub through [AudioStreams],
 * the head of the next track fetched ahead; its place goes through the reading
 * outbox to the hub, sent no more often than every [SYNC_MS], and finishing
 * writes the book finished. A 412 says the book's files changed: the manifest
 * is read again and the place kept. A book out of its ZIP keeps its place on
 * this device, as before.
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
    private var prefetchJob: Job? = null
    private var reloading = false
    /** Reloads since the player last got going: a book whose files keep changing stops asking. */
    private var reloadsWithoutPlaying = 0
    private val throttle by lazy { SyncThrottle(SYNC_MS) }
    private val syncNow = Runnable {
        throttle.ran(SystemClock.elapsedRealtime())
        app?.let { runCatching { ReadingProgress.get(it).requestSync() } }
    }

    private class Pending(val book: ReadingAudioBook, val part: Int, val offsetMs: Long, val play: Boolean)

    private fun player(): Player? = service?.player

    /**
     * Puts [book] on the player at [part] and [offsetMs], or, for a book out of
     * its ZIP, at the place this device kept. A book already on the player is
     * left as it is.
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
        // A stream that failed (the network went) is tried again from where it stood.
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            player.prepare()
            problem("")
        }
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

    /**
     * The entry before (from its own start once three seconds in) or the next:
     * a chapter where the hub found chapters inside the tracks, else a part.
     */
    fun part(delta: Int) {
        val player = player() ?: return
        val target = AudiobookContents.step(mutable.value.contents, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), delta) ?: return
        player.seekTo(target.part, target.startMs)
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
        prefetchJob?.cancel()
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
        prefetchJob?.cancel()
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
            MediaItem.Builder().setUri(part.file?.let(Uri::fromFile) ?: Uri.parse(part.uri)).setMediaId("${book.sourceItemId}:$index")
                // A track's bytes are kept under the file's key, not its URL (AudiobookStream.cacheKey).
                .setCustomCacheKey(part.cacheKey.ifEmpty { null })
                .setMediaMetadata(MediaMetadata.Builder().setTitle(book.title).setArtist(part.title)
                    .setTrackNumber(index + 1).setTotalTrackCount(book.parts.size).build())
                .build()
        }, start.part, start.offsetMs)
        val speed = ListeningSettings.speed(context, book.workId)
        player.setPlaybackSpeed(speed)
        player.volume = 1f
        player.prepare()
        if (start.play) player.play()
        // A streamed part's length is its manifest's; a file's is read from it, beside the player.
        val known = book.parts.map { it.durationMs }
        mutable.value = ListeningState(book = book, part = start.part, positionMs = start.offsetMs, speed = speed, partsMs = known)
        lastSave = SystemClock.elapsedRealtime()
        if (known.any { it == null }) scope.launch {
            val lengths = withContext(Dispatchers.IO) { book.parts.map { part -> part.durationMs ?: part.file?.let(::length) } }
            if (mutable.value.book?.isSame(book) == true) mutable.value = mutable.value.copy(partsMs = lengths)
        }
        prefetchNext()
    }

    private fun length(file: java.io.File): Long? = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(file.absolutePath); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            finally { release() }
        }
    }.getOrNull()?.takeIf { it > 0 }

    /**
     * The head of the next streamed track into the cache, a moment after a part
     * starts, so the change of part plays at once even if the network falters.
     */
    private fun prefetchNext() {
        prefetchJob?.cancel()
        val context = app ?: return
        val book = mutable.value.book ?: return
        val next = book.parts.getOrNull((player()?.currentMediaItemIndex ?: return) + 1)?.takeIf { it.streamed } ?: return
        prefetchJob = scope.launch {
            delay(PREFETCH_DELAY_MS)
            val fetched = AudioStreams.prefetch(context, next)
            DebugLog.log("listen", "next part's head ${if (fetched) "fetched" else "not fetched"}")
        }
    }

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
            prefetchNext()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                reloadsWithoutPlaying = 0
                if (mutable.value.problem.isNotEmpty()) problem("")
                publish()
            }
            // Finished: the book is written as finished, and the next listen starts at the beginning.
            if (playbackState == Player.STATE_ENDED) save(completed = true)
        }

        override fun onPlayerError(error: PlaybackException) {
            when (val status = AudioStreams.httpStatus(error)) {
                // The hub says the book's files are not what this list named.
                409, 412 -> reloadStream(status)
                else -> problem(if (status != null) "The audiobook stopped (the hub answered $status) · Play tries again"
                    else "The audiobook stopped · Play tries again")
            }
        }
    }

    /**
     * The book's files changed under the player (#19): its manifest read again
     * and the book put back at the same place, playing if it was. A book that
     * can no longer stream says so; opening it again downloads it whole.
     */
    private fun reloadStream(status: Int) {
        val book = mutable.value.book ?: return
        val player = player() ?: return
        val reload = book.reload
        if (reload == null || reloadsWithoutPlaying >= MAX_RELOADS) {
            problem("This audiobook's files changed. Open it again to listen.")
            return
        }
        if (reloading) return
        reloading = true
        reloadsWithoutPlaying++
        val part = player.currentMediaItemIndex.coerceAtLeast(0)
        val offset = player.currentPosition.coerceAtLeast(0)
        val place = AudioPlace.canonical(book.tracks, part, offset)
        val playing = player.playWhenReady
        DebugLog.log("listen", "the hub answered $status for a track: reading the manifest again")
        scope.launch {
            val fresh = try { reload() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            reloading = false
            if (mutable.value.book?.isSame(book) != true) return@launch
            if (fresh == null) {
                problem("This audiobook can no longer be streamed. Open it again to download it.")
                return@launch
            }
            val (startPart, startMs) = place?.openAt(fresh.tracks) ?: (part to offset)
            load(Pending(fresh, startPart, startMs, playing))
        }
    }

    private fun problem(text: String) {
        if (mutable.value.book == null || mutable.value.problem == text) return
        mutable.value = mutable.value.copy(problem = text)
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

    /**
     * The place. A streamed book's goes to its `audio` checkpoint, here first,
     * and to the hub no more often than every [SYNC_MS] (the last always goes);
     * finishing writes the book finished. A book out of its ZIP keeps it on
     * this device, and finishing starts it again at the beginning.
     */
    private fun save(completed: Boolean = false) {
        val player = player() ?: return
        val book = mutable.value.book ?: return
        val context = app ?: return
        lastSave = SystemClock.elapsedRealtime()
        val key = book.checkpoint
        if (key == null) {
            positions(context).edit()
                .putInt("${book.positionKey}:part", if (completed) 0 else player.currentMediaItemIndex.coerceAtLeast(0))
                .putLong("${book.positionKey}:ms", if (completed) 0 else player.currentPosition.coerceAtLeast(0))
                .apply()
            return
        }
        val place = AudioPlace.canonical(book.tracks, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), completed) ?: return
        try { ReadingProgress.get(context).save(key, place.location(), sync = false) }
        catch (_: Exception) { DebugLog.log("listen", "the listening place could not be kept on this device"); return }
        handler.removeCallbacks(syncNow)
        handler.postDelayed(syncNow, throttle.waitFor(SystemClock.elapsedRealtime()))
    }

    fun positions(context: Context) = context.getSharedPreferences("audiobook_positions", 0)

    /** How often the place is kept while playing. */
    const val SAVE_MS = 15_000L
    /** The hub hears the place no more often than this (#19). */
    const val SYNC_MS = 15_000L
    /** A part plays this long before the next one's head is fetched. */
    private const val PREFETCH_DELAY_MS = 5_000L
    private const val MAX_RELOADS = 2
    private const val TICK_MS = 500L
}
