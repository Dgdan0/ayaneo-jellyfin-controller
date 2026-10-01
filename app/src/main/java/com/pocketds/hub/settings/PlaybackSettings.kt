package com.pocketds.hub.settings

import android.content.Context

/** Player preferences kept semantic so a later Settings screen can expose them directly. */
object PlaybackSettings {
    private const val KEY_SEEK_SECONDS = "player_seek_seconds"
    private const val DEFAULT_SEEK_SECONDS = 10
    private val allowedSeekSeconds = setOf(5, 10, 15, 30)
    private const val KEY_CONVERT_HDR = "player_convert_hdr"

    fun seekSeconds(context: Context): Int =
        Prefs.of(context).getInt(KEY_SEEK_SECONDS, DEFAULT_SEEK_SECONDS)
            .takeIf(allowedSeekSeconds::contains) ?: DEFAULT_SEEK_SECONDS

    fun setSeekSeconds(context: Context, seconds: Int) {
        require(seconds in allowedSeekSeconds) { "seek seconds must be one of $allowedSeekSeconds" }
        Prefs.of(context).edit().putInt(KEY_SEEK_SECONDS, seconds).apply()
    }

    /**
     * Whether HDR video is decoded as SDR. On by default: this panel shows HDR
     * dim (see HdrOutput), but a title can look flat after conversion, so it can
     * be turned off. Read when each video decoder is created, so a change applies
     * from the next video.
     */
    fun convertHdr(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_CONVERT_HDR, true)

    fun setConvertHdr(context: Context, convert: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_CONVERT_HDR, convert).apply()
    }
}
