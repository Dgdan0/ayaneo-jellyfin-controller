package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAnnotationWritten
import com.pocketds.hub.model.ReadingAnnotationsResponse
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult

/** The hub's highlight routes, as the sync needs them: a small seam so a JVM test can stand in for the hub. */
interface AnnotationRemote {
    suspend fun annotations(workId: String, since: Long?): HubResult<ReadingAnnotationsResponse>
    suspend fun save(workId: String, annotation: ReadingAnnotation): HubResult<ReadingAnnotationWritten>
    suspend fun delete(workId: String, id: String, updatedAt: Long): HubResult<ReadingAnnotationWritten>
}

fun HubApi.annotationRemote(): AnnotationRemote = object : AnnotationRemote {
    override suspend fun annotations(workId: String, since: Long?) = readingAnnotations(workId, since)
    override suspend fun save(workId: String, annotation: ReadingAnnotation) = saveReadingAnnotation(workId, annotation)
    override suspend fun delete(workId: String, id: String, updatedAt: Long) = deleteReadingAnnotation(workId, id, updatedAt)
}

/** What a sync needs of the book it works on: [AnnotationShelf] under a lock, a plain [AnnotationBook] in a test. */
interface AnnotationLedger {
    fun pending(): List<ReadingAnnotation>
    fun sent(id: String, sent: ReadingAnnotation, held: ReadingAnnotation)
    fun refused(id: String)
    fun cursor(): Long
    fun merged(remote: List<ReadingAnnotation>)
}

/**
 * One pass with the hub (#62), outbox first and then what the other devices did: each waiting edit goes out oldest first (a
 * delete and the Undo after it keep their order), and the hub's answer, what it holds under that id, is adopted; then the
 * changes after the cursor are read and merged. Nothing here waits on a screen: a book can be synced with nothing open.
 */
class AnnotationSync(private val remote: AnnotationRemote) {
    data class Result(
        /** A failure that may pass (no network, the hub busy): try again later. */
        val retry: Boolean = false,
        /** The hub could not be asked at all or said no for good: nothing more this pass. */
        val stopped: Boolean = false
    )

    suspend fun run(workId: String, ledger: AnnotationLedger): Result {
        for (edit in ledger.pending()) {
            val answer = if (edit.deleted) remote.delete(workId, edit.id, edit.updatedAt) else remote.save(workId, edit)
            when (answer) {
                is HubResult.Ok -> ledger.sent(edit.id, edit, answer.value.annotation)
                is HubResult.Failed -> when (judge(answer)) {
                    Verdict.REFUSED -> ledger.refused(edit.id)
                    Verdict.LATER -> return Result(retry = true, stopped = true)
                    Verdict.NEVER -> return Result(stopped = true)
                }
            }
        }
        // Everything after the cursor, tombstones too; the first read has no cursor and gets what is there.
        val since = ledger.cursor().takeIf { it > 0 }
        return when (val list = remote.annotations(workId, since)) {
            is HubResult.Ok -> { ledger.merged(list.value.annotations); Result() }
            is HubResult.Failed -> if (judge(list) == Verdict.LATER) Result(retry = true, stopped = true) else Result(stopped = true)
        }
    }

    private enum class Verdict { LATER, REFUSED, NEVER }

    private fun judge(failed: HubResult.Failed): Verdict = when {
        // The hub looked at this edit and will never take it: it is not a highlight, or the book has the most it can keep.
        failed.kind == FailureKind.BAD_RESPONSE && failed.code in REFUSALS -> Verdict.REFUSED
        failed.kind.isRetryable -> Verdict.LATER
        // An old hub without the routes, a token that cannot: not retried until the app is next opened.
        else -> Verdict.NEVER
    }

    private companion object {
        val REFUSALS = setOf("invalid_request", "annotation_limit")
    }
}
