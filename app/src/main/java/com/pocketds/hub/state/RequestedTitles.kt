package com.pocketds.hub.state

import com.pocketds.hub.model.Availability
import com.pocketds.hub.model.SearchHit

/**
 * Titles requested during this session, so every poster shows it at once.
 *
 * Discover rows are cached for half an hour on the device and the hub caches
 * its feeds too, so a title requested from a Discover card -- or from its
 * detail page and then backed out of -- kept its "not in library" look and
 * still offered Request. [RequestFlow] records each request here and the card
 * binding applies it.
 *
 * Only ever a step forward: once the hub reports anything past "not in
 * library", its answer wins, so this cannot pin a title at Requested after it
 * has been approved, downloaded or deleted.
 */
object RequestedTitles {

    private data class Change(val availability: String, val requestId: Int)

    private val changes = HashMap<String, Change>()

    /** Bumped on every record, so a screen can tell its cards need rebinding. */
    @Volatile
    var revision = 0
        private set

    @Synchronized
    fun record(key: String, availability: String, requestId: Int) {
        if (key.isEmpty()) return
        changes[key] = Change(availability.ifEmpty { Availability.REQUESTED.wire }, requestId)
        revision++
    }

    @Synchronized
    fun apply(hit: SearchHit): SearchHit {
        val change = changes[hit.media.key] ?: return hit
        val current = Availability.fromWire(hit.availability)
        if (current != Availability.NOT_IN_LIBRARY && current != Availability.UNKNOWN) return hit
        return hit.copy(
            availability = change.availability,
            requestId = change.requestId,
            actions = hit.actions - "request"
        )
    }

    @Synchronized
    internal fun clear() {
        changes.clear()
        revision++
    }
}
