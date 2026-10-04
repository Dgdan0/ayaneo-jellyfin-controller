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
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.ui.AppIcon
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
 * The Glass Library root (the prototype's Libraries page): a search field and
 * Favourites, "Your libraries" with how many titles they hold, and a glass
 * tile per library, three across, each fanned with its posters
 * ([LibraryTileView]). A tile opens its library's page; the screen pushes it.
 */
class LibraryRootView(
    context: Context,
    private val api: HubApi,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val onOpen: (LibraryView) -> Unit,
    private val onFavourites: () -> Unit,
    private val onSearch: (String) -> Unit,
    private val onFocusChanged: () -> Unit
) : FrameLayout(context) {
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
    private val summary = TextView(context).apply {
        textSize = 12f
        setTextColor(com.pocketds.hub.ui.SettingsCard.GLASS_QUIET)
    }
    /** Loading failures and notices, in the shared status chip. */
    val status = TextView(context).apply {
        textSize = 11f
        setTextColor(colors.mutedText)
    }
    private val tiles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = FocusScrollView(context)
    private var views: List<LibraryView> = emptyList()
    private val tileViews = mutableListOf<LibraryTileView>()
    /** The tile last in focus, kept across a library's page and back. */
    private var selected = 0
    /**
     * [selected] when the root was hidden. Hiding it clears its focus, and the
     * window hands focus to the first tile it finds, which moved [selected]:
     * back from Shows, the ring was on Anime.
     */
    private var resumeAt: Int? = null

    init {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(SIDE_DP), dp(4), dp(SIDE_DP), dp(26))
            clipChildren = false
            clipToPadding = false
        }
        // The ring room round the buttons sits outside the page's edge, so the pills line up with the tiles.
        val ring = dp(PillButton.RING_DP)
        page.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            addView(favourites, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = -ring })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(TextView(context).apply {
            text = "Your libraries"
            textSize = 21f
            typeface = Type.display(context, 800)
            letterSpacing = -.01f
            setTextColor(android.graphics.Color.WHITE)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        page.addView(summary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        page.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        page.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        scroll.clipChildren = false
        scroll.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    val hasLibraries: Boolean get() = views.isNotEmpty()

    /** The library whose tile has focus, else the one last focused. */
    val focusedLibrary: LibraryView?
        get() = tileViews.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 }?.let(views::getOrNull)

    /** Glass: the focused tile's front poster (or its picture) tints the page. */
    val artwork: String?
        get() = views.getOrNull(selected)?.let { LibraryTiles.fan(it).firstOrNull() ?: it.image.ifBlank { null } }

    /**
     * The line under "Your libraries", with how old the counts are when that
     * is news ([com.pocketds.hub.ui.showSummary]).
     */
    fun summarize(message: com.pocketds.hub.state.StatusMessage) = summary.showSummary(message, colors)

    fun show(next: List<LibraryView>) {
        val sameTiles = next.map { it.id } == views.map { it.id }
        views = next
        summary.text = LibraryTiles.summary(next)
        if (!sameTiles) build() else tileViews.forEachIndexed { index, tile -> tile.bind(next[index], api) }
    }

    private fun build() {
        tiles.removeAllViews()
        tileViews.clear()
        val gap = dp(GAP_DP)
        views.chunked(COLUMNS).forEachIndexed { rowIndex, row ->
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            for (column in 0 until COLUMNS) {
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (column > 0) marginStart = gap
                }
                val view = row.getOrNull(column)
                if (view == null) {
                    line.addView(View(context), params)
                    continue
                }
                val index = rowIndex * COLUMNS + column
                val tile = LibraryTileView(context, colors).apply {
                    bind(view, api)
                    FocusDecorator.attach(this, ringVisible)
                    FocusDecorator.listen(this, ringVisible) { _, focused ->
                        if (focused) selected = index
                        onFocusChanged()
                    }
                    activateOnTap { selected = index; onOpen(view) }
                }
                tileViews += tile
                line.addView(tile, params)
            }
            tiles.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = gap
            })
        }
    }

    /**
     * Focus on the tile last in focus, else the first. Coming back from a
     * library's page the root is still hidden when its screen is shown, and a
     * hidden view takes no focus, so it waits a frame. By then clearing the
     * page's focus has handed it to whatever the scroller found first (the
     * search field, or the top row's first tile), which it takes back from.
     */
    fun requestInitialFocus(): Boolean {
        val index = resumeAt ?: selected
        resumeAt = null
        val tile = tileViews.getOrNull(index.coerceIn(0, (tileViews.size - 1).coerceAtLeast(0))) ?: return false
        if (tile.isShown) return tile.requestFocus()
        post { if (tile.isShown) tile.requestFocus() }
        return true
    }

    /** The screen is going: remember the tile in focus before hiding moves it. */
    fun onHide() {
        resumeAt = selected
    }

    fun tileHasFocus(): Boolean = tileViews.any { it.hasFocus() }
    fun inFirstRow(): Boolean = tileViews.take(COLUMNS).any { it.hasFocus() }

    /** The search field takes focus, and the keyboard comes up for it. */
    fun focusSearch() {
        search.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(value: Number) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val COLUMNS = 3
        /** The prototype's Pocket gap between tiles, and its side margin. */
        const val GAP_DP = 12f
        const val SIDE_DP = 22f
    }
}
