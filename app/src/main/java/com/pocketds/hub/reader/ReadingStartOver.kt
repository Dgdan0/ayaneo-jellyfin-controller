package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.home.ReadingShelves
import com.pocketds.hub.screens.library.ReadingBookFacts

/**
 * Start over (#60): a book back to not started, in every format, on every device. Mark read and Mark unread
 * only ever changed a device's completion state; the place itself lives in Storyteller, in Kavita and in each
 * device's own checkpoint, outbox and downloaded copy, and nothing touched it. This is the one owner of when a
 * book offers it, what it asks first and what a device forgets once the hub has done it. The hub does the
 * book's side (`POST /v1/reading/works/{id}/start-over`); [applyLocal] is this device's.
 */
object ReadingStartOver {
    const val ACTION = "Start over"
    /** The harmless answer, first. */
    const val KEEP = "Keep my place"

    /** Offered while the book has a place to forget or a finish to take away. */
    fun offered(hasPlace: Boolean, finished: Boolean): Boolean = hasPlace || finished

    /** Whether the book has a place: the hub says it was started, or this device [kept] one (even unsent). */
    fun hasPlace(work: ReadingWork, kept: Boolean): Boolean =
        kept || (work.progress?.let { it.completed || it.percentage > 0.0 } == true)

    fun isComic(work: ReadingWork): Boolean = ReadingShelves.isComic(work)

    fun confirmTitle(work: ReadingWork): String = "Start ${work.title} over?"

    /** What it forgets and what stays, in the words of the formats the book has. */
    fun confirmDetail(work: ReadingWork): String {
        if (isComic(work)) return "Your place is forgotten and every issue is unread again, on every device. Your lists stay."
        val names = ReadingBookFacts.formats(work).map { if (it == "readaloud") "read along" else it }
        val place = when (names.size) {
            0 -> "Your place"
            1 -> "Your place in the ${names[0]}"
            else -> "Your place in the ${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        }
        return "$place is forgotten on every device. Your rating, notes and bookmarks stay."
    }

    fun done() = "Started over · back to not started"
    fun failed(message: String) = "Start over could not be done · $message"

    /**
     * The hub has started [work] over at [resetAt]: this device forgets what it kept of the place (see
     * [ReadingProgress.noticeReset]). True when that was news here. Safe to call for every read of the book.
     */
    fun applyLocal(context: Context, work: ReadingWork, resetAt: Long): Boolean {
        val progress = ReadingProgress.get(context)
        return progress.noticeReset(progress.session().identity, work.id, resetAt, work.editions.map { it.sourceItemId })
    }

    /** Whether this device keeps a place of [work] that has not been sent, or has been and is kept here. */
    fun keptHere(context: Context, work: ReadingWork): Boolean {
        val progress = ReadingProgress.get(context)
        return runCatching { progress.store.hasPlace(progress.session().identity, work.id) }.getOrDefault(false)
    }
}
