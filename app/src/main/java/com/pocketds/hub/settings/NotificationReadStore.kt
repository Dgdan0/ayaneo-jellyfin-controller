package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.model.NotificationSection
import com.pocketds.hub.state.NotificationReadReducer
import com.pocketds.hub.state.NotificationReadSnapshot

class NotificationReadStore(private val context: Context) {
    fun observe(sections: List<NotificationSection>): Set<String> {
        val result = NotificationReadReducer.observe(load(), sections)
        save(result.snapshot)
        return result.unreadIds
    }

    fun markSeen(id: String) {
        if (id.isEmpty()) return
        save(NotificationReadReducer.markSeen(load(), id))
    }

    fun markAllSeen(ids: Collection<String>) {
        save(NotificationReadReducer.markAllSeen(load(), ids))
    }

    fun unread(ids: Collection<String>): Set<String> = ids.toSet() - load().seenIds

    private fun load(): NotificationReadSnapshot {
        val prefs = Prefs.of(context)
        val oldest = prefs.getStringSet(key("oldest"), emptySet()).orEmpty().mapNotNull { entry ->
            val split = entry.indexOf('=')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }.toMap()
        return NotificationReadSnapshot(
            initializedServices = prefs.getStringSet(key("initialized"), emptySet()).orEmpty().toSet(),
            seenIds = prefs.getStringSet(key("seen"), emptySet()).orEmpty().toSet(),
            oldestTimes = oldest
        )
    }

    private fun save(snapshot: NotificationReadSnapshot) {
        val boundedSeen = if (snapshot.seenIds.size <= MAX_SEEN_IDS) snapshot.seenIds else
            snapshot.seenIds.sorted().takeLast(MAX_SEEN_IDS).toSet()
        Prefs.of(context).edit()
            .putStringSet(key("initialized"), snapshot.initializedServices.toSet())
            .putStringSet(key("seen"), boundedSeen)
            .putStringSet(key("oldest"), snapshot.oldestTimes.map { "${it.key}=${it.value}" }.toSet())
            .apply()
    }

    private fun key(suffix: String): String {
        val hub = HubSettings.baseUrl(context).hashCode().toUInt().toString(16)
        return "notification_read_${hub}_$suffix"
    }

    private companion object {
        const val MAX_SEEN_IDS = 1_000
    }
}
