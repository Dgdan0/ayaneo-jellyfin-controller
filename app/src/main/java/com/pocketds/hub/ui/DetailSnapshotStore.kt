package com.pocketds.hub.ui

import android.content.Context
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Display cache only. Jellyfin and the existing watch-progress stores remain authoritative. */
object DetailSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(context: Context, id: String) = "detail_snapshot_" +
        DetailSnapshotKey.of(HubSettings.baseUrl(context), HubSettings.userId(context), id)

    fun read(context: Context, id: String): DetailSnapshot? = runCatching {
        val raw = Prefs.of(context).getString(key(context, id), null) ?: return null
        json.decodeFromString<DetailSnapshot>(raw)
    }.getOrNull()

    fun saveItem(context: Context, item: LibraryItem) {
        if (item.id.isBlank()) return
        save(context, item.id, (read(context, item.id) ?: DetailSnapshot()).copy(item = item))
    }

    fun saveTarget(context: Context, seriesId: String, target: SeriesPlayTargetResponse) {
        if (seriesId.isBlank() || target.item.id.isBlank()) return
        save(context, seriesId, (read(context, seriesId) ?: DetailSnapshot()).copy(
            target = target, targetRecordedAt = System.currentTimeMillis()))
    }

    private fun save(context: Context, id: String, snapshot: DetailSnapshot) {
        Prefs.of(context).edit().putString(key(context, id), json.encodeToString(snapshot)).apply()
    }
}
