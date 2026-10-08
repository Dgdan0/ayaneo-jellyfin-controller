package com.pocketds.hub.reader

import android.content.Context

/**
 * Where a device keeps what it has applied of the hub's start-overs (#60): a stamp for each book and profile.
 * The hub says, with every read of a book's place, when it last started the book over; a device that has
 * not applied that stamp drops what it kept of the place, and one that has writes with it, so the hub can
 * tell a place made from before the start over (refused) from one made after (kept).
 */
interface ResetLedger {
    fun get(key: String): Long
    fun put(key: String, value: Long)
}

/** The ledger on the device: one small preferences file, beside the others. */
class PrefsResetLedger(context: Context) : ResetLedger {
    private val prefs = context.applicationContext.getSharedPreferences("reading-resets", Context.MODE_PRIVATE)
    override fun get(key: String): Long = prefs.getLong(key, 0)
    override fun put(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
}

class ReadingResets(private val ledger: ResetLedger, private val store: ReadingCheckpointStore) {
    private fun key(scope: String, workId: String) = ReadingCheckpointKey.digest("reset\u0000$scope\u0000$workId")

    /** The start over of [workId] this device has applied, 0 when none. */
    fun seen(scope: String, workId: String): Long = ledger.get(key(scope, workId))

    /**
     * The hub says [workId] was started over at [resetAt]. When that is news, every place this device kept
     * of the book goes (its checkpoints, and with them the outbox), [alsoDrop] forgets the rest (the downloaded
     * copy's place, the listening and the read-along resume, what was marked read), and the stamp is kept.
     * It is done once: a place read after it stays. The stamp is written last, so an interrupted drop is
     * done again. True when it was news.
     */
    fun apply(scope: String, workId: String, resetAt: Long, alsoDrop: () -> Unit = {}): Boolean {
        if (resetAt <= 0 || resetAt <= seen(scope, workId)) return false
        store.dropWork(scope, workId)
        alsoDrop()
        ledger.put(key(scope, workId), resetAt)
        return true
    }

    companion object {
        /**
         * The keys of the place an audiobook kept on this device before the hub kept one (`audiobook_positions`):
         * a part number and a moment, by a digest of the profile, the book and the edition. A book out of its
         * ZIP still keeps it there, so it is part of the place a start over forgets.
         */
        fun legacyAudioKeys(scope: String, workId: String, sourceItemIds: List<String>): List<String> =
            sourceItemIds.distinct().flatMap { sourceItemId ->
                val digest = ReadingCheckpointKey.digest("$scope:$workId:$sourceItemId")
                listOf("$digest:part", "$digest:ms")
            }
    }
}
