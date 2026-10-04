package com.pocketds.hub.settings

import android.content.Context

/**
 * The app's look (#11). Glass is the default: the page takes the colour of the
 * artwork in focus, under tinted glass panels (GLASS_PLAN.md). Classic is the
 * app as it was before Glass, kept only as a fallback while Glass is built and
 * removed once #11 is finished.
 *
 * Pure, so the rules (an unknown or missing value is Glass; Glass is dark
 * whatever the theme says) are pinned by JVM tests.
 */
enum class Look(val stored: String, val label: String) {
    GLASS("glass", "Glass"),
    CLASSIC("classic", "Classic");

    /**
     * Whether the views are built dark. Glass always is: its page is the
     * artwork, darkened. Classic follows the Theme setting, and [systemNight]
     * when that is "Match the system".
     */
    fun dark(theme: ThemeSettings.Mode, systemNight: Boolean): Boolean = when {
        this == GLASS -> true
        theme == ThemeSettings.Mode.LIGHT -> false
        theme == ThemeSettings.Mode.DARK -> true
        else -> systemNight
    }

    companion object {
        fun fromStored(value: String?): Look = entries.firstOrNull { it.stored == value } ?: GLASS
    }
}

object LookSettings {
    private const val KEY_LOOK = "look"

    fun get(context: Context): Look = Look.fromStored(Prefs.of(context).getString(KEY_LOOK, null))

    fun set(context: Context, look: Look) {
        Prefs.of(context).edit().putString(KEY_LOOK, look.stored).apply()
    }
}
