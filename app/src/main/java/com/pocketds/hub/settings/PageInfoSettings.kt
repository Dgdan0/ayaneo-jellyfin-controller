package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.reader.PageInfoChoice
import com.pocketds.hub.reader.PageInfoCorner

/**
 * Which of Kindle's corners a book's page shows (#42), kept on this device for
 * every book: the clock, what the bottom left says, the percentage.
 */
object PageInfoSettings {
    private const val KEY_CLOCK = "reader_page_info_clock"
    private const val KEY_CORNER = "reader_page_info_corner"
    private const val KEY_PERCENTAGE = "reader_page_info_percentage"
    private const val KEY_TITLE = "reader_page_info_title"

    fun load(context: Context): PageInfoChoice {
        val prefs = Prefs.of(context)
        val defaults = PageInfoChoice()
        return PageInfoChoice(
            clock = prefs.getBoolean(KEY_CLOCK, defaults.clock),
            corner = if (prefs.contains(KEY_CORNER)) PageInfoCorner.named(prefs.getString(KEY_CORNER, null)) else defaults.corner,
            percentage = prefs.getBoolean(KEY_PERCENTAGE, defaults.percentage),
            title = prefs.getBoolean(KEY_TITLE, defaults.title)
        )
    }

    fun save(context: Context, value: PageInfoChoice) {
        Prefs.of(context).edit()
            .putBoolean(KEY_CLOCK, value.clock)
            .putString(KEY_CORNER, value.corner.name)
            .putBoolean(KEY_PERCENTAGE, value.percentage)
            .putBoolean(KEY_TITLE, value.title)
            .apply()
    }
}
