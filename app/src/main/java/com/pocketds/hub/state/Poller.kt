package com.pocketds.hub.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one poll found. */
data class PollOutcome(val ok: Boolean, val active: Boolean = false)

/**
 * The one polling loop, run on [PollSchedule]'s rules.
 *
 * Each screen used to write its own. Media detail rescheduled only after a
 * successful load, so one failed refresh froze its pipeline strip for good;
 * Notifications and the header badge polled at a fixed rate with no backoff.
 * Here a failure backs off and tries again, success resets it, and a screen
 * that is not visible stops.
 */
class Poller(
    private val cadence: PollCadence,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private var job: Job? = null
    private var restart: (() -> Unit)? = null
    private var active = false
    private var settleUntil = 0L

    var consecutiveFailures = 0
        private set

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Polls now and then on schedule, replacing any loop already running.
     * [fetch] returns null to skip a round, for instance while an action the
     * screen started is still in flight.
     */
    fun start(scope: CoroutineScope, visible: () -> Boolean, fetch: suspend () -> PollOutcome?) {
        restart = { start(scope, visible, fetch) }
        job?.cancel()
        consecutiveFailures = 0
        job = scope.launch {
            while (true) {
                fetch()?.let { outcome ->
                    if (outcome.ok) {
                        consecutiveFailures = 0
                        active = outcome.active
                    } else {
                        consecutiveFailures++
                    }
                }
                val wait = PollSchedule.nextDelayMs(
                    visible(), active, consecutiveFailures, now() < settleUntil, cadence
                ) ?: break
                delay(wait)
            }
        }
    }

    /** Asks again now instead of waiting out the interval. */
    fun pollNow() {
        restart?.invoke()
    }

    /** Keeps polling fast for a few seconds after the user acted. */
    fun settle() {
        settleUntil = now() + PollSchedule.SETTLE_MS
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
