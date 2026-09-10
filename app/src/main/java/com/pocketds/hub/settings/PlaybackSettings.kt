package com.pocketds.hub.settings

import android.content.Context

/** Player preferences kept semantic so a later Settings screen can expose them directly. */
object PlaybackSettings {
    private const val KEY_SEEK_SECONDS = "player_seek_seconds"
    private const val DEFAULT_SEEK_SECONDS = 10
    private val allowedSeekSeconds = setOf(5, 10, 15, 30)

    fun seekSeconds(context: Context): Int =
        Prefs.of(context).getInt(KEY_SEEK_SECONDS, DEFAULT_SEEK_SECONDS)
            .takeIf(allowedSeekSeconds::contains) ?: DEFAULT_SEEK_SECONDS

    fun setSeekSeconds(context: Context, seconds: Int) {
        require(seconds in allowedSeekSeconds) { "seek seconds must be one of $allowedSeekSeconds" }
        Prefs.of(context).edit().putInt(KEY_SEEK_SECONDS, seconds).apply()
    }
}
