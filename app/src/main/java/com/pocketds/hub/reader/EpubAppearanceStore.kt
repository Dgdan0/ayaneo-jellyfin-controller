package com.pocketds.hub.reader

import android.content.Context

/** Shared by every book; retains the existing preference keys for installed users. */
object EpubAppearanceStore {
    fun load(context: Context): EpubReaderPreferences {
        val store = context.getSharedPreferences("epub-reader", 0)
        val value = EpubReaderPreferences(
            theme = runCatching { EpubTheme.valueOf(store.getString("theme", EpubTheme.SEPIA.name)!!) }.getOrDefault(EpubTheme.SEPIA),
            fontFamily = store.getString("fontFamily", "publisher") ?: "publisher",
            fontScale = store.getFloat("fontScale", 1f).coerceIn(.7f, 2f),
            lineHeight = store.getFloat("lineHeight", 1.25f).coerceIn(1f, 2f),
            pageMargins = store.getFloat("pageMargins", 1f).coerceIn(.5f, 2f),
            columns = runCatching { EpubColumns.valueOf(store.getString("columns", EpubColumns.AUTO.name)!!) }.getOrDefault(EpubColumns.AUTO),
            publisherStyles = store.getBoolean("publisherStyles", true), scroll = store.getBoolean("scroll", false),
            textAlignment = store.getString("textAlignment", "start") ?: "start",
            onePagePerScreen = store.getBoolean("onePagePerScreen", false)
        )
        return if (value.onePagePerScreen) EpubLayoutPolicy.selectOnePage(value, true) else value
    }
    fun save(context: Context, value: EpubReaderPreferences) {
        context.getSharedPreferences("epub-reader", 0).edit()
            .putString("theme", value.theme.name).putString("fontFamily", value.fontFamily)
            .putFloat("fontScale", value.fontScale).putFloat("lineHeight", value.lineHeight)
            .putFloat("pageMargins", value.pageMargins).putString("columns", value.columns.name)
            .putBoolean("publisherStyles", value.publisherStyles).putBoolean("scroll", value.scroll)
            .putString("textAlignment", value.textAlignment).putBoolean("onePagePerScreen", value.onePagePerScreen).apply()
    }
}
