package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.playback.PlaybackService
import java.util.EnumMap

/**
 * [AudioArbiter]'s hands (#16, A1). Each player reports here when it starts
 * and stops, on the main thread; a start pauses whatever else was playing.
 * The reading players register how they pause; video is paused through its
 * own service's existing call, so the video path only reports.
 */
object AudioHandoff {
    private val arbiter = AudioArbiter()
    private val pausers = EnumMap<AudioSource, () -> Unit>(AudioSource::class.java)
    private var app: Context? = null

    /** What is sounding now. */
    val sounding: Set<AudioSource> get() = arbiter.sounding

    fun register(source: AudioSource, pause: () -> Unit) {
        pausers[source] = pause
    }

    /** Only the pauser that registered takes itself away, so a newer player's stays. */
    fun unregister(source: AudioSource, pause: () -> Unit) {
        if (pausers[source] === pause) pausers.remove(source)
        arbiter.stop(source)
    }

    fun started(context: Context, source: AudioSource) {
        app = context.applicationContext
        arbiter.start(source).forEach(::pause)
    }

    fun stopped(source: AudioSource) = arbiter.stop(source)

    private fun pause(source: AudioSource) {
        if (source == AudioSource.VIDEO) app?.let(PlaybackService::pause) else pausers[source]?.invoke()
    }
}
