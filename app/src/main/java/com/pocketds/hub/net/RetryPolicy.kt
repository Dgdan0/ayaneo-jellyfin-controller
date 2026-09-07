package com.pocketds.hub.net

import kotlin.random.Random

/**
 * Whether and when to try again.
 *
 * Two rules carry the weight, and both are about not making things worse:
 *
 *  * **A rejected credential is never retried.** It cannot start working, and
 *    hammering it burns the whole call budget for nothing -- and on the hub
 *    specifically, five failures in a minute earns this device a 15-minute ban.
 *  * **A non-idempotent call is never retried.** A timeout does not mean it did
 *    not happen. Retrying a request submission that actually succeeded leaves a
 *    duplicate to clean up by hand.
 */
object RetryPolicy {

    const val MAX_ATTEMPTS = 3

    /** Base delays before jitter. Total stays well inside the call timeout. */
    private val BACKOFF_MS = longArrayOf(400L, 1_200L, 3_000L)

    /**
     * @param attempt 1 for the first failure.
     * @return how long to wait, or null to give up.
     */
    fun delayMsFor(
        attempt: Int,
        kind: FailureKind,
        idempotent: Boolean,
        random: Random = Random.Default
    ): Long? {
        if (!idempotent) return null
        if (!kind.isRetryable) return null
        if (attempt < 1 || attempt >= MAX_ATTEMPTS) return null

        val base = BACKOFF_MS[(attempt - 1).coerceIn(BACKOFF_MS.indices)]
        // Jitter so several screens recovering at once do not all hit the hub on
        // the same tick.
        val jitter = random.nextLong(-base / 4, base / 4 + 1)
        return (base + jitter).coerceAtLeast(50L)
    }

    /** The worst-case total spent sleeping, for checking it fits the budget. */
    fun worstCaseDelayMs(): Long = BACKOFF_MS.take(MAX_ATTEMPTS - 1).sum() * 5 / 4
}
