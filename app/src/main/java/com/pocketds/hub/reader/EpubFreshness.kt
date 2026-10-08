package com.pocketds.hub.reader

import com.pocketds.hub.net.FailureKind

/**
 * What this device holds of one edition of a book, as far as asking the hub
 * whether it is still the right one goes (#41).
 */
sealed interface CopyState {
    /** No complete copy: there is nothing to check, only to download. */
    data object Missing : CopyState

    /**
     * A complete copy with no ETag recorded beside it: it was downloaded before
     * the hub could be asked, so it may be the book as it was before the hub
     * began rewriting font sizes and columns.
     */
    data object Unrecorded : CopyState

    /**
     * A complete copy whose download came with no strong ETag (the hub could not
     * map the file and passed Storyteller's through). There is nothing to
     * compare, so it stands until the hub offers a tagged edition.
     */
    data object Unverifiable : CopyState

    /** A complete copy and the strong ETag of the bytes the hub sent for it. */
    data class Tagged(val etag: String) : CopyState
}

/**
 * What to do with a cached book when it is opened (#41).
 *
 * The hub serves each ebook as a reading copy with a strong ETag of its own
 * bytes, and a copy opened before that existed has the old font sizes in it: a
 * text size and "Two pages" that do nothing. `EpubPackageCache.isComplete` kept
 * such a copy for ever, so the book is asked about whenever it is opened, and
 * this is the one place that decides what the answer means. Pure, so the table
 * is tested without a network.
 */
object EpubFreshness {
    enum class Action {
        /** The copy on this device stands. */
        OPEN_CACHED,

        /** The hub has another edition: fetch it, and keep the old copy if that fails. */
        REPLACE,

        /** Nothing is kept: fetch the whole book. */
        DOWNLOAD
    }

    sealed interface Answer {
        /**
         * The hub answered. [etag] is its strong ETag, blank when it sent none
         * or a weak one ([strong]).
         */
        data class Replied(val status: Int, val etag: String) : Answer

        /** The hub could not be asked, or could not answer. */
        data class Failed(val kind: FailureKind) : Answer
    }

    fun decide(copy: CopyState, answer: Answer): Action {
        if (copy == CopyState.Missing) return Action.DOWNLOAD
        return when (answer) {
            // No network, a timeout, a 5xx, a rejected or held token, a book the hub no
            // longer lists: whatever the kind, a book that opened yesterday opens today.
            is Answer.Failed -> Action.OPEN_CACHED
            is Answer.Replied -> replied(copy, answer)
        }
    }

    private fun replied(copy: CopyState, answer: Answer.Replied): Action {
        // A 304 says the copy is current. Any other status that is not a body (an error
        // reaches the caller as a failure) leaves the copy as it is.
        if (answer.status != 200 && answer.status != 206) return Action.OPEN_CACHED
        // The hub tagged nothing: it is passing Storyteller's file through, which says
        // nothing about this copy. The hub also does this when it cannot map the file.
        if (answer.etag.isEmpty()) return Action.OPEN_CACHED
        // A body whose tag is the one kept: a server that does not honour If-None-Match
        // (the hub's pass-through, before it began serving the reading copy) must not
        // cost the whole book every time it is opened.
        if (copy is CopyState.Tagged && copy.etag == answer.etag) return Action.OPEN_CACHED
        // A different tag, or the first tag this copy has had: another edition.
        return Action.REPLACE
    }

    /** The `If-None-Match` value to send for [copy]; null where there is no tag to match. */
    fun condition(copy: CopyState): String? = (copy as? CopyState.Tagged)?.etag

    /**
     * [header] when it is a strong entity tag (quoted, `"abc"`), else blank. The
     * transfer's resume keeps only strong tags too: a weak one cannot vouch for
     * the same bytes.
     */
    fun strong(header: String?): String {
        val value = header?.trim().orEmpty()
        val quoted = value.length >= 2 && value.startsWith('"') && value.endsWith('"')
        // A header value cannot carry a control character; a kept file that does is damaged.
        return if (quoted && value.none { it.code < 0x20 || it.code == 0x7f }) value else ""
    }
}
