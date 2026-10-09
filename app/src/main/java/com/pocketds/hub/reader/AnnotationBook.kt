package com.pocketds.hub.reader

import kotlinx.serialization.Serializable

/**
 * What this device keeps of one profile's highlights of one book (#62): every one the hub has told it of, the edits it has
 * not yet been able to send (the outbox), and how far through the hub's changes it has read. Plain Kotlin, so a JVM test
 * pins the rules; [AnnotationStore] puts it on disk and [AnnotationSync] talks to the hub.
 *
 * The rule is the hub's, last write wins on `updatedAt`: an edit made here is stamped by this device's clock, a version the hub
 * sends replaces ours when it is newer or when we have nothing waiting to be sent for it. A delete is an edit that leaves a
 * tombstone, so a device that was offline learns of it and an Undo (a newer edit of the same id) can bring it back.
 */
class AnnotationBook(
    annotations: Collection<ReadingAnnotation> = emptyList(),
    pending: Collection<String> = emptyList(),
    /** The greatest `syncedAt` of the hub's that this device has read: the next fetch asks for what is after it. */
    var cursor: Long = 0
) {
    private val byId = LinkedHashMap<String, ReadingAnnotation>().also { map -> annotations.forEach { map[it.id] = it } }
    private val outbox = LinkedHashSet<String>().also { set -> pending.filterTo(set) { it in byId } }

    /** The highlights to show, in the order they were made. */
    val live: List<ReadingAnnotation> get() = byId.values.filter { !it.deleted }.sortedWith(compareBy({ it.createdAt }, { it.id }))

    /** Everything kept, tombstones too. */
    val all: List<ReadingAnnotation> get() = byId.values.toList()

    val pendingIds: List<String> get() = outbox.toList()

    val isDirty: Boolean get() = outbox.isNotEmpty()

    operator fun get(id: String): ReadingAnnotation? = byId[id]

    /** The edits waiting to be sent, oldest first, so a delete and the Undo after it arrive in the order they were made. */
    fun pending(): List<ReadingAnnotation> = outbox.mapNotNull { byId[it] }.sortedBy { it.updatedAt }

    /**
     * A highlight made or changed here. It is stamped [now], or one millisecond after the version it replaces when this device's
     * clock has not moved past it, so it always wins over what it edits. Returns what is kept.
     */
    fun put(annotation: ReadingAnnotation, now: Long): ReadingAnnotation {
        val held = byId[annotation.id]
        val stamp = maxOf(now, (held?.updatedAt ?: 0L) + 1)
        val kept = annotation.copy(
            createdAt = held?.createdAt ?: annotation.createdAt.takeIf { it > 0 } ?: stamp,
            updatedAt = stamp, deleted = false, syncedAt = held?.syncedAt ?: 0
        )
        byId[kept.id] = kept
        outbox += kept.id
        return kept
    }

    /** A highlight removed here: a tombstone that keeps the anchor and drops the note. Null for one this book never held. */
    fun remove(id: String, now: Long): ReadingAnnotation? {
        val held = byId[id] ?: return null
        if (held.deleted) return held
        val tomb = held.copy(note = "", updatedAt = maxOf(now, held.updatedAt + 1), deleted = true)
        byId[id] = tomb
        outbox += id
        return tomb
    }

    /**
     * The hub took [sent] (or answered with [held], what it keeps under that id when it kept something newer): the edit is no
     * longer waiting, unless this device has edited it again since, and what the hub holds is adopted when it is not the
     * version that was sent.
     */
    fun sent(id: String, sent: ReadingAnnotation, held: ReadingAnnotation) {
        val local = byId[id]
        when {
            local == null -> byId[id] = held
            local.updatedAt > sent.updatedAt && local.updatedAt > held.updatedAt -> Unit // edited again since: still waiting
            else -> { byId[id] = held; outbox -= id }
        }
        cursor = maxOf(cursor, held.syncedAt)
    }

    /** The hub will not take this edit and never will (it is not a highlight, or the book has the most it can keep): it is no longer waiting. */
    fun refused(id: String) { outbox -= id }

    /**
     * What the hub sent. A version replaces ours when it is as new or newer (the hub's wins a tie, so two devices that wrote the
     * same moment agree); an older one changes nothing, whether ours is waiting to be sent or already was. An edit still waiting
     * that the hub's version replaces is dropped from the outbox: it would lose at the hub. Returns whether anything shown changed.
     */
    fun merge(remote: List<ReadingAnnotation>): Boolean {
        var changed = false
        for (incoming in remote) {
            cursor = maxOf(cursor, incoming.syncedAt)
            val local = byId[incoming.id]
            if (local != null && incoming.updatedAt < local.updatedAt) continue
            if (local == null || shown(local) != shown(incoming)) changed = true
            byId[incoming.id] = incoming
            outbox -= incoming.id
        }
        return changed
    }

    /** What the person sees of a version: a change to nothing but its stamps does not redraw the page. */
    private fun shown(a: ReadingAnnotation) = Triple(a.deleted, a.color to a.note, a.document to a.quote)

    fun snapshot(): Snapshot = Snapshot(byId.values.toList(), outbox.toList(), cursor)

    @Serializable
    data class Snapshot(val annotations: List<ReadingAnnotation> = emptyList(), val pending: List<String> = emptyList(), val cursor: Long = 0) {
        fun book() = AnnotationBook(annotations, pending, cursor)
    }
}
