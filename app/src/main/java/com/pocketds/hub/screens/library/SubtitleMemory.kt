package com.pocketds.hub.screens.library

import android.content.Context
import com.pocketds.hub.model.SubtitleRecord
import com.pocketds.hub.settings.HubSettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Personal feedback belongs to this device, Hub and selected profile. */
class SubtitleMemory(context: Context, itemId: String) {
    private val prefs = context.getSharedPreferences("subtitle-feedback", Context.MODE_PRIVATE)
    private val key = MessageDigest.getInstance("SHA-256").digest(
        "${HubSettings.baseUrl(context)}\n${HubSettings.userId(context)}\n$itemId".toByteArray()
    ).joinToString("") { "%02x".format(it) }
    private val json = Json { ignoreUnknownKeys = true }
    fun merge(current: List<SubtitleRecord>): List<SubtitleRecord> {
        val saved = runCatching { json.decodeFromString(ListSerializer(SubtitleRecord.serializer()), prefs.getString(key, "[]")!!) }.getOrDefault(emptyList())
        val records = (current + saved.map { it.copy(installed = false) }).distinctBy { it.id }.take(100)
        // Only retain provenance, never retain an assertion that a file is still installed.
        prefs.edit().putString(key, json.encodeToString(ListSerializer(SubtitleRecord.serializer()), records.filter { it.provider.isNotEmpty() }.map { it.copy(installed = false) })).apply()
        return records
    }
    fun rating(id: String): String = prefs.getString("$key:$id", "").orEmpty()
    fun rate(id: String, value: String) { prefs.edit().putString("$key:$id", value).apply() }
}
