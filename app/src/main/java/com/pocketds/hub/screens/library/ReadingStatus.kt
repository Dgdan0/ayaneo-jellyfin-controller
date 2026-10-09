package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou

/**
 * A book's reading status (#63): one word for where it stands for this profile, and the one owner of what
 * that word is, what it shows and what choosing it does. Pure, so it is tested.
 *
 *  - [WANT]: on the Want to read list. The list is this device's own (it has always been), so choosing
 *    Want to read adds the book to it and any other status takes it off: there are not two.
 *  - [READING]: Continue reading and Home show it.
 *  - [FINISHED]: the ✓ on the cover. Asks the month, as Mark finished always did.
 *  - [NOT_READING]: put down. Off Continue reading and Home; the place is kept in case of coming back.
 *
 * The hub sends one effective status on every book ([ReadingWork.status], [ReadingSectionItem.status]) and the page
 * trusts it. A hub from before this sends none, and [derive] works it out the way the hub does, so the tick
 * and Continue reading are right either way.
 */
object ReadingStatus {
    const val WANT = "want"
    const val READING = "reading"
    const val FINISHED = "finished"
    const val NOT_READING = "not-reading"

    /** In the order the picker shows them. */
    val CHOICES = listOf(WANT, READING, FINISHED, NOT_READING)

    fun known(status: String) = status in CHOICES

    fun label(status: String): String = when (status) {
        WANT -> "Want to read"
        READING -> "Reading"
        FINISHED -> "Finished"
        NOT_READING -> "Not reading"
        else -> ""
    }

    /** What choosing it does, under its name in the picker. */
    fun detail(status: String): String = when (status) {
        WANT -> "Keep it on your Want to read list"
        READING -> "Show it in Continue reading"
        FINISHED -> "Say when; puts the ✓ on its cover"
        NOT_READING -> "Take it off Continue reading and Home; your place stays"
        else -> ""
    }

    // ------------------------------------------------------------------ the one status of a book

    fun of(work: ReadingWork): String = of(work.status, work.you, work.progress)

    fun of(item: ReadingSectionItem): String = of(item.status, null, item.progress)

    private fun of(status: String, you: ReadingYou?, progress: ReadingProgress?): String =
        status.takeIf(::known) ?: derive(you, progress)

    /**
     * The hub's `effectiveReadingStatus`, for a hub that sends none: what the person chose, else finished (a month, an import
     * that says read, a place at the end), then the import's to-read shelf, then reading (a place begun, or an import that
     * says currently reading). Blank where there is nothing to say.
     */
    fun derive(you: ReadingYou?, progress: ReadingProgress?): String {
        if (you != null && known(you.chosen)) return settle(you.chosen, progress)
        if (you != null && (you.finished.isNotBlank() || you.status == "read")) return FINISHED
        if (progress?.completed == true) return FINISHED
        if (you?.status == "to-read") return WANT
        if (progress != null && progress.percentage > 0.0) return READING
        if (you?.status == "currently-reading") return READING
        return ""
    }

    /**
     * The status [chosen] comes to for a book at [progress]: a book wanted and then opened is being read (a place begun,
     * short of the end), as the Want to read list always had it. Not reading is the one choice a place does not move.
     */
    fun settle(chosen: String, progress: ReadingProgress?): String =
        if (chosen == WANT && progress != null && progress.percentage > 0.0 && !progress.completed) READING else chosen

    /** The ✓ on a cover: read to the end, marked Finished, or imported as read. */
    fun isFinished(work: ReadingWork) = of(work) == FINISHED

    fun isFinished(item: ReadingSectionItem) = of(item) == FINISHED

    /**
     * Whether Continue reading and Home show a book: it has a place, and was neither put down nor finished. A book chosen
     * as Reading stays after the end of it (reading it again).
     */
    fun continues(work: ReadingWork): Boolean = continues(of(work), work.progress)

    fun continues(item: ReadingSectionItem): Boolean = continues(of(item), item.progress)

    private fun continues(status: String, progress: ReadingProgress?): Boolean {
        val place = progress ?: return false
        if (place.percentage <= 0.0) return false
        return when (status) {
            NOT_READING, FINISHED -> false
            READING -> true
            else -> !place.completed
        }
    }

    // ------------------------------------------------------------------ the menu row

    /** "Reading status · Reading"; just "Reading status" while the book has none. */
    fun rowLabel(status: String): String = label(status).let { if (it.isEmpty()) "Reading status" else "Reading status · $it" }

    /** Under the row: what the status means for this book. */
    fun rowDetail(status: String, you: ReadingYou?): String = when (status) {
        WANT -> "On your Want to read list"
        READING -> "In Continue reading"
        FINISHED -> ReadingBookPage.monthLabel(you?.finished.orEmpty())?.let { "Finished $it · change the date" } ?: "Finished · say when"
        NOT_READING -> "Off Continue reading and Home; your place stays"
        else -> "Want to read, reading, finished or not reading"
    }

    // ------------------------------------------------------------------ choosing

    enum class Action {
        /** It is the status the book has: nothing to do. */
        NOTHING,
        /** Write it, and do what it does here. */
        SET,
        /** Finished asks the month first, whether or not it is finished already: that is how a date is put right. */
        ASK_MONTH
    }

    fun action(next: String, current: String): Action = when {
        next == FINISHED -> Action.ASK_MONTH
        next == current -> Action.NOTHING
        else -> Action.SET
    }

    enum class LocalRead { MARK, UNMARK, KEEP }

    /** What choosing a status does on this device besides writing it. */
    data class Effects(val wantList: Boolean, val localRead: LocalRead)

    /**
     * [wantList]: the book is on the Want to read list afterwards (and off it for any other status). [localRead]: the
     * hub has no route that marks a book read in Kavita or Storyteller, so Finished marks it read on this device as the
     * page's read toggle always did, and leaving Finished takes that mark away.
     */
    fun effects(next: String, wasFinished: Boolean) = Effects(
        wantList = next == WANT,
        localRead = when {
            next == FINISHED && !wasFinished -> LocalRead.MARK
            next != FINISHED && wasFinished -> LocalRead.UNMARK
            else -> LocalRead.KEEP
        }
    )

    // ------------------------------------------------------------------ under the cover

    /**
     * What "you" says under the cover (the month finished, the shelves, how many times): a book that is not finished does not
     * carry the month it was once finished, nor an import's "read", though its count of readings stays.
     */
    fun youForLine(work: ReadingWork): ReadingYou? {
        val you = work.you ?: return null
        if (of(work) == FINISHED) return you
        return you.copy(finished = "", status = if (you.status == "read") "" else you.status)
    }
}
