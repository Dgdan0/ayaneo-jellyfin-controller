package com.pocketds.hub.nav

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction

/**
 * One screen in the app.
 *
 * Deliberately not a Fragment. What is actually needed here is four callbacks
 * and a back stack -- about forty lines we own -- rather than a transaction,
 * lifecycle and saved-state machine we would then have to work around to keep a
 * single focus owner and a single input pipeline.
 *
 * The lifecycle half lives in [StackScreen] with no Android types, so the stacks
 * that drive it are unit-tested against fakes.
 */
interface Screen : StackScreen {

    /** Shown in the tab bar and used as the [FocusMemory] key. */
    val title: String

    fun onCreateView(host: ScreenHost, container: ViewGroup): View

    /** What A/B/X/Y do right now. Rendered as labels *and* as tappable buttons. */
    fun hints(): List<ButtonHint> = emptyList()

    /**
     * @return false to let the generic handler have it -- move focus, activate
     *   the focused view, pop the stack. Screens only intercept what they
     *   genuinely handle differently.
     */
    fun onPad(action: PadAction): Boolean = false

    /**
     * Put focus where this screen wants it to start.
     *
     * Needed because neither form of ViewGroup.requestFocus gives traversal
     * order inside a ScrollView: it overrides onRequestFocusInDescendants to
     * prefer whatever is nearest the current scroll position, which on a detail
     * screen landed on the fifth cast card rather than the first. A screen knows
     * its own layout; the host does not.
     *
     * @return true if focus was taken.
     */
    fun requestInitialFocus(): Boolean = false

    /**
     * Whether this screen should take focus the moment it appears.
     *
     * True for a grid, where the first cell is obviously where you are. False
     * for a reading screen: a detail page opens at its title and overview, and
     * grabbing focus for something further down scrolls the header out of view
     * before the user has read a word of it. Those screens take focus on the
     * first directional press instead.
     */
    val focusOnShow: Boolean get() = true

    /**
     * How left and right behave here.
     *
     * Confined by default, which is right for rows of posters, a cast strip and
     * the tab bar: rows are navigated with up and down, so a sideways press
     * that runs out of items should do nothing. A grid says otherwise, because
     * reading order continues on the line below.
     */
    val horizontalMode: HorizontalMode get() = HorizontalMode.CONFINED

    /**
     * Raw events, before the router turns them into intents.
     *
     * An escape hatch for exactly one screen: the input probe, whose entire job
     * is to report key codes, scan codes and axis values as they actually
     * arrive. Every other screen must work in [PadAction]s -- that is what makes
     * "reachable by gamepad and by trackpad" a property of the router rather
     * than of each screen.
     *
     * @return true to consume, keeping the event away from the router.
     */
    fun onRawKeyEvent(event: android.view.KeyEvent): Boolean = false
    fun onRawMotionEvent(event: android.view.MotionEvent): Boolean = false
}

/**
 * What a screen is allowed to ask of the app around it.
 *
 * [viewContext] rather than reaching for the Activity is the rule that keeps the
 * secondary display possible later: a `Presentation` on the bottom screen
 * carries a different `Context` with different metrics, and code that reaches
 * for the Activity's silently sizes itself for the wrong screen.
 */
interface ScreenHost {
    val viewContext: Context
    fun push(screen: Screen)
    fun back(): Boolean
    fun switchSection(delta: Int)
    /** A transient message in the status strip, not a system Toast. */
    fun notify(message: String)
    /** Redraw the hint bar, after the contextual actions change. */
    fun refreshHints()

    /**
     * Play a trailer in a floating window that survives navigation.
     *
     * Hosted by the Activity rather than by a screen for exactly that reason:
     * backing out of a detail page should not stop the trailer you just started.
     *
     * @param key the bare YouTube video id.
     * @param watchUrl the full URL, for handing off to the YouTube app.
     */
    fun openTrailer(key: String, watchUrl: String, title: String)
}

/**
 * One button's meaning right now.
 *
 * The same object labels the button for gamepad users and *is* the tappable
 * control for trackpad users, so there is no second touch-only UI to keep in
 * sync with this one.
 */
data class ButtonHint(
    val glyph: String,
    val label: String,
    val action: PadAction,
    val enabled: Boolean = true
) {
    companion object {
        fun activate(label: String) = ButtonHint("Ⓐ", label, PadAction.Activate)   // Ⓐ
        fun back(label: String = "Back") = ButtonHint("Ⓑ", label, PadAction.Back)   // Ⓑ
        fun primary(label: String) = ButtonHint("Ⓧ", label, PadAction.Primary)      // Ⓧ
        fun secondary(label: String) = ButtonHint("Ⓨ", label, PadAction.Secondary)  // Ⓨ
    }
}
