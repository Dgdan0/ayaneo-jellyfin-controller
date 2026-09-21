package com.pocketds.hub.reader

import android.content.Context

/** Local-only persistence for the R1 fixture shell. Real progress arrives in R3. */
object ReaderLabProgressStore {
    private const val PREFS = "reader_lab_progress"

    fun load(context: Context, userId: String, profile: ReaderProfile): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(key(userId, profile), 0f)
            .toDouble()
            .coerceIn(0.0, 1.0)

    fun remember(context: Context, userId: String, profile: ReaderProfile, locator: ReaderLocator) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(key(userId, profile), locator.progression.toFloat())
            .apply()
    }

    private fun key(userId: String, profile: ReaderProfile): String =
        "${userId.ifBlank { "default" }}:${profile.name}"
}
