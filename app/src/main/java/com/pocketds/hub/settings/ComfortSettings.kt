package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.ui.ScreenComfort

/**
 * The readers' Comfort (#16, X3), kept for every reader and every book: dim a
 * comic at night and the next book opens just as dim. The player's brightness
 * drag stays its own, for one video.
 */
object ComfortSettings {
    private const val KEY_BRIGHTNESS = "reader_comfort_brightness"
    private const val KEY_WARMTH = "reader_comfort_warmth"
    private const val KEY_BLACK_PAGE = "reader_comfort_black_page"
    private const val KEY_AWAKE = "reader_comfort_awake_narrating"

    fun load(context: Context): ScreenComfort {
        val prefs = Prefs.of(context)
        return ScreenComfort(
            brightness = prefs.getFloat(KEY_BRIGHTNESS, 1f).coerceIn(ScreenComfort.MIN_BRIGHTNESS, 1f),
            warmth = prefs.getFloat(KEY_WARMTH, 0f).coerceIn(0f, 1f),
            blackPage = prefs.getBoolean(KEY_BLACK_PAGE, false),
            awakeWhileNarrating = prefs.getBoolean(KEY_AWAKE, true)
        )
    }

    fun save(context: Context, comfort: ScreenComfort) {
        Prefs.of(context).edit()
            .putFloat(KEY_BRIGHTNESS, comfort.brightness)
            .putFloat(KEY_WARMTH, comfort.warmth)
            .putBoolean(KEY_BLACK_PAGE, comfort.blackPage)
            .putBoolean(KEY_AWAKE, comfort.awakeWhileNarrating)
            .apply()
    }
}
