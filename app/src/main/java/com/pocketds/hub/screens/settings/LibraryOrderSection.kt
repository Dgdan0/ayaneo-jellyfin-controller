package com.pocketds.hub.screens.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.LibraryArrangeGrid
import com.pocketds.hub.screens.library.LibraryOrderEditor
import com.pocketds.hub.screens.library.LibraryTiles
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SettingsCard
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings › Libraries (#15): each side's libraries in the order every device
 * shows them for this Jellyfin profile, a row each with the grip at its end.
 * A pointer drags a row by its grip and the others make room; on the pad Ⓐ
 * picks the row in focus up (it lifts and its grip lights), the D-pad moves
 * it, Ⓐ puts it down and Ⓑ puts it back where it was. Back to A–Z, under a
 * side that has its own order, forgets it.
 *
 * The rows are a [LibraryArrangeGrid], as the Library roots' tiles are, and a
 * drop saves through [LibraryOrderEditor] as theirs does: at once, and a
 * failed save slides the rows back and says why.
 */
internal class LibraryOrderSection(
    private val host: ScreenHost,
    private val api: HubApi,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    /** Reads; Settings cancels them when it hides. */
    private val scope: CoroutineScope,
    /** Saves, which hiding must not cancel half way. */
    private val saveScope: CoroutineScope,
    /** Draws the section again; focus comes back to the row it was on. */
    private val render: () -> Unit
) {
    private inner class Side(val mode: ContentMode, val title: String) : LibraryArrangeGrid.Listener {
        val editor = LibraryOrderEditor(mode, api, saveScope,
            onOrder = { ids -> showOrder(this, ids) },
            onFailed = { message -> host.notify("The new order could not be saved · $message") },
            onReset = { load(this, force = true) })
        /** Each library's name and kind, by id. */
        var names: Map<String, Pair<String, String>> = emptyMap()
        var loaded = false
        /** Why the list is not there yet: loading, or what failed. */
        var problem: String? = null
        var job: Job? = null
        /** What is on screen for this side, changed in place by a move. */
        var card: SettingsCard? = null
        var grid: LibraryArrangeGrid? = null
        var aToZ: View? = null
        val rows = LinkedHashMap<String, LibraryOrderRowView>()

        override fun onPointerLift(place: Int) {
            // A row lifted on the other side, by the pad, goes back first.
            sides.filter { it !== this && it.editor.session.isLifted }.forEach(::putBack)
            if (editor.session.isLifted) drop(this)
            grid?.tile(place)?.requestFocus()
            pickUp(this, place)
        }

        override fun onPointerOver(place: Int) {
            if (editor.session.moveLiftedTo(place)) grid?.order(editor.session.ids)
        }

        override fun onPointerDrop() {
            if (editor.session.isLifted) drop(this)
        }
    }

    private val sides = listOf(Side(ContentMode.MEDIA, "Movies and TV"), Side(ContentMode.BOOKS, "Books"))

    /** The library row in focus, by side and id. */
    private var focused: Pair<ContentMode, String>? = null
    /** The side whose Back to A–Z has focus. */
    private var aToZFocused: ContentMode? = null

    private val liftedSide: Side? get() = sides.firstOrNull { it.editor.session.isLifted }

    /** Reads each side's order unless this section already has the newest. */
    fun load() = sides.forEach { if (!it.loaded || it.editor.stale) load(it, force = false) }

    private fun load(side: Side, force: Boolean) {
        if (side.job?.isActive == true && !force) return
        side.job?.cancel()
        if (!side.loaded) side.problem = "Loading…"
        side.job = scope.launch {
            val failure = when (side.mode) {
                ContentMode.MEDIA -> when (val result = api.library()) {
                    is HubResult.Ok -> {
                        side.names = result.value.views.associate { it.id to (it.name to LibraryTiles.kindLabel(it.kind)) }
                        side.editor.show(result.value.views.map { it.id }, result.value.order)
                        null
                    }
                    is HubResult.Failed -> result.message
                }
                ContentMode.BOOKS -> when (val result = api.readingLibraries()) {
                    is HubResult.Ok -> {
                        side.names = result.value.libraries.associate { it.id to (it.title to LibraryTiles.readingKindLabel(it.kind)) }
                        side.editor.show(result.value.libraries.map { it.id }, result.value.order)
                        null
                    }
                    is HubResult.Failed -> result.message
                }
            }
            if (failure == null) side.loaded = true
            side.problem = failure?.takeUnless { side.loaded }
            if (failure != null && side.loaded) host.notify(failure)
            render()
        }
    }

    /** The two cards, one per side; [card] adds one to the pane under its tag. */
    fun build(card: (String) -> SettingsCard) {
        focused = null
        aToZFocused = null
        val context = host.viewContext
        val glass = Theme.onGlass(colors)
        sides.forEachIndexed { index, side ->
            side.card = null
            side.grid = null
            side.aToZ = null
            side.rows.clear()
            val ids = side.editor.ids
            val panel = card("libraries:${side.mode.stored}").title(side.title, trailing(side))
            side.card = panel
            if (index == 0) panel.hint("Drag a library by its grip, or press A on it and move it. " +
                "Every device shows this order for this Jellyfin profile.")
            side.problem?.let(panel::hint)
            if (!side.loaded) return@forEachIndexed
            if (ids.isEmpty()) {
                panel.hint("No libraries were found.")
                return@forEachIndexed
            }
            val rows = ids.mapNotNull { id ->
                val (name, kind) = side.names[id] ?: return@mapNotNull null
                id to LibraryOrderRowView(context, colors, ringVisible, name, kind).apply {
                    tag = "library:${side.mode.stored}:$id"
                    FocusDecorator.listen(this, ringVisible) { _, has ->
                        val mine = side.mode to id
                        focused = if (has) mine else focused.takeUnless { it == mine }
                        host.refreshHints()
                    }
                    side.rows[id] = this
                }
            }
            val grid = LibraryArrangeGrid(context, colors, LibraryArrangeGrid.Style.ROWS).apply {
                listener = side
                setTiles(rows, rows.size)
            }
            side.grid = grid
            describe(side)
            panel.body(grid, topDp = 8f, fill = true)
            val reset = TextView(context).apply {
                text = "Back to A–Z"
                contentDescription = "Back to A to Z: ${side.title}"
                tag = "atoz:${side.mode.stored}"
                if (glass) PillButton.control(this, colors, AppIcon.SORT) else {
                    textSize = 12f; textWeight(700); setTextColor(colors.primaryText); gravity = Gravity.CENTER
                    setPadding(Styler.dpInt(context, 14f), 0, Styler.dpInt(context, 14f), 0)
                    minimumHeight = Styler.dpInt(context, 36f)
                    background = Styler.chipBackground(context, colors)
                }
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                FocusDecorator.listen(this, ringVisible) { _, has ->
                    aToZFocused = if (has) side.mode else aToZFocused.takeUnless { it == side.mode }
                    host.refreshHints()
                }
                activateOnTap { side.editor.aToZ() }
                visibility = if (side.editor.isCustom) View.VISIBLE else View.GONE
            }
            side.aToZ = reset
            panel.body(reset, topDp = 10f)
        }
    }

    private fun trailing(side: Side): String = when {
        !side.loaded -> ""
        side.editor.isCustom -> "Your order"
        else -> "A to Z"
    }

    /** A move, a failed save put back, or a save landing: the rows slide, the heading says whose order it is. */
    private fun showOrder(side: Side, ids: List<String>) {
        val grid = side.grid ?: return
        grid.order(ids)
        grid.lift(if (side.editor.session.isLifted) side.editor.session.lifted else -1)
        side.card?.trailing(trailing(side))
        // Back to A–Z shows once there is an order to forget; it goes only on the reload after A to Z.
        if (side.editor.isCustom) side.aToZ?.visibility = View.VISIBLE
        describe(side)
        host.refreshHints()
    }

    /** Each row says where it is, for a screen reader. */
    private fun describe(side: Side) {
        val ids = side.editor.ids
        ids.forEachIndexed { place, id -> side.rows[id]?.place(place, ids.size) }
    }

    private fun pickUp(side: Side, place: Int) {
        if (!side.editor.session.pickUp(place)) return
        side.grid?.lift(place)
        host.refreshHints()
    }

    private fun drop(side: Side) {
        side.grid?.lift(-1)
        side.editor.drop()
        describe(side)
        host.refreshHints()
    }

    private fun putBack(side: Side) {
        side.grid?.order(side.editor.session.putBack())
        side.grid?.lift(-1)
        host.refreshHints()
    }

    private fun step(side: Side, direction: Direction) {
        val before = side.editor.session.lifted
        val after = side.editor.session.step(direction, 1)
        if (after == before) return
        side.grid?.order(side.editor.session.ids)
        side.grid?.reveal(after)
    }

    /** Leaving Settings: a row still lifted is put down where it is, and saves. */
    fun finish() {
        liftedSide?.let(::drop)
    }

    /** The system's Back: a lifted row goes back where it was. False when none is lifted. */
    fun putBackIfLifted(): Boolean {
        val side = liftedSide ?: return false
        putBack(side)
        return true
    }

    /** A and B while a row is lifted, A on a row or on Back to A–Z; null elsewhere. */
    fun hints(): List<ButtonHint>? = when {
        liftedSide != null -> listOf(ButtonHint.activate("Drop"), ButtonHint.back("Put back"))
        focused != null -> listOf(ButtonHint.activate("Pick up"))
        aToZFocused != null -> listOf(ButtonHint.activate("A to Z"))
        else -> null
    }

    fun onPad(action: PadAction): Boolean {
        liftedSide?.let { side ->
            when (action) {
                PadAction.Activate -> drop(side)
                PadAction.Back -> putBack(side)
                is PadAction.Step -> if (action.direction == Direction.UP || action.direction == Direction.DOWN) step(side, action.direction)
                // L1 and R1 leave Settings, which puts the row down.
                is PadAction.Section -> return false
                else -> Unit
            }
            return true
        }
        val (mode, id) = focused ?: return false
        if (action != PadAction.Activate) return false
        val side = sides.first { it.mode == mode }
        val place = side.editor.ids.indexOf(id)
        if (place < 0) return false
        pickUp(side, place)
        return true
    }
}

/**
 * One library in Settings › Libraries: its name over its kind, with room at
 * its end for the grip the grid draws there. Lifted (activated), it stands on
 * a raised panel. On Glass it sits straight on its card and rings while
 * focused, as a switch row does.
 */
internal class LibraryOrderRowView(
    context: Context,
    colors: PocketColors,
    ringVisible: () -> Boolean,
    private val name: String,
    private val kind: String
) : LinearLayout(context) {
    private val glass = Theme.onGlass(colors)
    /** The panel a lifted row stands on: the page's glass, or Classic's card colour. */
    private val raised = if (glass) com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, 10f)).also { background = null }
        else com.pocketds.hub.ui.ThemeGradientDrawable.rounded(Styler.dp(context, 16f), colors.cardSurface)
    private val resting = if (glass) null else Styler.cardBackground(context, colors, cornerDp = 16f, focusStrokeDp = 2f)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val end = dp(LibraryArrangeGrid.ROW_GRIP_ROOM_DP)
        if (glass) {
            setPadding(dp(8f), dp(5f), end, dp(5f))
            foreground = Styler.focusOutline(context, colors, 10f, 2f)
        } else {
            setPadding(dp(14f), dp(8f), end, dp(8f))
            background = resting
        }
        minimumHeight = dp(PillButton.CONTROL_DP + 2 * PillButton.RING_DP + 4f)
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "$name, $kind"
        addView(TextView(context).apply { text = name; textSize = if (glass) 13f else 14f; textWeight(700); setTextColor(colors.primaryText) })
        addView(TextView(context).apply {
            text = kind; textSize = 11.5f; setTextColor(if (glass) SettingsCard.GLASS_QUIET else colors.mutedText)
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1f) })
        FocusDecorator.attach(this, ringVisible, scale = false)
        // A tap on the row puts focus on it, so A (or its hint) picks it up.
        activateOnTap { }
    }

    /** Where it is in its list, for a screen reader. */
    fun place(index: Int, count: Int) {
        contentDescription = "$name, $kind, ${index + 1} of $count"
    }

    override fun setActivated(activated: Boolean) {
        super.setActivated(activated)
        background = if (activated) raised else resting
    }

    private fun dp(value: Float) = Styler.dpInt(context, value)
}
