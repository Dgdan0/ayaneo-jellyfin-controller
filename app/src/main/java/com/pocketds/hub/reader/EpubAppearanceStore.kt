package com.pocketds.hub.reader

import android.content.Context
import android.content.SharedPreferences

/** Shared by every book; retains the existing preference keys for installed users. */
object EpubAppearanceStore {
    fun load(context: Context): EpubReaderPreferences = decode(context.getSharedPreferences("epub-reader", 0))

    /**
     * What [store] holds. A key that is not there falls back to [EpubReaderPreferences]'s own default
     * (read from one instance, so a new default cannot be missed here), and one that is there is kept:
     * a device whose look was already changed does not move when the defaults do (#42, Part 3).
     */
    fun decode(store: SharedPreferences): EpubReaderPreferences {
        val defaults = EpubReaderPreferences()
        val value = EpubReaderPreferences(
            theme = runCatching { EpubTheme.valueOf(store.getString("theme", defaults.theme.name)!!) }.getOrDefault(defaults.theme),
            fontFamily = store.getString("fontFamily", defaults.fontFamily) ?: defaults.fontFamily,
            fontScale = store.getFloat("fontScale", defaults.fontScale).coerceIn(.7f, 2f),
            lineHeight = store.getFloat("lineHeight", defaults.lineHeight).coerceIn(1f, 2f),
            pageMargins = store.getFloat("pageMargins", defaults.pageMargins).coerceIn(.5f, 2f),
            columns = runCatching { EpubColumns.valueOf(store.getString("columns", defaults.columns.name)!!) }.getOrDefault(defaults.columns),
            publisherStyles = store.getBoolean("publisherStyles", defaults.publisherStyles),
            scroll = store.getBoolean("scroll", defaults.scroll),
            textAlignment = store.getString("textAlignment", defaults.textAlignment) ?: defaults.textAlignment,
            onePagePerScreen = store.getBoolean("onePagePerScreen", defaults.onePagePerScreen),
            hyphenation = store.getBoolean("hyphenation", defaults.hyphenation)
        )
        return if (value.onePagePerScreen) EpubLayoutPolicy.selectOnePage(value, true) else value
    }

    fun save(context: Context, value: EpubReaderPreferences) {
        context.getSharedPreferences("epub-reader", 0).edit()
            .putString("theme", value.theme.name).putString("fontFamily", value.fontFamily)
            .putFloat("fontScale", value.fontScale).putFloat("lineHeight", value.lineHeight)
            .putFloat("pageMargins", value.pageMargins).putString("columns", value.columns.name)
            .putBoolean("publisherStyles", value.publisherStyles).putBoolean("scroll", value.scroll)
            .putString("textAlignment", value.textAlignment).putBoolean("onePagePerScreen", value.onePagePerScreen)
            .putBoolean("hyphenation", value.hyphenation).apply()
    }
}
