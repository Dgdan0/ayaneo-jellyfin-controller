package com.pocketds.hub.reader

import kotlin.math.abs

data class ReaderLocator(
    val publicationId: String,
    val chapterId: String,
    val progression: Double,
    val label: String = "",
    val completed: Boolean = false
) {
    init {
        require(progression in 0.0..1.0) { "progression must be between zero and one" }
    }
}

/** Separates preview/visible position from the last position safe to persist. */
class ReaderPositionState(initial: ReaderLocator) {
    var generation: Long = 1
        private set
    var visible: ReaderLocator = initial
        private set
    var saved: ReaderLocator = initial
        private set
    var pendingProgress: ReaderLocator? = null
        private set

    private var preview: ReaderLocator? = null

    fun beginPreview(locator: ReaderLocator) {
        if (locator.publicationId != saved.publicationId) return
        preview = locator
        visible = locator
    }

    fun commitPreview(): ReaderLocator? {
        val value = preview ?: return null
        preview = null
        saved = value
        visible = value
        pendingProgress = value
        return value
    }

    fun cancelPreview() {
        preview = null
        visible = saved
    }

    fun takePendingProgress(): ReaderLocator? = pendingProgress.also { pendingProgress = null }

    fun replacePublication(locator: ReaderLocator): Long {
        generation += 1
        visible = locator
        saved = locator
        preview = null
        pendingProgress = null
        return generation
    }

    fun acceptSettled(callbackGeneration: Long, locator: ReaderLocator): Boolean {
        if (callbackGeneration != generation || locator.publicationId != saved.publicationId) return false
        preview = null
        visible = locator
        saved = locator
        pendingProgress = locator
        return true
    }
}

data class ReaderProgressPoint(
    val locator: ReaderLocator,
    val revision: Long,
    val baseRevision: Long,
    val updatedAtMillis: Long,
    val deviceName: String
)

enum class ReaderProgressSide { LOCAL, SERVER }

sealed interface ReaderProgressResolution {
    data class Use(val side: ReaderProgressSide) : ReaderProgressResolution
    data object Prompt : ReaderProgressResolution
}

object ReaderConflictPolicy {
    private const val CLOSE_PROGRESSION = 0.03

    fun resolve(local: ReaderProgressPoint, server: ReaderProgressPoint): ReaderProgressResolution {
        val completionDisagrees = local.locator.completed != server.locator.completed
        if (completionDisagrees) {
            val completed = if (local.locator.completed) local else server
            val active = if (local.locator.completed) server else local
            if (completed.updatedAtMillis < active.updatedAtMillis ||
                completed.baseRevision < active.revision
            ) return ReaderProgressResolution.Prompt
        }

        if (local.baseRevision >= server.revision) {
            return ReaderProgressResolution.Use(ReaderProgressSide.LOCAL)
        }
        if (server.baseRevision >= local.revision) {
            return ReaderProgressResolution.Use(ReaderProgressSide.SERVER)
        }

        val close = local.locator.chapterId == server.locator.chapterId &&
            abs(local.locator.progression - server.locator.progression) <= CLOSE_PROGRESSION
        if (close) {
            return ReaderProgressResolution.Use(
                if (local.updatedAtMillis >= server.updatedAtMillis) {
                    ReaderProgressSide.LOCAL
                } else {
                    ReaderProgressSide.SERVER
                }
            )
        }
        return ReaderProgressResolution.Prompt
    }
}
