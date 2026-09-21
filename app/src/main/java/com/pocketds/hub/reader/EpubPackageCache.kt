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

/** Complete EPUBs are promoted atomically; partial ZIPs are never opened. */
class EpubPackageCache(private val root: File) {
    init {
        root.mkdirs()
    }

    fun completeFile(workId: String, sourceItemId: String): File =
        File(root, stableName(workId, sourceItemId) + ".epub")

    fun temporaryName(workId: String, sourceItemId: String): String =
        stableName(workId, sourceItemId) + ".part"

    fun temporaryFile(workId: String, sourceItemId: String): File =
        File(root, temporaryName(workId, sourceItemId))

    fun isComplete(workId: String, sourceItemId: String): Boolean =
        completeFile(workId, sourceItemId).let { it.isFile && it.length() > 0L }

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

    fun promote(workId: String, sourceItemId: String): File {
        val temporary = temporaryFile(workId, sourceItemId)
        val target = completeFile(workId, sourceItemId)
        require(temporary.isFile && temporary.length() > 0L) { "EPUB download was empty" }
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    private fun stableName(workId: String, sourceItemId: String): String =
        (workId + "_" + sourceItemId).map { char ->
            if (char.isLetterOrDigit() || char == '_' || char == '-') char else '_'
        }.joinToString("").take(160)
}
