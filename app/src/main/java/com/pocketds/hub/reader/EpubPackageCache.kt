package com.pocketds.hub.reader

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class EpubCacheEntry(
    val id: String,
    val sizeBytes: Long,
    val lastAccessMillis: Long,
    val active: Boolean = false
)

object EpubPackageCachePolicy {
    fun evict(entries: List<EpubCacheEntry>, budgetBytes: Long): List<String> {
        var used = entries.sumOf { it.sizeBytes.coerceAtLeast(0) }
        if (used <= budgetBytes.coerceAtLeast(0)) return emptyList()
        val removed = mutableListOf<String>()
        entries.asSequence()
            .filterNot { it.active }
            .sortedBy { it.lastAccessMillis }
            .forEach { entry ->
                if (used <= budgetBytes.coerceAtLeast(0)) return@forEach
                removed += entry.id
                used -= entry.sizeBytes.coerceAtLeast(0)
            }
        return removed
    }
}

/**
 * Complete EPUBs are promoted atomically; partial ZIPs are never opened.
 *
 * Beside each complete copy sits `<name>.etag`: the strong ETag of the bytes the
 * hub sent for it (#41), so the book can be asked about when it is opened. It
 * shares the copy's name and folder, which is how it goes with the copy: every
 * place that removes a book's files (Remove offline copy takes whatever starts
 * with the work's id) takes it too.
 */
class EpubPackageCache(private val root: File) {
    init {
        root.mkdirs()
    }

    fun completeFile(workId: String, sourceItemId: String): File =
        File(root, stableName(workId, sourceItemId) + ".epub")

    /** The ETag kept for [completeFile]; absent for a copy from before there was one. */
    fun etagFile(workId: String, sourceItemId: String): File =
        File(root, stableName(workId, sourceItemId) + ".etag")

    fun temporaryName(workId: String, sourceItemId: String): String =
        stableName(workId, sourceItemId) + ".part"

    fun temporaryFile(workId: String, sourceItemId: String): File =
        File(root, temporaryName(workId, sourceItemId))

    /** The partial transfer only: the complete copy and its ETag are left alone. */
    fun clearDownload(workId: String, sourceItemId: String) {
        val temporary = temporaryFile(workId, sourceItemId)
        temporary.delete()
        File(temporary.path + ".meta").delete()
    }

    /** The copy, its ETag and any partial transfer: nothing of the book stays behind. */
    fun remove(workId: String, sourceItemId: String) {
        completeFile(workId, sourceItemId).delete()
        etagFile(workId, sourceItemId).delete()
        clearDownload(workId, sourceItemId)
    }

    fun isComplete(workId: String, sourceItemId: String): Boolean =
        completeFile(workId, sourceItemId).let { it.isFile && it.length() > 0L }

    /** What is kept for this edition and what the hub can be asked about it (#41). */
    fun copyState(workId: String, sourceItemId: String): CopyState {
        if (!isComplete(workId, sourceItemId)) return CopyState.Missing
        val kept = runCatching { etagFile(workId, sourceItemId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?: return CopyState.Unrecorded
        if (kept.isEmpty()) return CopyState.Unverifiable
        // A file that is not a whole strong tag is damaged: the copy is asked about afresh.
        return if (EpubFreshness.strong(kept) == kept) CopyState.Tagged(kept) else CopyState.Unrecorded
    }

    /**
     * Records [etag] for the complete copy; blank records that the hub sent none,
     * which is not the same as never having recorded it ([CopyState.Unverifiable]
     * against [CopyState.Unrecorded]).
     */
    fun keepEtag(workId: String, sourceItemId: String, etag: String) {
        val target = etagFile(workId, sourceItemId)
        val temporary = File(target.path + ".new")
        try {
            temporary.writeText(etag.trim())
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun install(workId: String, sourceItemId: String, writer: (File) -> Unit): File {
        root.mkdirs()
        val temporary = temporaryFile(workId, sourceItemId)
        temporary.delete()
        try {
            writer(temporary)
            return promote(workId, sourceItemId)
        } finally {
            temporary.delete()
        }
    }

    /** [etag]: the strong ETag of the bytes just downloaded, blank when the hub sent none. */
    fun promote(workId: String, sourceItemId: String, etag: String = ""): File {
        val temporary = temporaryFile(workId, sourceItemId)
        val target = completeFile(workId, sourceItemId)
        require(temporary.isFile && temporary.length() > 0L) { "EPUB download was empty" }
        // The old tag is for the old bytes, and goes first: a crash between the move and the
        // new tag leaves a copy that is asked about afresh, never one vouched for by another's tag.
        val previous = etagFile(workId, sourceItemId).let { file -> runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() }
        etagFile(workId, sourceItemId).delete()
        try {
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // Nothing moved: the copy that was here is still here, with its tag.
            previous?.let { runCatching { keepEtag(workId, sourceItemId, it) } }
            throw e
        }
        target.setLastModified(System.currentTimeMillis())
        File(temporary.path + ".meta").delete()
        // Without the tag the book still opens; it is only asked about afresh next time.
        runCatching { keepEtag(workId, sourceItemId, etag) }
        return target
    }

    private fun stableName(workId: String, sourceItemId: String): String =
        (workId + "_" + sourceItemId).map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("").take(160)
}
