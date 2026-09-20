package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.state.ContentMode

object ContentModeSettings {
    private const val KEY_CONTENT_MODE = "content_mode"

    fun get(context: Context): ContentMode =
        ContentMode.fromStored(Prefs.of(context).getString(KEY_CONTENT_MODE, null))

    fun set(context: Context, mode: ContentMode) {
        Prefs.of(context).edit().putString(KEY_CONTENT_MODE, mode.stored).apply()
    }
}
