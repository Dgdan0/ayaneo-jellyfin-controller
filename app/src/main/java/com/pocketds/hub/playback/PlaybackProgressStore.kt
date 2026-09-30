package com.pocketds.hub.playback

import android.content.Context
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.PlaybackItem
import com.pocketds.hub.settings.Prefs

/**
 * Bridges the brief gap between leaving the player and Jellyfin applying the
 * final stopped event. Jellyfin remains authoritative after this short window.
 */
object PlaybackProgressStore {
    fun remember(
        context: Context,
        userId: String,
        item: PlaybackItem,
        positionMillis: Long,
        durationMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (item.id.isEmpty() || durationMillis <= 0) return
        val prefix = prefix(userId)
        Prefs.of(context).edit()
            .putString("${prefix}item", item.id)
            .putLong("${prefix}position", positionMillis.coerceIn(0, durationMillis))
            .putLong("${prefix}duration", durationMillis)
            .putLong("${prefix}updated", nowMillis)
            .apply()
    }

    fun resumePosition(
        context: Context,
        userId: String,
        itemId: String,
        startMode: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Long = read(context, userId)?.resumePosition(itemId, startMode, nowMillis) ?: 0L

    fun applyTo(
        context: Context,
        userId: String,
        item: LibraryItem,
        nowMillis: Long = System.currentTimeMillis()
    ): LibraryItem {
        val checkpoint = read(context, userId) ?: return item
        if (checkpoint.isComplete(item.id, nowMillis)) {
            return item.copy(played = true, positionSeconds = 0, progress = 0.0)
        }
        val position = checkpoint.resumePosition(item.id, "resume", nowMillis)
        if (position == 0L || item.played) return item
        return item.copy(
            positionSeconds = (position / 1_000L).toInt(),
            progress = (position.toDouble() / checkpoint.durationMillis.toDouble()).coerceIn(0.0, 1.0)
        )
    }

    /**
     * Drops the checkpoint once the user sets watched state by hand. The
     * choice is newer than anything the player saw, and keeping it overrode
     * the server's reply: "Mark unwatched" on a just-finished episode still
     * showed it watched for two minutes.
     */
    fun forget(context: Context, userId: String) {
        val prefix = prefix(userId)
        Prefs.of(context).edit()
            .remove("${prefix}item")
            .remove("${prefix}position")
            .remove("${prefix}duration")
            .remove("${prefix}updated")
            .apply()
    }

    /** True only for a fresh checkpoint that reached Jellyfin's completion window. */
    fun isRecentlyComplete(
        context: Context,
        userId: String,
        itemId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = read(context, userId)?.isComplete(itemId, nowMillis) == true

    private fun read(context: Context, userId: String): PlaybackCheckpoint? {
        val prefix = prefix(userId)
        val prefs = Prefs.of(context)
        val itemId = prefs.getString("${prefix}item", "").orEmpty()
        if (itemId.isEmpty()) return null
        return PlaybackCheckpoint(
            itemId = itemId,
            positionMillis = prefs.getLong("${prefix}position", 0L),
            durationMillis = prefs.getLong("${prefix}duration", 0L),
            updatedAtMillis = prefs.getLong("${prefix}updated", 0L)
        )
    }

    private fun prefix(userId: String): String =
        "playback_checkpoint_${userId.hashCode().toUInt().toString(16)}_"
}

internal data class PlaybackCheckpoint(
    val itemId: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAtMillis: Long
) {
    fun isComplete(itemId: String, nowMillis: Long): Boolean =
        this.itemId == itemId && isFresh(nowMillis) && durationMillis > 0 &&
            ResumeRules.isFinished(positionMillis, durationMillis)

    fun resumePosition(itemId: String, startMode: String, nowMillis: Long): Long {
        if (startMode != "resume" || this.itemId != itemId) return 0L
        if (!isFresh(nowMillis)) return 0L
        return ResumeRules.resumePosition(positionMillis, durationMillis)
    }

    private fun isFresh(nowMillis: Long): Boolean =
        updatedAtMillis > 0 && nowMillis >= updatedAtMillis && nowMillis - updatedAtMillis <= MAX_AGE_MILLIS

    private companion object {
        const val MAX_AGE_MILLIS = 2 * 60 * 1_000L
    }
}
