package com.pocketds.hub

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.FocusGuard
import com.pocketds.hub.ui.FloatingPlayerView
import com.pocketds.hub.ui.StripNav
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.PadEventRouter
import com.pocketds.hub.input.PadNames
import com.pocketds.hub.input.PadTicker
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.nav.SectionStacks
import com.pocketds.hub.nav.StatusStripView
import com.pocketds.hub.nav.TabBarView
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.screens.PlaceholderScreen
import com.pocketds.hub.screens.downloads.DownloadsScreen
import com.pocketds.hub.screens.discover.DiscoverScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.screens.system.PadTestScreen
import com.pocketds.hub.settings.HapticSettings
import com.pocketds.hub.ui.KeyHaptics
import com.pocketds.hub.ui.Theme

/**
 * The one Activity.
 *
 * Everything is a screen swapped into this window rather than a separate
 * Activity, because the input pipeline is per-window state that has to exist
 * exactly once: one router, one frame ticker, one dead-zone, one focus owner.
 * Separate Activities would rebuild the ticker on every navigation, re-run the
 * source latches, and re-inflate the chrome that is supposed to be the constant
 * thing on screen.
 *
 * The cost, stated plainly: a hand-rolled back stack, and no state restoration
 * after process death -- we restart at the section root. Accepted.
 */
class HubActivity : AppCompatActivity(), ScreenHost {

    private val colors by lazy { Theme.colors(this) }

    private lateinit var router: PadEventRouter
    private lateinit var ticker: PadTicker
    private lateinit var sections: SectionStacks

    private lateinit var tabBar: TabBarView
    private lateinit var hintBar: HintBarView
    private lateinit var overlay: FrameLayout
    private lateinit var player: FloatingPlayerView

    /**
     * Whether the pad is driving the trailer window rather than the screen.
     *
     * An explicit mode, rather than making the window a focus target. It lives
     * in the chrome layer, outside the content that the focus guard confines
     * movement to, and letting directional search reach across that boundary is
     * exactly the class of bug that had the selection escaping into the tab bar.
     * Start takes control; X hands it back.
     */
    private var trailerMode = false
    private lateinit var statusStrip: StatusStripView
    private lateinit var content: FrameLayout

    private val focusTick = KeyHaptics.RepeatGate(80L)

    /**
     * The view each attached screen created. Kept here rather than on the Screen
     * interface: the host owns the view's attachment to the window, so it should
     * own the reference too, and implementers are not made to hold state they do
     * not manage.
     */
    private val views = HashMap<Screen, View>()

    private lateinit var api: HubApi

    private val sectionTitles = listOf("Discover", "Library", "Downloads", "Manage", "Pad")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Intent extras first: scripts/dev.sh seed pushes the URL and token in
        // this way, because typing a 43-character token on a handheld after every
        // clean install is a reason not to test.
        seedFromIntent()
        api = HubClient(this)

        router = PadEventRouter(emit = ::onPadAction)
        ticker = PadTicker(router)
        sections = SectionStacks(sectionTitles.size)

        setContentView(buildChrome())

        sections.push(DiscoverScreen(api, ::ringVisible).also { attach(it) })
        sections.select(1); sections.push(PlaceholderScreen("Library", 60, 4, ::ringVisible).also { attach(it) })
        sections.select(2); sections.push(DownloadsScreen(api, ::ringVisible).also { attach(it) })
        sections.select(3); sections.push(PlaceholderScreen("Manage", 8, 1, ::ringVisible).also { attach(it) })
        sections.select(4); sections.push(PadTestScreen().also { attach(it) })
        sections.select(0)

