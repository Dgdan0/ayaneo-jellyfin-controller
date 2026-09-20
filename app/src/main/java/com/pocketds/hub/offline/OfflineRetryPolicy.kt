package com.pocketds.hub.offline

/**
 * Keeps transient transfer failures from monopolising the serial queue.
 *
 * A download resumes from the verified byte already on disk. After each
 * interruption it yields to the next queued item, then becomes eligible again
 * after this bounded backoff. The configured retry count still controls when
 * an item finally needs manual attention.
 */
object OfflineRetryPolicy {
    private val delaysMillis = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L, 300_000L)

    fun delayMillis(failedAttempts: Int): Long =
        delaysMillis[(failedAttempts - 1).coerceIn(0, delaysMillis.lastIndex)]
}
