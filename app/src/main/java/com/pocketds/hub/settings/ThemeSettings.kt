package com.pocketds.hub.settings

import android.content.Context

object ThemeSettings {
    enum class Mode { SYSTEM, LIGHT, DARK }

    private const val KEY_THEME_MODE = "theme_mode"

    fun getMode(context: Context): Mode {
        val name = Prefs.of(context).getString(KEY_THEME_MODE, Mode.SYSTEM.name)
        return runCatching { Mode.valueOf(name ?: Mode.SYSTEM.name) }.getOrDefault(Mode.SYSTEM)
    }

    fun setMode(context: Context, mode: Mode) {
        Prefs.of(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}
