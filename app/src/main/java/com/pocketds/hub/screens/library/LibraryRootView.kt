package com.pocketds.hub.screens.library

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassSearchField
import com.pocketds.hub.ui.showSummary

/**
 * A Glass Library root (the prototype's Libraries page), Movies and TV or
 * Books: for Movies and TV a search field and Favourites first, then the
 * heading with how much the libraries hold, and a glass tile per library,
 * three across ([LibraryTileView] in a [LibraryArrangeGrid]). A tile opens its
 * library; the screen pushes the page.
 *
 * The tiles come in the hub's order and can be arranged (#15). The Arrange
 * control beside the heading (Done while arranging) or a hold on a tile starts
 * it: the tiles wiggle and show their grips, Ⓐ picks up the tile in focus and
 * puts it down, the D-pad moves it, Ⓑ puts a lifted tile back where it was and
 * otherwise finishes, and Ⓨ goes back to A to Z; a pointer drags. Each drop
 * saves through [order], which puts the tiles back and says why if the save
 * fails. Settings › Libraries moves rows by the same grip.
 */
class LibraryRootView(
    context: Context,
    private val api: HubApi,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    heading: String,
    /** Movies and TV: the search field and Favourites over the heading. */
    private val withSearch: Boolean,
    /** This side's order: what a move changes and saves. */
    val order: LibraryOrderEditor,
    private val onOpen: (String) -> Unit,
    private val onFavourites: () -> Unit = {},
    private val onSearch: (String) -> Unit = {},
    private val onFocusChanged: () -> Unit
) : FrameLayout(context), LibraryArrangeGrid.Listener {
    /**
     * A tile: [id] is what the hub orders. A [fixed] one is not arranged and
     * stays after the rest (reading lists). [artwork] tints the page while it
     * has focus.
     */
    class Tile(val id: String, val artwork: String?, val fixed: Boolean = false, val bind: (LibraryTileView) -> Unit)

    val search: EditText = EditText(context).apply {
        hint = "Search your Jellyfin library"
        GlassSearchField.style(this, colors, pillDp = PillButton.CONTROL_DP, targetDp = PillButton.CONTROL_DP + 2 * PillButton.RING_DP)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        Styler.makeFocusable(this)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { onSearch(text.toString()); true } else false
        }
        // A keyboard that sends a plain Enter rather than the editor action.
        setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_UP &&
                (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                onSearch(text.toString()); true
            } else false
        }
        setOnFocusChangeListener { _, _ -> onFocusChanged() }
    }
    val favourites: TextView = TextView(context).apply {
        text = "Favourites"
        contentDescription = "Favourites"
        PillButton.control(this, colors, AppIcon.STAR)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, _ -> onFocusChanged() }
        activateOnTap { onFavourites() }
    }
    /** Arrange, or Done while arranging: the way in for the pad, and for a pointer besides holding a tile. */
    val arrange: TextView = TextView(context).apply {
        PillButton.control(this, colors, AppIcon.ARRANGE)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, _ -> onFocusChanged() }
        activateOnTap { if (arranging) finishArranging() else startArranging(focusTiles = true) }
        visibility = View.GONE
    }
    private val summary = TextView(context).apply {
        textSize = 12f
        setTextColor(com.pocketds.hub.ui.SettingsCard.GLASS_QUIET)
    }
    /** Loading failures and notices, in the shared status chip. */
    val status = TextView(context).apply {
        textSize = 11f
        setTextColor(colors.mutedText)
    }
    /** Not clipped: a focused tile's ring stands outside it, and a lifted one grows. */
    private val grid = LibraryArrangeGrid(context, colors, LibraryArrangeGrid.Style.TILES).apply { listener = this@LibraryRootView }
    private val scroll = FocusScrollView(context)
    private var tiles: List<Tile> = emptyList()
    private val tileViews = LinkedHashMap<String, LibraryTileView>()
    /** The tile last in focus, by id, kept across a library's page and back and across moves. */
    private var selected: String? = null
    /**
     * [selected] when the root was hidden. Hiding it clears its focus, and the
     * window hands focus to the first tile it finds, which moved [selected]:
     * back from Shows, the ring was on Anime.
     */
    private var resumeAt: String? = null

    var arranging = false
        private set

    init {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SIDE_DP), dp(if (withSearch) 4 else 3), dp(SIDE_DP), dp(26))
            clipChildren = false
            clipToPadding = false
        }
        // The ring room round the buttons sits outside the page's edge, so the pills line up with the tiles.
        val ring = dp(PillButton.RING_DP)
        if (withSearch) page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            addView(favourites, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = -ring })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            // As tall as Arrange whether it shows or not, so the heading never moves.
            minimumHeight = dp(PillButton.CONTROL_DP + 2 * PillButton.RING_DP)
            addView(TextView(context).apply {
                text = heading
                textSize = 21f
                typeface = Type.display(context, 800)
                letterSpacing = -.01f
                setTextColor(android.graphics.Color.WHITE)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrange, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = -ring })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (withSearch) 8 else 0)
        })
        page.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        page.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        scroll.clipChildren = false
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        showArrange()
    }

    val hasLibraries: Boolean get() = tiles.isNotEmpty()

    /** Glass: the tile in focus (else the one last in focus) tints the page. */
    val artwork: String?
        get() = tiles.firstOrNull { it.id == selected }?.artwork ?: tiles.firstOrNull()?.artwork

    /**
     * The line under the heading, with how old the counts are when that is
     * news ([com.pocketds.hub.ui.showSummary]).
     */
    fun summarize(message: com.pocketds.hub.state.StatusMessage) = summary.showSummary(message, colors)

    /** The libraries in the hub's order ([orderName] is "name" or "custom"), fixed tiles last. */
    fun show(next: List<Tile>, orderName: String) {
        val sameTiles = next.map { it.id }.toSet() == tiles.map { it.id }.toSet() && next.size == tiles.size
        tiles = next
        val arranged = next.filterNot { it.fixed }.map { it.id }
        order.show(arranged, orderName)
        if (sameTiles) {
            next.forEach { tile -> tileViews[tile.id]?.let(tile.bind) }
            grid.lift(-1)
            grid.order(arranged)
        } else build()
        showArrange()
    }

    private fun build() {
        tileViews.clear()
        val arranged = tiles.filterNot { it.fixed }
        val built = (arranged + tiles.filter { it.fixed }).map { tile ->
            tile.id to LibraryTileView(context, colors, ringVisible).apply {
                tile.bind(this)
                FocusDecorator.attach(this, ringVisible)
                FocusDecorator.listen(this, ringVisible) { _, focused ->
                    if (focused) selected = tile.id
                    onFocusChanged()
                }
                activateOnTap { selected = tile.id; onOpen(tile.id) }
                tileViews[tile.id] = this
            }
        }
        grid.setTiles(built, arranged.size)
    }

    /** Arranging asks for two libraries at least. */
    private fun showArrange() {
        arrange.visibility = if (grid.movableCount > 1) View.VISIBLE else View.GONE
        if (arrange.visibility != View.VISIBLE && arranging) finishArranging()
        arrange.text = if (arranging) "Done" else "Arrange"
        arrange.contentDescription = if (arranging) "Done arranging" else "Arrange libraries"
        PillButton.setPrimary(arrange, colors, arranging)
        // The lit face takes the ink; the icon says what the press does.
        val ink = arrange.currentTextColor
        val size = dp(14)
        arrange.setCompoundDrawables(AppIconDrawable(if (arranging) AppIcon.CHECK else AppIcon.ARRANGE, ink).apply { setBounds(0, 0, size, size) },
            null, null, null)
    }

    // ----------------------------------------------------------- arranging

    private fun startArranging(focusTiles: Boolean) {
        if (arranging || grid.movableCount < 2) return
        arranging = true
        grid.arranging = true
        showArrange()
        if (focusTiles) requestInitialFocus()
        onFocusChanged()
    }

    /** Ends arranging; a library still lifted is put down where it is, and saves. */
    fun finishArranging() {
        if (!arranging) return
        if (order.session.isLifted) drop()
        arranging = false
        grid.arranging = false
        showArrange()
        onFocusChanged()
    }

    private fun pickUp(place: Int) {
        if (!order.session.pickUp(place)) return
        grid.lift(place)
        onFocusChanged()
    }

    private fun drop() {
        grid.lift(-1)
        order.drop()
        onFocusChanged()
    }

    private fun moveLifted(direction: Direction) {
        val before = order.session.lifted
        val after = order.session.step(direction, grid.columns)
        if (after == before) return
        grid.order(order.session.ids)
        // The tile keeps focus as it moves; a row the page had scrolled away comes back.
        grid.reveal(after)
    }

    /** Ⓑ on a lifted tile: back where it was picked up, nothing saved. */
    private fun putBack() {
        grid.order(order.session.putBack())
        grid.lift(-1)
        onFocusChanged()
    }

    /**
     * Back, from the pad or the system: a lifted tile goes back where it was,
     * else arranging finishes. False when there is nothing to undo here.
     */
    fun back(): Boolean = when {
        order.session.isLifted -> { putBack(); true }
        arranging -> { finishArranging(); true }
        else -> false
    }

    /** The order changed under the tiles: a move, a failed save put back, or a save landing. */
    fun reorder(ids: List<String>) {
        grid.order(ids)
        grid.lift(if (order.session.isLifted) order.session.lifted else -1)
        onFocusChanged()
    }

    override fun onPointerLift(place: Int) {
        if (!arranging) startArranging(focusTiles = false)
        if (order.session.isLifted) drop()
        grid.tile(place)?.requestFocus()
        pickUp(place)
    }

    override fun onPointerOver(place: Int) {
        if (order.session.moveLiftedTo(place)) grid.order(order.session.ids)
    }

    override fun onPointerDrop() {
        if (order.session.isLifted) drop()
    }

    /**
     * The pad while arranging, and the moves between the parts of the root:
     * Up from the top tiles reaches the search field (the window's own search
     * skipped it for the tabs) or Arrange above the last column, Down comes
     * back to the tile last in focus.
     */
    fun onPad(action: PadAction): Boolean {
        if (arranging) {
            val place = grid.focusedPlace()
            val lifted = order.session.isLifted
            when {
                action == PadAction.Activate && arrange.hasFocus() -> finishArranging()
                action == PadAction.Activate && lifted -> drop()
                action == PadAction.Activate && place in 0 until grid.movableCount -> pickUp(place)
                // A fixed tile (reading lists) is not opened while arranging.
                action == PadAction.Activate -> Unit
                action == PadAction.Back -> back()
                action == PadAction.Secondary -> if (order.isCustom) {
                    grid.lift(-1)
                    order.aToZ()
                    onFocusChanged()
                }
                action is PadAction.Step && lifted -> moveLifted(action.direction)
                action is PadAction.Step -> return route(action.direction)
                // Nothing else while arranging; leaving the root (L1, R1) finishes it.
                action == PadAction.Primary || action == PadAction.Refresh -> Unit
                else -> return false
            }
            return true
        }
        return (action as? PadAction.Step)?.let { route(it.direction) } ?: false
    }

    private fun route(direction: Direction): Boolean = when (direction) {
        Direction.UP -> when {
            inFirstRow() -> {
                val column = grid.focusedPlace() % grid.columns
                val target = if (arrange.isShown && (!withSearch || arranging || column == grid.columns - 1)) arrange
                    else if (withSearch) search else null
                target?.requestFocus() ?: false
            }
            arrange.hasFocus() && withSearch && !arranging -> favourites.requestFocus()
            arrange.hasFocus() && arranging -> true
            else -> false
        }
        Direction.DOWN -> when {
            search.hasFocus() -> requestInitialFocus()
            favourites.hasFocus() -> if (arrange.isShown) arrange.requestFocus() else requestInitialFocus()
            arrange.hasFocus() -> requestInitialFocus()
            else -> false
        }
        else -> false
    }

    /** What A, Y and B do while arranging, or on Arrange; null when the screen's own hints apply. */
    fun hints(): List<ButtonHint>? = when {
        arranging && order.session.isLifted -> listOf(ButtonHint.activate("Drop"), ButtonHint.back("Put back"))
        arranging -> buildList {
            val place = grid.focusedPlace()
            if (arrange.hasFocus()) add(ButtonHint.activate("Done"))
            else if (place in 0 until grid.movableCount) add(ButtonHint.activate("Pick up"))
            if (order.isCustom) add(ButtonHint.secondary("A to Z"))
            add(ButtonHint.back("Done"))
        }
        arrange.hasFocus() -> listOf(ButtonHint.activate("Arrange"), ButtonHint.refresh())
        else -> null
    }

    /**
     * Focus on the tile last in focus, else the first. Coming back from a
     * library's page the root is still hidden when its screen is shown, and a
     * hidden view takes no focus, so it waits a frame. By then clearing the
     * page's focus has handed it to whatever the scroller found first (the
     * search field, or the top row's first tile), which it takes back from.
     */
    fun requestInitialFocus(): Boolean {
        val id = resumeAt ?: selected
        resumeAt = null
        val tile = id?.let(tileViews::get) ?: grid.tile(0) ?: return false
        if (tile.isShown) return tile.requestFocus()
        post { if (tile.isShown) tile.requestFocus() }
        return true
    }

    /** The screen is going: arranging ends, and the tile in focus is remembered before hiding moves it. */
    fun onHide() {
        finishArranging()
        resumeAt = selected
    }

    fun tileHasFocus(): Boolean = grid.focusedPlace() >= 0
    fun inFirstRow(): Boolean = grid.focusedPlace() in 0 until grid.columns

    /** The search field takes focus, and the keyboard comes up for it. */
    fun focusSearch() {
        search.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(value: Number) = Styler.dpInt(context, value.toFloat())

    private companion object {
        /** The prototype's Pocket side margin. */
        const val SIDE_DP = 22f
    }
}
