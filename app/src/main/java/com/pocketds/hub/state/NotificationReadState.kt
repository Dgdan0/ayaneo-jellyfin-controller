package com.pocketds.hub.state

import com.pocketds.hub.model.NotificationSection

data class NotificationReadSnapshot(
    val initializedServices: Set<String> = emptySet(),
    val seenIds: Set<String> = emptySet(),
    val oldestTimes: Map<String, String> = emptyMap()
)

data class NotificationReadResult(
    val snapshot: NotificationReadSnapshot,
    val unreadIds: Set<String>
)

/** Pure unread-state transitions. Android persistence is kept in the small store wrapper. */
object NotificationReadReducer {
    fun observe(
        snapshot: NotificationReadSnapshot,
        sections: List<NotificationSection>
    ): NotificationReadResult {
        val initialized = snapshot.initializedServices.toMutableSet()
        val seen = snapshot.seenIds.toMutableSet()
        val oldest = snapshot.oldestTimes.toMutableMap()

        sections.forEach { section ->
            val historicalTimes = section.items.mapNotNull { it.occurredAt.takeIf(String::isNotEmpty) }
            val previousOldest = oldest[section.service]
            if (section.service !in initialized && (section.items.isNotEmpty() || section.state == "up")) {
                // Installing an update must not turn the existing history into a full inbox.
                seen += section.items.map { it.id }
                initialized += section.service
            } else if (previousOldest != null) {
                // Increasing a history limit exposes older entries; those are backfill, not new.
                section.items
                    .filter { it.occurredAt.isNotEmpty() && it.occurredAt <= previousOldest }
                    .forEach { seen += it.id }
            }
            historicalTimes.minOrNull()?.let { observedOldest ->
                oldest[section.service] = minOf(previousOldest ?: observedOldest, observedOldest)
            }
        }

        val currentIds = sections.flatMap { it.items }.map { it.id }.toSet()
        return NotificationReadResult(
            snapshot = NotificationReadSnapshot(initialized, seen, oldest),
            unreadIds = currentIds - seen
        )
    }

    fun markSeen(snapshot: NotificationReadSnapshot, id: String): NotificationReadSnapshot =
        snapshot.copy(seenIds = snapshot.seenIds + id)

    fun markAllSeen(snapshot: NotificationReadSnapshot, ids: Collection<String>): NotificationReadSnapshot =
        snapshot.copy(seenIds = snapshot.seenIds + ids)
}
