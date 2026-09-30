package com.pocketds.hub.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One request at a time, with a busy state that cannot outlive the request.
 *
 * Screens used to write `busy = true`, launch, and set `busy = false` after the
 * await. When the screen hid, onHide cancelled the request, the line after the
 * await never ran, and the flag stayed true for good: Discover's Request button
 * read "Requesting…" forever, the Transfers poll loop never fetched again, and
 * the hub-connection Save button stayed disabled. Some screens patched it by
 * resetting their flag in onHide, which only works while every screen
 * remembers to.
 *
 * Here "busy" is simply "the job is still active", so cancelling the scope --
 * or the job -- makes the slot idle by construction.
 */
class JobSlot {

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Starts [block] unless the slot is already busy.
     *
     * @param onIdle runs once when the job ends, whether it finished, failed or
     *   was cancelled -- the place to re-enable a button or redraw the hints.
     * @return false when a job was already running and nothing was started.
     */
    fun launch(
        scope: CoroutineScope,
        onIdle: () -> Unit = {},
        block: suspend CoroutineScope.() -> Unit
    ): Boolean {
        if (isBusy) return false
        // Recorded before it runs: screens use Dispatchers.Main.immediate, where
        // a plain launch executes the block before returning, so a hint redraw
        // at the top of the block would still have seen the slot as idle.
        val started = scope.launch(start = CoroutineStart.LAZY, block = block)
        job = started
        started.invokeOnCompletion { onIdle() }
        started.start()
        return true
    }

    fun cancel() {
        job?.cancel()
    }
}
