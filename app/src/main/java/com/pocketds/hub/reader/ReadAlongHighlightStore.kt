package com.pocketds.hub.reader

import android.content.Context
import android.content.SharedPreferences

/**
 * The read-along highlight of each page theme (#66), kept on this device with the reader's other looks (the same
 * `epub-reader` preferences [EpubAppearanceStore] keeps): `readalong.color.<THEME>` and `readalong.trail.<THEME>`. A
 * theme with neither kept is its default, Gold on a light page and Ember on a dark one, the trail at 40%; "Use the
 * default" removes both, so a later change of the defaults reaches it.
 */
object ReadAlongHighlightStore {
    private const val FILE = "epub-reader"
    private fun colorKey(theme: EpubTheme) = "readalong.color.${theme.name}"
    private fun trailKey(theme: EpubTheme) = "readalong.trail.${theme.name}"

    fun load(context: Context): ReadAlongHighlights = decode(context.getSharedPreferences(FILE, 0))

    /** What [store] holds; a colour that is not one of the eight, or a trail out of range, is the theme's default. */
    fun decode(store: SharedPreferences): ReadAlongHighlights {
        val looks = EpubTheme.entries.mapNotNull { theme ->
            val color = ReadAlongColor.of(store.getString(colorKey(theme), null))
            val trail = if (store.contains(trailKey(theme))) store.getInt(trailKey(theme), ReadAlongWordHighlight.DEFAULT_TRAIL) else null
            if (color == null && trail == null) null
            else theme to HighlightLook(color ?: ReadAlongWordHighlight.defaultColor(theme),
                ReadAlongWordHighlight.stepped(trail ?: ReadAlongWordHighlight.DEFAULT_TRAIL))
        }.toMap()
        return ReadAlongHighlights(looks)
    }

    fun save(context: Context, value: ReadAlongHighlights) = encode(context.getSharedPreferences(FILE, 0), value)

    fun encode(store: SharedPreferences, value: ReadAlongHighlights) {
        val edit = store.edit()
        EpubTheme.entries.forEach { theme ->
            val look = value.looks[theme]
            if (look == null) edit.remove(colorKey(theme)).remove(trailKey(theme))
            else edit.putString(colorKey(theme), look.color.id).putInt(trailKey(theme), ReadAlongWordHighlight.stepped(look.trailPercent))
        }
        edit.apply()
    }
}
