package com.pocketds.hub.screens.library

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
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.settings.Prefs
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassSearchField

/**
 * Glass: one library's page, opened from its tile on the Library root (the
 * prototype's folder page). A capsule of the libraries and Favourites switches
 * between them in place; search, Sort and its direction sit at the end of the
 * row; the posters fill seven columns below with their counts and ticks.
 *
 * Search looks through the whole Jellyfin library, as the root's does: the hub
 * has no search scoped to one library.
 */
class LibraryFolderScreen(
    private val api: HubApi,
    /** The libraries, Favourites last. */
    private val views: List<LibraryView>,
    private val startId: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = ContentMode.MEDIA
    override val title = views.firstOrNull { it.id == startId }?.name ?: "Library"
    override val horizontalMode = HorizontalMode.GRID
    override val showsOwnTitle = true
    override val pageArtwork: String? get() = if (::gridView.isInitialized) gridView.artwork else null

    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var gridView: LibraryGridView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var capsule: BlobSegmentedView
    private lateinit var searchButton: TextView
    private lateinit var searchBox: EditText
    /**
     * Until the first posters land, focus waits on the chosen library's name
     * and then moves to them; once you have moved it yourself, a late page
     * leaves it where you put it. Clearing the root's focus had handed it to
     * the capsule's first name, where it stayed.
     */
    private var awaitingGrid = true

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)
        val root = FrameLayout(context)
        overlay = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
        gridView = LibraryGridView(context, api, colors, ringVisible, host) { overlay }.apply {
            // A late page must not pull focus out of the row above it.
            wantsFocus = {
                awaitingGrid.also { awaitingGrid = false } ||
                    (!capsule.hasFocus() && !searchBox.hasFocus() && !searchButton.hasFocus() && !sortControls.hasFocus())
            }
        }
        capsule = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.PILL).apply {
            useGlassTrack()
            setOptions(views.map {
                if (it.kind == FAVOURITES) BlobSegmentedView.Option(it.id, "Favourites", "Favourites", icon = AppIcon.STAR)
                else BlobSegmentedView.Option(it.id, it.name)
            }, startId)
            onPick = { id -> views.firstOrNull { it.id == id }?.let(::showLibrary) }
            onOptionFocused = { host.refreshHints() }
        }
        searchButton = TextView(context).apply {
            contentDescription = "Search your Jellyfin library"
            PillButton.control(this, colors, AppIcon.SEARCH, round = true)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, _ -> host.refreshHints() }
            activateOnTap { openSearchBox() }
        }
        searchBox = EditText(context).apply {
            hint = "Search your Jellyfin library"
            GlassSearchField.style(this, colors, pillDp = PillButton.CONTROL_DP, targetDp = PillButton.CONTROL_DP + 8f)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            visibility = View.GONE
            Styler.makeFocusable(this)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(text.toString()); true } else false
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_UP &&
                    (keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    search(text.toString()); true
                } else false
            }
            setOnFocusChangeListener { _, _ -> host.refreshHints() }
        }
        val side = Styler.dpInt(context, 22f)
        val ring = Styler.dpInt(context, PillButton.RING_DP)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(side, Styler.dpInt(context, 4f), side - ring, 0)
            addView(FocusHorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                addView(capsule)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = Styler.dpInt(context, 6f) })
            addView(searchButton)
            addView(gridView.sortControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Styler.dpInt(context, 2f)
            })
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(side, Styler.dpInt(context, 4f), side, 0)
            })
            addView(gridView.status.apply { setPadding(side, Styler.dpInt(context, 6f), side, 0) })
            addView(gridView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        views.firstOrNull { it.id == startId }?.let(::showLibrary)
        return root
    }

    private fun showLibrary(view: LibraryView) {
        // The Classic Library opens on the library chosen last.
        host?.viewContext?.let { Prefs.of(it).edit().putString(LibraryScreen.KEY_LAST_LIBRARY, view.id).apply() }
        capsule.select(view.id)
        gridView.show(view)
        host?.refreshHints()
    }

    private fun openSearchBox() {
        searchBox.visibility = View.VISIBLE
        searchBox.requestFocus()
        (searchBox.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(searchBox, InputMethodManager.SHOW_IMPLICIT)
        host?.refreshHints()
    }

    private fun closeSearchBox(): Boolean {
        if (searchBox.visibility != View.VISIBLE) return false
        searchBox.visibility = View.GONE
        searchButton.requestFocus()
        host?.refreshHints()
        return true
    }

    private fun search(raw: String) {
        val query = raw.trim()
        if (query.length < 2) {
            host?.notify("Type at least two characters")
            return
        }
        closeSearchBox()
        host?.push(LibraryGridScreen(api, LibraryView(id = query, name = "Search · $query", kind = "search"), ringVisible))
    }

    override fun onShow() {
        if (::gridView.isInitialized) gridView.onShow()
    }

    override fun onHide() {
        if (::gridView.isInitialized) gridView.onHide()
        if (::overlay.isInitialized && overlay.isOpen) overlay.dismiss()
    }

    override fun onDestroyView() {
        if (::gridView.isInitialized) gridView.destroy()
        host = null
    }

    override fun requestInitialFocus(): Boolean {
        if (::overlay.isInitialized && overlay.isOpen) return true
        if (gridView.requestInitialFocus()) return true
        // While the first posters load, focus waits on the library's name and moves to them when they land.
        return capsule.focus(gridView.library?.id)
    }

    override fun hints(): List<ButtonHint> = when {
        ::overlay.isInitialized && overlay.isOpen -> listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        capsule.hasFocus() -> listOf(ButtonHint.activate("Show library"), ButtonHint.back(), ButtonHint.refresh())
        searchBox.hasFocus() -> listOf(ButtonHint.activate("Search"), ButtonHint.back("Close search"))
        searchButton.hasFocus() -> listOf(ButtonHint.activate("Search"), ButtonHint.back())
        gridView.sortControls.hasFocus() -> listOf(ButtonHint.activate("Change"), ButtonHint.back(), ButtonHint.refresh())
        else -> gridView.hints() + ButtonHint.back()
    }

    override fun onPad(action: PadAction): Boolean {
        if (action is PadAction.Step) awaitingGrid = false
        if (::overlay.isInitialized && overlay.onPad(action)) { host?.refreshHints(); return true }
        return when {
            action == PadAction.Back && closeSearchBox() -> true
            action is PadAction.Step && action.direction == Direction.UP && gridView.grid.hasFocus() && gridView.inFirstRow() ->
                capsule.focus(gridView.library?.id)
            action is PadAction.Step && action.direction == Direction.DOWN &&
                (capsule.hasFocus() || searchButton.hasFocus() || gridView.sortControls.hasFocus()) -> gridView.requestInitialFocus()
            action == PadAction.Activate && searchBox.hasFocus() -> { search(searchBox.text.toString()); true }
            else -> gridView.onPad(action)
        }
    }

    private companion object {
        const val FAVOURITES = "favorites"
    }
}
