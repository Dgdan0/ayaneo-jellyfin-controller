package com.pocketds.hub.offline

import android.content.Context
import com.pocketds.hub.settings.Prefs
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The library of each download made before manifests carried one, looked up
 * once from the hub and kept, keyed by the movie's or series' Jellyfin id.
 * Downloads made since name their library in the manifest itself.
 */
object OfflineLibraryNames {
    private const val KEY = "offline_library_names"
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun all(context: Context): Map<String, String> =
        Prefs.of(context).getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    fun remember(context: Context, key: String, library: String) {
        if (key.isBlank() || library.isBlank()) return
        val next = all(context) + (key to library)
        Prefs.of(context).edit().putString(KEY, Json.encodeToString(serializer, next)).apply()
    }
}
