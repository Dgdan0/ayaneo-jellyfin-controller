package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.reader.HighlightColor

/** The colour the next highlight is made in (#62): the last one picked, kept on this device for every book. */
object HighlightSettings {
    private const val KEY_COLOR = "reader_highlight_color"

    fun color(context: Context): HighlightColor = HighlightColor.orDefault(Prefs.of(context).getString(KEY_COLOR, null))

    fun setColor(context: Context, color: HighlightColor) {
        Prefs.of(context).edit().putString(KEY_COLOR, color.id).apply()
    }
}
