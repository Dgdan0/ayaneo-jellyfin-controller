package com.pocketds.hub.reader

import com.pocketds.hub.net.EpubRevalidation
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.net.ReadingEpubDownload
import java.io.File

/**
 * One edition of a book kept on this device (the ebook, or the read-along edition
 * without its audio), and how it is opened (#41).
 *
 * A copy that is here opens at once unless the hub is asked about it first: the
 * hub serves each ebook as a reading copy with a strong ETag of its own bytes, and
 * a copy downloaded before that existed has the old font sizes in it. So an
 * `If-None-Match` goes out with the ETag kept beside the copy, and
 * [EpubFreshness.decide] says what the answer means: a 304 opens the copy
 * untouched, another edition replaces it, and a hub that cannot answer (no network,
 * a timeout, a 5xx, a token that is held) leaves the copy to open as it always did.
 * The saved place is a locator (href and progression), so it is valid in the new copy.
 *
 * What fetches is [open]'s `fetch`, so the same rules run in the reader (the shared
 * [com.pocketds.hub.net.HubClient]) and in the tests (a local server).
 */
class EpubEdition(
    private val cache: EpubPackageCache,
    private val workId: String,
    private val sourceItemId: String,
    /** How long the hub has to begin answering about a copy that is kept. */
    private val answerWithinMs: Long = EpubRevalidation.ANSWER_MS
) {
    enum class Stage {
        /** The whole book is on its way: nothing is kept here. */
        DOWNLOADING,

        /** The hub has another edition of a book kept here, and it is on its way. */
        UPDATING
    }

    /** How the copy came to be the one that opens, for the trace and the tests. */
    enum class How {
        /** Nothing was kept; the whole book was fetched. */
        DOWNLOADED,

        /** The hub had another edition, and it replaced the copy. */
        UPDATED,

        /** The hub said the copy is current. */
        CONFIRMED,

        /** The copy opened unchecked: not asked, or the hub could not answer. */
        KEPT
    }

    sealed interface Opened {
        data class Ready(val file: File, val how: How) : Opened
        data class Failed(val failure: HubResult.Failed) : Opened
    }

    /**
     * @param forceDownload drop what is kept and fetch the whole book.
     * @param revalidate ask the hub about a copy that is kept; false opens it as it is (the whole
     *   read-along edition, which the hub does not rewrite, and an edition kept for a hub that
     *   could not be asked).
     * @param fetch fetches into the destination, with the question when a copy is kept.
     * @param onStage called as a download begins, from whichever thread the transfer runs on.
     */
    suspend fun open(
        forceDownload: Boolean,
        revalidate: Boolean,
        fetch: suspend (destination: File, check: EpubRevalidation?) -> HubResult<ReadingEpubDownload>,
        onStage: (Stage) -> Unit
    ): Opened {
        if (forceDownload) cache.remove(workId, sourceItemId)
        val copy = cache.copyState(workId, sourceItemId)
        val complete = cache.completeFile(workId, sourceItemId)
        val temporary = cache.temporaryFile(workId, sourceItemId)
        if (copy == CopyState.Missing) {
            onStage(Stage.DOWNLOADING)
            return when (val result = fetch(temporary, null)) {
                is HubResult.Ok -> promote(result.value, How.DOWNLOADED)
                    ?: Opened.Failed(HubResult.Failed(FailureKind.BAD_RESPONSE, "The downloaded EPUB is incomplete"))
                is HubResult.Failed -> Opened.Failed(result)
            }
        }
        if (!revalidate) return Opened.Ready(complete, How.KEPT)

        val question = EpubRevalidation(copy, answerWithinMs, onReplace = { onStage(Stage.UPDATING) })
        return when (val result = fetch(temporary, question)) {
            is HubResult.Ok ->
                if (result.value.keptCopy) {
                    // The hub sent no tag for a copy that has none recorded: remember that it was
                    // asked, so the book is not fetched again at every opening.
                    if (copy == CopyState.Unrecorded) runCatching { cache.keepEtag(workId, sourceItemId, "") }
                    Opened.Ready(complete, How.CONFIRMED)
                } else {
                    // A newer edition that cannot be kept leaves the old copy in place.
                    promote(result.value, How.UPDATED) ?: Opened.Ready(complete, How.KEPT)
                }
            // Whatever the failure was, the copy stands. A newer edition cut off part-way
            // stays as a partial transfer, which the next opening resumes.
            is HubResult.Failed -> when (EpubFreshness.decide(copy, EpubFreshness.Answer.Failed(result.kind))) {
                EpubFreshness.Action.OPEN_CACHED -> Opened.Ready(complete, How.KEPT)
                EpubFreshness.Action.REPLACE, EpubFreshness.Action.DOWNLOAD -> Opened.Failed(result)
            }
        }
    }

    private fun promote(download: ReadingEpubDownload, how: How): Opened.Ready? =
        runCatching { cache.promote(workId, sourceItemId, EpubFreshness.strong(download.etag)) }
            .getOrNull()?.let { Opened.Ready(it, how) }
}
