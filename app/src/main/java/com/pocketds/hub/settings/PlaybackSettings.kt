package com.pocketds.hub.settings

import android.content.Context

/** Player preferences kept semantic so a later Settings screen can expose them directly. */
object PlaybackSettings {
    private const val KEY_SEEK_SECONDS = "player_seek_seconds"
    private const val DEFAULT_SEEK_SECONDS = 10
    private val allowedSeekSeconds = setOf(5, 10, 15, 30)
    private const val KEY_CONVERT_HDR = "player_convert_hdr"
    private const val KEY_NEXT_TIMING = "player_next_timing"
    private const val KEY_AUTO_SKIP_INTRO = "player_auto_skip_intro"

    /** Settings › Playback › Show the next episode. */
    fun nextTiming(context: Context): com.pocketds.hub.playback.NextEpisodeTiming =
        com.pocketds.hub.playback.NextEpisodeTiming.entries.firstOrNull { it.name == Prefs.of(context).getString(KEY_NEXT_TIMING, null) }
            ?: com.pocketds.hub.playback.NextEpisodeTiming.CREDITS

    fun setNextTiming(context: Context, timing: com.pocketds.hub.playback.NextEpisodeTiming) {
        Prefs.of(context).edit().putString(KEY_NEXT_TIMING, timing.name).apply()
    }

    /** Off by default: a Skip intro button appears instead. */
    fun autoSkipIntro(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_AUTO_SKIP_INTRO, false)

    fun setAutoSkipIntro(context: Context, skip: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_AUTO_SKIP_INTRO, skip).apply()
    }

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
