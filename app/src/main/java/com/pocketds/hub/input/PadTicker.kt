package com.pocketds.hub.input

import android.view.Choreographer

/**
 * The frame pump behind stick and D-pad repeat.
 *
 * This exists because of one mechanical fact that is invisible until you hit it:
 * **a held stick stops producing events.** Once the thumb parks at full
 * deflection the driver has nothing new to report, so anything that repeats on
 * event arrival moves one item and then sits there until the stick is wiggled.
 * The D-pad is worse -- its hat axes deliver exactly one event on press and one
 * on release, with no `repeatCount` and no OS auto-repeat at all.
 *
 * So repeat is driven from the clock instead. While the router is not idle this
 * calls [PadEventRouter.onTick] once per frame; when it goes idle it stops
 * entirely, so a parked controller costs nothing.
 */
class PadTicker(private val router: PadEventRouter) {

    private val choreographer = Choreographer.getInstance()
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            router.onTick(frameTimeNanos / 1_000_000L)
            if (router.idle()) {
                running = false
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    /** Call after any motion event. Cheap and idempotent when already running. */
    fun ensureRunning() {
        if (running || router.idle()) return
        running = true
        choreographer.postFrameCallback(frame)
    }

    /** Call from onPause: a backgrounded screen must not keep a frame callback alive. */
    fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frame)
    }

    val isRunning: Boolean get() = running
}