        showCurrent()
        DebugLog.log("nav", "HubActivity created with ${sectionTitles.size} sections")
    }

    /**
     * launchMode is singleTask, so a second `am start` on an already-running app
     * arrives here rather than at onCreate. Without this override, `dev.sh seed`
     * silently does nothing whenever the app happens to be open — which is
     * almost always, since deploy launches it.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        seedFromIntent()
        (sections.stack().peek() as? Screen)?.onShow()
    }

    /** Accepts `-e hub_url ... -e hub_token ...` from dev.sh seed. */
    private fun seedFromIntent() {
        val url = intent?.getStringExtra("hub_url")
        val token = intent?.getStringExtra("hub_token")
        if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            HubSettings.save(this, url, token)
            DebugLog.log("auth", "hub seeded from intent: $url")
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun buildChrome(): View {
        // The chrome sits inside a frame so the floating trailer window can be
        // laid over all of it, hint bar included.
        overlay = FrameLayout(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }

        tabBar = TabBarView(this, colors).apply {
            setSections(sectionTitles)
            onSelect = { index ->
                router.onPointer()
                if (sections.select(index)) showCurrent()
            }
        }
        root.addView(tabBar)

        statusStrip = StatusStripView(this, colors)
        root.addView(statusStrip)

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            clipChildren = false
        }
        root.addView(content)

        hintBar = HintBarView(this, colors).apply {
            // A pointer user reaches every contextual action through the same
            // widget that labels it for a pad user.
            onAction = { action ->
                router.onPointer()
                onPadAction(action)
            }
        }
        root.addView(hintBar)

        overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        player = FloatingPlayerView(
            context = this,
            colors = colors,
            onClose = { closeTrailer() },
            onOpenExternally = { url -> openExternally(url) },
            onChanged = { refreshHints() }
        ).apply { layoutParams = FrameLayout.LayoutParams(0, 0) }
        overlay.addView(player)

        return overlay
    }

    override fun openTrailer(key: String, watchUrl: String, title: String) {
        if (key.isEmpty()) {
            openExternally(watchUrl)
            return
        }
        player.open(key, watchUrl, title)
        trailerMode = true
        content.findFocus()?.clearFocus()
        refreshHints()
    }

    private fun closeTrailer() {
        player.close()
        trailerMode = false
        refreshHints()
        // Focus was cleared when the trailer took over, so put it back rather
        // than leaving the window with no owner and the next press doing nothing.
        returnFocusToScreen()
    }

    private fun returnFocusToScreen() {
        val top = sections.stack().peek() as? Screen ?: return
        if (!top.requestInitialFocus()) views[top]?.let { focusFirst(it) }
    }

    private fun openExternally(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        } catch (e: android.content.ActivityNotFoundException) {
            notify("Nothing on this device opens YouTube links")
        }
    }

    /** What the pad does while the trailer has control. */
    private fun trailerHints(): List<ButtonHint> = listOf(
        ButtonHint.activate(if (player.window.fullscreen) "Shrink" else "Fullscreen"),
        ButtonHint.back("Close"),
        ButtonHint.secondary("Size: " + player.window.sizeLabel()),
        ButtonHint.primary("Back to app")
    )

    private fun attach(screen: Screen) {
        // Views are created eagerly so a section switch is instant. The screens
        // hold no data yet, so this costs nothing; A2 moves it to on-first-show.
        val view = screen.onCreateView(this, content)
        view.visibility = View.GONE
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        views[screen] = view
    }

    private fun detach(screen: Screen) {
        views.remove(screen)?.let { content.removeView(it) }
    }

    private fun showCurrent() {
        val top = sections.stack().peek() as? Screen ?: return
        // Clear focus before hiding: a GONE view can keep window focus, and the
        // next directional press then searches outward from something invisible
        // and appears to do nothing. This was the search box on the screen we
        // just left still holding focus behind a detail page.
        content.findFocus()?.clearFocus()
        for (i in 0 until content.childCount) {
            content.getChildAt(i).visibility = View.GONE
        }
        val view = views[top]
        view?.visibility = View.VISIBLE
        tabBar.setCurrent(sections.current)
        hintBar.setHints(top.hints())
        view?.post {
            if (top.focusOnShow && view.findFocus() == null) {
                if (!top.requestInitialFocus()) focusFirst(view)
            }
            // Again, after focus has actually landed. Contextual hints are read
            // off the focused item, and the setHints above runs a frame too
            // early -- on the downloads screen X read blank even though the
            // selected transfer could plainly be stopped.
            hintBar.setHints(top.hints())
        }
    }

    /**
     * Give the screen a starting focus.
     *
     * requestFocus on a ViewGroup descends into its children, which is what is
     * wanted here: a screen whose root is a ScrollView has its focusables buried
     * several levels down, and reaching only for getChildAt(0) finds a
     * non-focusable LinearLayout and stops.
     */
    private fun focusFirst(root: View?) {
        if (root == null) return
        if (!root.requestFocus()) {
            (root as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ input

    /**
     * Note what this cannot do: an accessibility service declaring
     * `flagRequestFilterKeyEvents` is consulted before the foreground app and
     * can consume an event outright. Measured on this device it does not touch
     * the gamepad -- but the sticks and the D-pad hat arrive as *motion* events,
     * which such a service structurally cannot intercept, so navigation would
     * survive even if that changed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val screen = sections.stack().peek() as? Screen
        if (screen?.onRawKeyEvent(event) == true) return true

        if (event.action == KeyEvent.ACTION_DOWN && router.onKeyDown(event.keyCode, event.deviceId)) {
            return true
        }
        // Directional keys are ours even when the router declined this one --
        // the source latch drops duplicates on purpose, and letting the event
        // fall through means the *framework* performs its own focus search,
        // which is global and unguarded. That is how the selection kept
        // escaping a poster row and landing on a tab: the guard in moveFocus
        // was working, and some presses were never reaching it.
        //
        // A text field is the exception: left and right move the caret there,
        // and stealing them would make the search box uneditable.
        if (isDirectionalKey(event.keyCode) && currentFocus !is android.widget.EditText) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDirectionalKey(keyCode: Int): Boolean = when (keyCode) {
        PadNames.KEYCODE_DPAD_UP, PadNames.KEYCODE_DPAD_DOWN,
        PadNames.KEYCODE_DPAD_LEFT, PadNames.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val screen = sections.stack().peek() as? Screen
        if (screen?.onRawMotionEvent(event) == true) return true

        if (!PadNames.has(event.source, PadNames.SOURCE_JOYSTICK)) {
            return super.dispatchGenericMotionEvent(event)
        }
        router.onMotion(
            x = event.getAxisValue(PadNames.AXIS_X),
            y = event.getAxisValue(PadNames.AXIS_Y),
            hatX = event.getAxisValue(PadNames.AXIS_HAT_X),
            hatY = event.getAxisValue(PadNames.AXIS_HAT_Y),
            leftTriggerValue = event.getAxisValue(PadNames.AXIS_BRAKE),
            rightTriggerValue = event.getAxisValue(PadNames.AXIS_GAS),
            nowMs = event.eventTime,
            deviceId = event.deviceId
        )
        ticker.ensureRunning()
        return true
    }

    /** Any touch -- a finger, or the bottom-screen trackpad's cursor. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) router.onPointer()
        return super.dispatchTouchEvent(event)
    }

    private fun ringVisible(): Boolean = router.inputMode.showFocusRing

    private fun onPadAction(action: PadAction) {
        // The trailer window gets first refusal while it has control, so a
        // directional press moves the window rather than the selection behind it.
        if (trailerMode && player.isOpen) {
            if (action == PadAction.Primary) {
                trailerMode = false
                refreshHints()
                returnFocusToScreen()
                return
            }
            if (player.onPad(action)) {
                refreshHints()
                return
            }
        }
        // Start is how control comes back without closing the trailer.
        if (action == PadAction.Menu && player.isOpen && !trailerMode) {
            trailerMode = true
            content.findFocus()?.clearFocus()
            refreshHints()
            return
        }
        val screen = sections.stack().peek() as? Screen
        if (screen?.onPad(action) == true) return

        when (action) {
            is PadAction.Step -> moveFocus(action.direction)
            is PadAction.Page -> page(action.direction)
            is PadAction.Section -> if (sections.switch(action.delta)) showCurrent()
            PadAction.Activate -> currentFocus?.performClick()
            PadAction.Back -> if (!back()) DebugLog.log("nav", "back at section root")
            PadAction.Primary -> notify("X does nothing yet")
            PadAction.Secondary -> notify("Y does nothing yet")
            PadAction.Menu -> notify("menu")
            PadAction.Refresh -> notify("refresh")
        }
    }

    private fun moveFocus(direction: Direction) {
        // A focused view that is no longer on screen is not somewhere to search
        // from. Belt and braces alongside clearing focus on a screen switch.
        val from = (currentFocus ?: content.findFocus())?.takeIf { it.isShown }
        if (from == null) {
            // Nothing is focused yet, so there is nowhere to search *from* and a
            // directional press would silently do nothing. This is not a corner
            // case: a detail screen opens with only its cast cards focusable and
            // those start below the fold, so the pad appeared dead until a
            // trackpad tap gave focus somewhere. Take the first focusable in the
            // pressed direction instead.
            //
            // The screen gets first refusal, because it knows its own layout;
            // a ScrollView's own focus search picks by scroll proximity and
            // lands somewhere arbitrary.
            val screen = sections.stack().peek() as? Screen
            if (screen?.requestInitialFocus() != true) content.requestFocus()
            return
        }
        val mode = (sections.stack().peek() as? Screen)?.horizontalMode
            ?: com.pocketds.hub.input.HorizontalMode.CONFINED
        // Horizontal movement inside a row is done by adapter position, never
        // by focus search. Asking the framework is what breaks: its
        // onFocusSearchFailed scrolls while hunting for a candidate, the scroll
        // detaches the card holding focus, and Android hands focus to the first
        // focusable view in the window. See StripNav.
        if (mode == com.pocketds.hub.input.HorizontalMode.CONFINED &&
            StripNav.step(from, direction)
        ) {
            if (focusTick.allow(android.os.SystemClock.uptimeMillis())) {
                KeyHaptics.perform(from, HapticSettings.strength(this))
            }
            refreshHints()
            return
        }
        val candidate = from.focusSearch(direction.toFocusConstant())
        val next = candidate?.takeIf {
            FocusGuard.accepts(direction, screenRect(from), screenRect(it), mode)
        }
        if (candidate != null && next == null) {
            // Low volume and worth keeping: this is the only visible trace of a
            // press that deliberately did nothing, and "the pad feels dead" is
            // otherwise indistinguishable from "the guard refused a wrap".
            DebugLog.log(
                "nav",
                "focus $direction refused ${candidate.javaClass.simpleName} mode=$mode"
            )
        }
        if (next != null && next !== from && next.requestFocus()) {
            // Gated, or a held stick becomes a continuous buzz against the palm
            // rather than feedback. Written for held backspace in the sibling
            // project and it transfers unchanged.
            if (focusTick.allow(android.os.SystemClock.uptimeMillis())) {
                KeyHaptics.perform(next, HapticSettings.strength(this))
            }
            // The contextual buttons belong to whatever is selected, so moving
            // the selection has to redraw them. Without this, X still reads
            // "Stop" after the cursor moves onto an item that is already stopped.
            refreshHints()
        }
    }

    /**
     * Where a view actually is, in window coordinates.
     *
     * Window rather than local, because the two views being compared live in
     * different parents -- two rows of a vertical list, or a card and a tab.
     */
    private fun screenRect(view: View): com.pocketds.hub.input.FocusRect {
        val out = IntArray(2)
        view.getLocationInWindow(out)
        return com.pocketds.hub.input.FocusRect(
            left = out[0],
            top = out[1],
            right = out[0] + view.width,
            bottom = out[1] + view.height
        )
    }

    private fun page(direction: Direction) {
        val list = generateSequence(currentFocus) { it.parent as? View }
            .filterIsInstance<RecyclerView>()
            .firstOrNull() ?: return
        val by = if (direction == Direction.UP) -list.height else list.height
        list.smoothScrollBy(0, by)
    }

    private fun Direction.toFocusConstant(): Int = when (this) {
        Direction.UP -> View.FOCUS_UP
        Direction.DOWN -> View.FOCUS_DOWN
        Direction.LEFT -> View.FOCUS_LEFT
        Direction.RIGHT -> View.FOCUS_RIGHT
    }

    // ------------------------------------------------------------- ScreenHost

    override val viewContext: Context get() = this

    override fun push(screen: Screen) {
        attach(screen)
        sections.push(screen)
        showCurrent()
    }

    override fun back(): Boolean {
        val leaving = sections.stack().peek() as? Screen
        if (!sections.back()) return false
        leaving?.let { detach(it) }
        showCurrent()
        return true
    }

    override fun switchSection(delta: Int) {
        if (sections.switch(delta)) showCurrent()
    }

    override fun notify(message: String) = statusStrip.flash(message)

    override fun refreshHints() {
        if (!::player.isInitialized) return
        if (trailerMode && player.isOpen) {
            hintBar.setHints(trailerHints())
            player.setControlHint("D-pad move · A fullscreen · Y size · B close · X back to app")
            return
        }
        player.setControlHint(if (player.isOpen) "Start to control" else "")
        val hints = ((sections.stack().peek() as? Screen)?.hints() ?: emptyList()).toMutableList()
        if (player.isOpen) hints.add(ButtonHint("⏵", "Trailer", PadAction.Menu))
        hintBar.setHints(hints)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        if (::player.isInitialized) player.resumePlayback()
        (sections.stack().peek() as? Screen)?.onShow()
    }

    override fun onPause() {
        // A backgrounded screen must not keep a frame callback alive, and a
        // trailer must not keep playing audio over whatever is now in front.
        ticker.stop()
        if (::player.isInitialized) player.pausePlayback()
        (sections.stack().peek() as? Screen)?.onHide()
        super.onPause()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
