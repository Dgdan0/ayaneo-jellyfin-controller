package com.pocketds.hub.reader

enum class ReaderSourceKind { DOWNLOAD, CACHE, REMOTE }

data class ReaderSource(
    val id: String,
    val kind: ReaderSourceKind,
    val sizeBytes: Long,
    val valid: Boolean,
    val complete: Boolean
)

object ReaderSourceSelector {
    private val priority = mapOf(
        ReaderSourceKind.DOWNLOAD to 0,
        ReaderSourceKind.CACHE to 1,
        ReaderSourceKind.REMOTE to 2
    )

    fun select(sources: List<ReaderSource>): ReaderSource? = sources
        .asSequence()
        .filter { it.valid && it.complete }
        .minByOrNull { priority.getValue(it.kind) }
}

data class ReaderCacheEntry(
    val id: String,
    val sizeBytes: Long,
    val lastAccessMillis: Long,
    val kind: ReaderSourceKind,
    val active: Boolean = false
)

object ReaderCachePolicy {
    fun evictions(entries: List<ReaderCacheEntry>, temporaryBudgetBytes: Long): List<String> {
        var temporaryBytes = entries
            .filter { it.kind == ReaderSourceKind.CACHE }
            .sumOf { it.sizeBytes.coerceAtLeast(0) }
        if (temporaryBytes <= temporaryBudgetBytes.coerceAtLeast(0)) return emptyList()

        val removed = mutableListOf<String>()
        entries.asSequence()
            .filter { it.kind == ReaderSourceKind.CACHE && !it.active }
            .sortedBy { it.lastAccessMillis }
            .forEach { entry ->
                if (temporaryBytes <= temporaryBudgetBytes.coerceAtLeast(0)) return@forEach
                removed += entry.id
                temporaryBytes -= entry.sizeBytes.coerceAtLeast(0)
            }
        return removed
    }
}

data class ReaderProgressEvent(
    val userId: String,
    val workId: String,
    val editionFingerprint: String,
    val locator: ReaderLocator,
    val baseRevision: Long,
    val updatedAtMillis: Long
)

object ReaderProgressOutbox {
    fun compact(events: List<ReaderProgressEvent>): List<ReaderProgressEvent> = events
        .groupBy { Triple(it.userId, it.workId, it.editionFingerprint) }
        .values
        .mapNotNull { group -> group.maxByOrNull { it.updatedAtMillis } }
        .sortedBy { it.updatedAtMillis }
}
