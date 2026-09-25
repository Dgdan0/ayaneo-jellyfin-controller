package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.state.ContentMode

object DomainPreferences {
    private fun key(context: Context, mode: ContentMode, setting: String) = "$setting:${PreferenceScope.key(HubSettings.baseUrl(context), HubSettings.userId(context), mode)}"
    fun accent(context: Context, mode: ContentMode) = AccentPreset.fromStored(Prefs.of(context).getString(key(context,mode,"accent"),null))
    fun setAccent(context: Context, mode: ContentMode, preset: AccentPreset) { Prefs.of(context).edit().putString(key(context,mode,"accent"),preset.id).apply() }
    fun sort(context: Context, mode: ContentMode, fields: List<String>, fallback: String) =
        SortPreference.decode(Prefs.of(context).getString(key(context,mode,"sort"),null),fallback).supported(fields,fallback)
    fun setSort(context: Context, mode: ContentMode, sort: SortPreference) { Prefs.of(context).edit().putString(key(context,mode,"sort"),sort.encode()).apply() }
}
