package com.pocketds.hub.screens.library

import android.content.Context
import com.pocketds.hub.reader.ReadingCheckpointKey
import com.pocketds.hub.reader.ReadingProgress

/** Last opened format belongs to this Hub connection, selected user and work. */
object ReadingEntryPreferences {
    fun key(identity: String, workId: String): String =
        ReadingCheckpointKey.digest(identity + "\u0000" + workId)

    fun get(context: Context, workId: String): ReadingEntryPreference? {
        val prefs = context.getSharedPreferences("reading-entry", Context.MODE_PRIVATE)
        val prefix = key(ReadingProgress.get(context).session().identity, workId)
        val mode = prefs.getString("$prefix:mode", null)?.let {
            runCatching { ReadingEntryMode.valueOf(it) }.getOrNull()
        } ?: return null
        return ReadingEntryPreference(mode, prefs.getString("$prefix:audio", "").orEmpty())
    }

    fun put(context: Context, workId: String, mode: ReadingEntryMode, audioSourceItemId: String = "") {
        val prefs = context.getSharedPreferences("reading-entry", Context.MODE_PRIVATE)
        val prefix = key(ReadingProgress.get(context).session().identity, workId)
        prefs.edit().putString("$prefix:mode", mode.name)
            .putString("$prefix:audio", audioSourceItemId).apply()
    }
}
