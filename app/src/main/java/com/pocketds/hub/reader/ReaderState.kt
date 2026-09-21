package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReaderProfile {
    COMIC,
    MANGA,
    BOOK,
    READ_ALONG
}

enum class ReaderOverlay {
    HIDDEN,
    CONTROLS,
    NAVIGATOR,
    APPEARANCE,
    AUDIO,
    CONFLICT
}

sealed interface ReaderCommand {
    data class ShowOverlay(val overlay: ReaderOverlay) : ReaderCommand
    data class Navigate(val direction: Direction) : ReaderCommand
    data class MoveFocus(val direction: Direction) : ReaderCommand
    data class TurnPage(val delta: Int) : ReaderCommand
    data class ChangeChapter(val delta: Int) : ReaderCommand
    data class Zoom(val delta: Int) : ReaderCommand
    data object HideControls : ReaderCommand
    data object ActivateFocused : ReaderCommand
    data object Advance : ReaderCommand
    data object ToggleBookmark : ReaderCommand
    data object Exit : ReaderCommand
}

/** Pure reader input reducer. Android views only execute the returned command. */
class ReaderController(private val profile: ReaderProfile) {
    var overlay: ReaderOverlay = ReaderOverlay.HIDDEN
        private set

    fun dispatch(action: PadAction): ReaderCommand = when (action) {
        PadAction.Menu -> if (overlay == ReaderOverlay.HIDDEN) {
            show(ReaderOverlay.CONTROLS)
        } else {
            overlay = ReaderOverlay.HIDDEN
            ReaderCommand.HideControls
        }

        PadAction.Back -> when (overlay) {
            ReaderOverlay.HIDDEN -> ReaderCommand.Exit
            ReaderOverlay.CONTROLS -> {
                overlay = ReaderOverlay.HIDDEN
                ReaderCommand.HideControls
            }
            else -> show(ReaderOverlay.CONTROLS)
        }

        is PadAction.Step -> if (overlay == ReaderOverlay.HIDDEN) {
            ReaderCommand.Navigate(action.direction)
        } else {
            ReaderCommand.MoveFocus(action.direction)
        }

        PadAction.Activate -> if (overlay == ReaderOverlay.HIDDEN) {
            ReaderCommand.Advance
        } else {
            ReaderCommand.ActivateFocused
        }

        PadAction.Primary -> ReaderCommand.ToggleBookmark
        PadAction.Secondary -> show(ReaderOverlay.NAVIGATOR)
        PadAction.Refresh -> show(ReaderOverlay.APPEARANCE)

        is PadAction.Section -> ReaderCommand.TurnPage(action.delta.coerceIn(-1, 1))
        is PadAction.Page -> when (profile) {
            ReaderProfile.COMIC, ReaderProfile.MANGA ->
                ReaderCommand.Zoom(if (action.direction == Direction.UP) -1 else 1)
            ReaderProfile.BOOK, ReaderProfile.READ_ALONG ->
                ReaderCommand.ChangeChapter(if (action.direction == Direction.UP) -1 else 1)
        }
    }

    fun openOverlay(value: ReaderOverlay): ReaderCommand.ShowOverlay {
        require(value != ReaderOverlay.HIDDEN) { "use Back or Menu to hide reader chrome" }
        return show(value)
    }

    private fun show(value: ReaderOverlay): ReaderCommand.ShowOverlay {
        overlay = value
        return ReaderCommand.ShowOverlay(value)
    }
}

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

enum class ReaderControl {
    CLOSE,
    BOOKMARK,
    NAVIGATOR,
    APPEARANCE,
    PREVIOUS,
    SCRUBBER,
    NEXT,
    AUDIO
}

/** The same deterministic graph drives tests and the real reader toolbar. */
class ReaderFocusGraph(profile: ReaderProfile) {
    private val top = listOf(
        ReaderControl.CLOSE,
        ReaderControl.BOOKMARK,
        ReaderControl.NAVIGATOR,
        ReaderControl.APPEARANCE
    )
    private val bottom = buildList {
        add(ReaderControl.PREVIOUS)
        add(ReaderControl.SCRUBBER)
        add(ReaderControl.NEXT)
        if (profile == ReaderProfile.READ_ALONG) add(ReaderControl.AUDIO)
    }

    val controls: List<ReaderControl> = top + bottom
    val initial: ReaderControl = ReaderControl.NAVIGATOR

    fun move(from: ReaderControl, direction: Direction): ReaderControl {
        val row = if (from in top) top else bottom
        val other = if (row === top) bottom else top
        val index = row.indexOf(from).coerceAtLeast(0)
        return when (direction) {
            Direction.LEFT -> row[(index - 1).coerceAtLeast(0)]
            Direction.RIGHT -> row[(index + 1).coerceAtMost(row.lastIndex)]
            Direction.UP -> if (row === top) from else other[project(index, row.size, other.size)]
            Direction.DOWN -> if (row === bottom) from else other[project(index, row.size, other.size)]
        }
    }

    private fun project(index: Int, fromSize: Int, toSize: Int): Int {
        if (fromSize <= 1 || toSize <= 1) return 0
        return (index.toDouble() / (fromSize - 1) * (toSize - 1)).roundToInt()
            .coerceIn(0, toSize - 1)
    }
}
