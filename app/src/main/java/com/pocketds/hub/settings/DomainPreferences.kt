package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.state.ContentMode

object DomainPreferences {
    private fun key(context: Context, mode: ContentMode, setting: String) = "$setting:${PreferenceScope.key(HubSettings.baseUrl(context), HubSettings.userId(context), mode)}"
    fun accent(context: Context, mode: ContentMode) =
        AccentPreset.fromStored(Prefs.of(context).getString(key(context,mode,"accent"),null), AccentPreset.defaultFor(mode))
    fun setAccent(context: Context, mode: ContentMode, preset: AccentPreset) { Prefs.of(context).edit().putString(key(context,mode,"accent"),preset.id).apply() }
    fun sort(context: Context, mode: ContentMode, fields: List<String>, fallback: String) =
        SortPreference.decode(Prefs.of(context).getString(key(context,mode,"sort"),null),fallback).supported(fields,fallback)
    fun setSort(context: Context, mode: ContentMode, sort: SortPreference) { Prefs.of(context).edit().putString(key(context,mode,"sort"),sort.encode()).apply() }
    /** Books Library: series, authors, or every book on its own. */
    fun readingView(context: Context): String? = Prefs.of(context).getString(key(context,ContentMode.BOOKS,"reading_view"),null)
    fun setReadingView(context: Context, view: String) { Prefs.of(context).edit().putString(key(context,ContentMode.BOOKS,"reading_view"),view).apply() }
    /** The order of the every-book view, kept apart from the series order. */
    fun bookSort(context: Context, fields: List<String>, fallback: String) =
        SortPreference.decode(Prefs.of(context).getString(key(context,ContentMode.BOOKS,"book_sort"),null),fallback).supported(fields,fallback)
    fun setBookSort(context: Context, sort: SortPreference) { Prefs.of(context).edit().putString(key(context,ContentMode.BOOKS,"book_sort"),sort.encode()).apply() }
}
