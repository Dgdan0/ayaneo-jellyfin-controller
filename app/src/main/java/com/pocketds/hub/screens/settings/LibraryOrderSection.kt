package com.pocketds.hub.screens.settings

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
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
 * shows them for this Jellyfin profile, a row each. X and Y move the library
 * in focus up and down (a pointer has the arrows on its row), and Back to A–Z
 * forgets the order. A change shows at once and saves through
 * [LibraryOrderEditor], as arranging a Library root does, so a failed save
 * puts the list back and says why.
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
    private inner class Side(val mode: ContentMode, val title: String) {
        val editor = LibraryOrderEditor(mode, api, saveScope,
            onOrder = { render() },
            onFailed = { message -> host.notify("The new order could not be saved · $message") },
            onReset = { load(this, force = true) })
        /** Each library's name and kind, by id. */
        var names: Map<String, Pair<String, String>> = emptyMap()
        var loaded = false
        /** Why the list is not there yet: loading, or what failed. */
        var problem: String? = null
        var job: Job? = null
    }

    private val sides = listOf(Side(ContentMode.MEDIA, "Movies and TV"), Side(ContentMode.BOOKS, "Books"))

    /** The library row in focus, by side and id. */
    private var focused: Pair<ContentMode, String>? = null
    /** The side whose Back to A–Z has focus. */
    private var aToZFocused: ContentMode? = null

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

    /** The two cards, one per side; [card] adds one to the pane under [tag]. */
    fun build(card: (String) -> SettingsCard) {
        focused = null
        aToZFocused = null
        val context = host.viewContext
        val glass = Theme.onGlass(colors)
        sides.forEachIndexed { index, side ->
            val ids = side.editor.ids
            val panel = card("libraries:${side.mode.stored}")
                .title(side.title, when {
                    !side.loaded -> ""
                    side.editor.isCustom -> "Your order"
                    else -> "A to Z"
                })
            if (index == 0) panel.hint("X and Y move the library in focus up or down. Every device shows this order for this Jellyfin profile.")
            side.problem?.let(panel::hint)
            if (!side.loaded) return@forEachIndexed
            if (ids.isEmpty()) panel.hint("No libraries were found.")
            ids.forEachIndexed { place, id ->
                val (name, kind) = side.names[id] ?: return@forEachIndexed
                val row = LibraryOrderRowView(context, colors, ringVisible, name, kind, place, ids.size).apply {
                    tag = "library:${side.mode.stored}:$id"
                    onMove = { delta -> move(side, id, delta) }
                    FocusDecorator.listen(this, ringVisible) { _, has ->
                        val mine = side.mode to id
                        focused = if (has) mine else focused.takeUnless { it == mine }
                        host.refreshHints()
                    }
                }
                panel.body(row, topDp = if (place == 0) 8f else 2f, fill = true)
            }
            if (side.editor.isCustom) panel.body(TextView(context).apply {
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
            }, topDp = 10f)
        }
    }

    private fun move(side: Side, id: String, delta: Int) {
        val ids = side.editor.ids
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        side.editor.move(from, to)
    }

    /** X and Y on a library row, A on Back to A–Z; null elsewhere. */
    fun hints(): List<ButtonHint>? = when {
        focused != null -> listOf(ButtonHint.primary("Move up"), ButtonHint.secondary("Move down"))
        aToZFocused != null -> listOf(ButtonHint.activate("A to Z"))
        else -> null
    }

    fun onPad(action: PadAction): Boolean {
        val (mode, id) = focused ?: return false
        val side = sides.first { it.mode == mode }
        return when (action) {
            PadAction.Primary -> { move(side, id, -1); true }
            PadAction.Secondary -> { move(side, id, 1); true }
            // A row has nothing to switch; A is not a move.
            PadAction.Activate -> true
            else -> false
        }
    }
}

/**
 * One library in Settings › Libraries: its name over its kind, and an up and
 * a down arrow for a pointer (the pad uses X and Y, which the hint bar names).
 * The first has no up and the last no down. On Glass it sits straight on its
 * card and rings while focused, as a switch row does.
 */
internal class LibraryOrderRowView(
    context: Context,
    colors: PocketColors,
    ringVisible: () -> Boolean,
    name: String,
    kind: String,
    place: Int,
    count: Int
) : LinearLayout(context) {
    var onMove: (Int) -> Unit = {}

    init {
        val glass = Theme.onGlass(colors)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (glass) {
            setPadding(dp(8f), dp(5f), dp(4f), dp(5f))
            foreground = Styler.focusOutline(context, colors, 10f, 2f)
        } else {
            setPadding(dp(14f), dp(8f), dp(8f), dp(8f))
            background = Styler.cardBackground(context, colors, cornerDp = 16f, focusStrokeDp = 2f)
        }
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "$name, $kind, ${place + 1} of $count"
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply { text = name; textSize = if (glass) 13f else 14f; textWeight(700); setTextColor(colors.primaryText) })
            addView(TextView(context).apply {
                text = kind; textSize = 11.5f; setTextColor(if (glass) SettingsCard.GLASS_QUIET else colors.mutedText)
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(1f) })
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(arrow(colors, glass, AppIcon.MOVE_UP, "Move $name up", place > 0) { onMove(-1) })
        addView(arrow(colors, glass, AppIcon.MOVE_DOWN, "Move $name down", place < count - 1) { onMove(1) },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(2f) })
        FocusDecorator.attach(this, ringVisible, scale = false)
        // A tap on the row puts focus on it, so the hint bar's Move up and Move down apply to it.
        activateOnTap { }
    }

    /** A round arrow for a pointer; not a focus stop, since the pad has X and Y. Where there is nowhere to go it keeps its room. */
    private fun arrow(colors: PocketColors, glass: Boolean, icon: AppIcon, label: String, enabled: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            contentDescription = label
            if (glass) PillButton.control(this, colors, icon, round = true) else {
                // A round chip with the arrow in its middle: the padding either side of the icon is equal.
                val size = dp(16f)
                setCompoundDrawables(com.pocketds.hub.ui.AppIconDrawable(icon, colors.primaryText).apply { setBounds(0, 0, size, size) }, null, null, null)
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                background = com.pocketds.hub.ui.ThemeGradientDrawable.oval(androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x14))
            }
            isFocusable = false
            visibility = if (enabled) View.VISIBLE else View.INVISIBLE
            setOnClickListener { onTap() }
        }

    private fun dp(value: Float) = Styler.dpInt(context, value)
}
