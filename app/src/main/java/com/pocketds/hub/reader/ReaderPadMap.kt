package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import com.pocketds.hub.nav.ButtonHint

/** Which reader the keys are for (#16). Read-along is a book with narration. */
enum class ReaderKind { COMIC, BOOK, AUDIOBOOK }

/** What a reader is showing, as far as its keys are concerned. */
data class ReaderPadState(
    val kind: ReaderKind,
    /** Comics: the controls over the page. Books: the menu, the page shrunk inside it. */
    val controlsVisible: Boolean = false,
    /** Books: continuous scrolling rather than pages. */
    val scrolling: Boolean = false,
    /** Books: narration plays alongside the text (read along). */
    val narration: Boolean = false,
    /** Still opening, or it failed: Select tries again. */
    val loading: Boolean = false,
    /** Audiobooks: how far L2 and R2 jump. */
    val seekSeconds: Int = 10
)

/** What a key does in a reader, whichever reader it is. Each reader carries these out its own way. */
sealed interface ReaderCommand {
    /** On through the reading: a comic's next third or page, a book's next page. */
    data object Forward : ReaderCommand
    /** Back through the reading. */
    data object Backward : ReaderCommand
    /** A whole page on or back, past any thirds. */
    data class Page(val delta: Int) : ReaderCommand
    /** A book's chapter, an audiobook's part. */
    data class Chapter(val delta: Int) : ReaderCommand
    data class Zoom(val factor: Float) : ReaderCommand
    /** Comics, the D-pad: across the page, turning at its edge going sideways. */
    data class Move(val direction: Direction) : ReaderCommand
    /** The right stick: a comic pans, a scrolling book scrolls; amounts in stick-seconds. */
    data class Glide(val dx: Float, val dy: Float) : ReaderCommand
    /** A scrolling book, the D-pad: a part of a screen up or down. */
    data class Scroll(val direction: Direction) : ReaderCommand
    /** Comics, L3 held: a closer look until it is let go. */
    data class Magnifier(val on: Boolean) : ReaderCommand
    /** Read along: back to the sentence being read aloud. */
    data object FollowNarration : ReaderCommand
    /** Read along, L1 and R1: the sentence before or after (#16, A5). */
    data class Sentence(val delta: Int) : ReaderCommand
    /** Comics: show or hide the controls. Books: open the menu, or go back to the page. */
    data class Controls(val visible: Boolean) : ReaderCommand
    data object Leave : ReaderCommand
    /** Press the control in focus. */
    data object Choose : ReaderCommand
    /** Move between the controls. */
    data class Focus(val direction: Direction) : ReaderCommand
    data object Bookmark : ReaderCommand
    data object Contents : ReaderCommand
    /** A comic's Display sheet, a book's Appearance. */
    data object Display : ReaderCommand
    /** The Controls sheet: every key and what it does here. */
    data object Keys : ReaderCommand
    data object PlayPause : ReaderCommand
    data class Seek(val seconds: Int) : ReaderCommand
    /** Audiobooks: the other ways to read this book. */
    data object Formats : ReaderCommand
    data object Retry : ReaderCommand
    /** Taken by the reader and left alone: never handed on to the app (a tab switch would close it). */
    data object Ignore : ReaderCommand
}

/** One line of the Controls sheet: the keys, drawn as caps, and what they do. */
data class ReaderKeyLine(val keys: List<String>, val does: String)

/**
 * One controller language for every reader (#16, X1), as the owner decided
 * on 2026-10-04: comics read on with Ⓐ and back with Ⓑ and leave with Select;
 * a book's Ⓑ opens the menu (the page shrinks inside it) and Ⓑ again leaves;
 * an audiobook's Ⓑ leaves. Every key is the reader's: none is handed to the
 * app, whose shoulder buttons would switch tabs and close the reader.
 *
 * The same table names the keys, so the hint row inside the controls and the
 * Controls sheet can never say something the keys do not do. Pure, so tested.
 */
object ReaderPadMap {
    /** L1 and R1 zoom a comic by this much, L2 and R2 by [BIG_ZOOM]. */
    const val SMALL_ZOOM = 1.2f
    const val BIG_ZOOM = 1.35f

    fun command(state: ReaderPadState, action: PadAction): ReaderCommand = when (state.kind) {
        ReaderKind.COMIC -> comic(state, action)
        ReaderKind.BOOK -> book(state, action)
        ReaderKind.AUDIOBOOK -> audiobook(state, action)
    }

    private fun comic(state: ReaderPadState, action: PadAction): ReaderCommand = when (action) {
        PadAction.Activate -> if (state.controlsVisible) ReaderCommand.Choose else ReaderCommand.Forward
        PadAction.Back -> if (state.controlsVisible) ReaderCommand.Controls(false) else ReaderCommand.Backward
        PadAction.Primary -> ReaderCommand.Page(1)
        PadAction.Secondary -> ReaderCommand.Page(-1)
        is PadAction.Section -> ReaderCommand.Zoom(if (action.delta > 0) SMALL_ZOOM else 1f / SMALL_ZOOM)
        is PadAction.Page -> ReaderCommand.Zoom(if (action.direction == Direction.DOWN) BIG_ZOOM else 1f / BIG_ZOOM)
        is PadAction.Step -> if (state.controlsVisible) ReaderCommand.Focus(action.direction) else ReaderCommand.Move(action.direction)
        PadAction.Menu -> ReaderCommand.Controls(!state.controlsVisible)
        PadAction.Refresh -> if (state.loading) ReaderCommand.Retry else ReaderCommand.Leave
        is PadAction.Pan -> ReaderCommand.Glide(action.dx, action.dy)
        is PadAction.Click -> when {
            action.stick == Stick.LEFT -> ReaderCommand.Magnifier(action.down)
            action.down -> ReaderCommand.Keys
            else -> ReaderCommand.Ignore
        }
    }

    private fun book(state: ReaderPadState, action: PadAction): ReaderCommand = when (action) {
        PadAction.Activate -> if (state.controlsVisible) ReaderCommand.Choose else ReaderCommand.Forward
        PadAction.Back -> if (state.controlsVisible) ReaderCommand.Leave else ReaderCommand.Controls(true)
        PadAction.Primary -> ReaderCommand.Bookmark
        PadAction.Secondary -> ReaderCommand.Contents
        // Read along, the shoulders step through the narration a sentence at a time (A5).
        is PadAction.Section -> if (state.narration && !state.controlsVisible) ReaderCommand.Sentence(action.delta) else ReaderCommand.Page(action.delta)
        // Held: the reader asks for a deliberate hold before a chapter jumps.
        is PadAction.Page -> ReaderCommand.Chapter(if (action.direction == Direction.DOWN) 1 else -1)
        is PadAction.Step -> when {
            state.controlsVisible -> ReaderCommand.Focus(action.direction)
            action.direction == Direction.LEFT -> ReaderCommand.Page(-1)
            action.direction == Direction.RIGHT -> ReaderCommand.Page(1)
            state.scrolling -> ReaderCommand.Scroll(action.direction)
            else -> ReaderCommand.Controls(true)
        }
        PadAction.Menu -> ReaderCommand.Controls(!state.controlsVisible)
        PadAction.Refresh -> if (state.loading) ReaderCommand.Retry else ReaderCommand.Display
        is PadAction.Pan -> if (state.scrolling && !state.controlsVisible) ReaderCommand.Glide(action.dx, action.dy) else ReaderCommand.Ignore
        is PadAction.Click -> when {
            !action.down -> ReaderCommand.Ignore
            action.stick == Stick.RIGHT -> ReaderCommand.Keys
            state.narration -> ReaderCommand.FollowNarration
            else -> ReaderCommand.Ignore
        }
    }

    private fun audiobook(state: ReaderPadState, action: PadAction): ReaderCommand = when (action) {
        PadAction.Activate -> ReaderCommand.Choose
        PadAction.Back -> ReaderCommand.Leave
        PadAction.Primary -> ReaderCommand.PlayPause
        PadAction.Secondary -> ReaderCommand.Formats
        is PadAction.Section -> ReaderCommand.Chapter(action.delta)
        is PadAction.Page -> ReaderCommand.Seek(if (action.direction == Direction.DOWN) state.seekSeconds else -state.seekSeconds)
        is PadAction.Step -> ReaderCommand.Focus(action.direction)
        PadAction.Menu -> ReaderCommand.Keys
        PadAction.Refresh -> if (state.loading) ReaderCommand.Retry else ReaderCommand.Ignore
        is PadAction.Pan -> ReaderCommand.Ignore
        is PadAction.Click -> if (action.stick == Stick.RIGHT && action.down) ReaderCommand.Keys else ReaderCommand.Ignore
    }

    /** What [command] is called on a key cap's line, in this reader; blank for [ReaderCommand.Ignore]. */
    fun describe(kind: ReaderKind, command: ReaderCommand): String = when (command) {
        ReaderCommand.Forward -> if (kind == ReaderKind.BOOK) "Next page" else "Forward"
        ReaderCommand.Backward -> "Back"
        is ReaderCommand.Page -> if (command.delta > 0) "Next page" else "Previous page"
        is ReaderCommand.Chapter -> when {
            kind == ReaderKind.AUDIOBOOK -> if (command.delta > 0) "Next part" else "Previous part"
            command.delta > 0 -> "Next chapter"
            else -> "Previous chapter"
        }
        is ReaderCommand.Zoom -> if (command.factor > 1f) "Zoom in" else "Zoom out"
        is ReaderCommand.Move -> "Move around the page"
        is ReaderCommand.Glide -> if (kind == ReaderKind.COMIC) "Pan" else "Scroll"
        is ReaderCommand.Scroll -> "Scroll"
        is ReaderCommand.Magnifier -> "Magnifier, while held"
        ReaderCommand.FollowNarration -> "Back to the narration"
        is ReaderCommand.Sentence -> if (command.delta > 0) "Next sentence" else "Previous sentence"
        is ReaderCommand.Controls -> when {
            kind == ReaderKind.BOOK -> if (command.visible) "Menu" else "Back to the page"
            command.visible -> "Controls"
            else -> "Hide controls"
        }
        ReaderCommand.Leave -> if (kind == ReaderKind.BOOK) "Leave the book" else "Leave"
        ReaderCommand.Choose -> "Choose"
        is ReaderCommand.Focus -> "Move between controls"
        ReaderCommand.Bookmark -> "Bookmark"
        ReaderCommand.Contents -> "Contents"
        ReaderCommand.Display -> if (kind == ReaderKind.BOOK) "Appearance" else "Display"
        ReaderCommand.Keys -> "Keys"
        ReaderCommand.PlayPause -> "Play or pause"
        is ReaderCommand.Seek -> if (command.seconds < 0) "Back ${-command.seconds} s" else "Forward ${command.seconds} s"
        ReaderCommand.Formats -> "Reading and listening"
        ReaderCommand.Retry -> "Retry"
        ReaderCommand.Ignore -> ""
    }

    /**
     * The hint row inside the controls (the app's own hint bar is hidden in
     * a reader): what the main keys do now. Each chip is also a button.
     */
    fun hints(state: ReaderPadState): List<ButtonHint> = HINT_KEYS.getValue(state.kind).mapNotNull { (glyph, action) ->
        describe(state.kind, command(state, action)).takeIf(String::isNotBlank)?.let { ButtonHint(glyph, it, action) }
    }

    /**
     * The Controls sheet: every key and what it does while reading, the
     * controls closed. A key that does nothing here is left out; the D-pad
     * takes two lines when its sides and its ends differ (a book).
     */
    fun sheet(kind: ReaderKind, state: ReaderPadState = ReaderPadState(kind)): List<ReaderKeyLine> = buildList {
        val reading = state.copy(controlsVisible = kind == ReaderKind.AUDIOBOOK, loading = false)
        fun line(keys: List<String>, action: PadAction) {
            describe(kind, command(reading, action)).takeIf(String::isNotBlank)?.let { add(ReaderKeyLine(keys, it)) }
        }
        fun pair(first: Pair<String, PadAction>, second: Pair<String, PadAction>) {
            val a = describe(kind, command(reading, first.second))
            val b = describe(kind, command(reading, second.second))
            when {
                a.isBlank() && b.isBlank() -> Unit
                a == b -> add(ReaderKeyLine(listOf(first.first, second.first), a))
                else -> { line(listOf(first.first), first.second); line(listOf(second.first), second.second) }
            }
        }
        line(listOf(A), PadAction.Activate)
        line(listOf(B), PadAction.Back)
        line(listOf(X), PadAction.Primary)
        line(listOf(Y), PadAction.Secondary)
        val sides = describe(kind, command(reading, PadAction.Step(Direction.RIGHT)))
        val ends = describe(kind, command(reading, PadAction.Step(Direction.DOWN)))
        if (sides == ends) add(ReaderKeyLine(listOf(DPAD), sides))
        else {
            val left = describe(kind, command(reading, PadAction.Step(Direction.LEFT)))
            add(ReaderKeyLine(listOf(DPAD_SIDES), if (left == sides) sides else "$left, $sides".lowercaseAfterFirst()))
            add(ReaderKeyLine(listOf(DPAD_ENDS), ends))
        }
        pair(L1 to PadAction.Section(-1), R1 to PadAction.Section(1))
        pair(L2 to PadAction.Page(Direction.UP), R2 to PadAction.Page(Direction.DOWN))
        line(listOf(RIGHT_STICK), PadAction.Pan(0f, 1f))
        line(listOf(L3), PadAction.Click(Stick.LEFT))
        line(listOf(R3), PadAction.Click(Stick.RIGHT))
        line(listOf(START), PadAction.Menu)
        line(listOf(SELECT), PadAction.Refresh)
    }

    /** "Previous page, Next page" reads "Previous page, next page". */
    private fun String.lowercaseAfterFirst(): String {
        val comma = indexOf(", ")
        return if (comma < 0) this else substring(0, comma + 2) + substring(comma + 2).replaceFirstChar { it.lowercase() }
    }

    // The caps as the hint bar draws them (KeyGlyphDrawable): letters in circles, names in pills.
    const val A = "Ⓐ"
    const val B = "Ⓑ"
    const val X = "Ⓧ"
    const val Y = "Ⓨ"
    const val START = "⏵"
    const val SELECT = "⟳"
    const val L1 = "L1"
    const val R1 = "R1"
    const val L2 = "L2"
    const val R2 = "R2"
    const val L3 = "L3"
    const val R3 = "R3"
    const val DPAD = "D-pad"
    const val DPAD_SIDES = "D-pad ← →"
    const val DPAD_ENDS = "D-pad ↑ ↓"
    const val RIGHT_STICK = "Right stick"

    /** The keys the hint row names, per reader, in the order the row shows them. */
    private val HINT_KEYS: Map<ReaderKind, List<Pair<String, PadAction>>> = mapOf(
        ReaderKind.COMIC to listOf(A to PadAction.Activate, B to PadAction.Back, X to PadAction.Primary,
            Y to PadAction.Secondary, SELECT to PadAction.Refresh, R3 to PadAction.Click(Stick.RIGHT)),
        ReaderKind.BOOK to listOf(A to PadAction.Activate, B to PadAction.Back, X to PadAction.Primary,
            Y to PadAction.Secondary, START to PadAction.Menu, SELECT to PadAction.Refresh, R3 to PadAction.Click(Stick.RIGHT)),
        ReaderKind.AUDIOBOOK to listOf(A to PadAction.Activate, B to PadAction.Back, X to PadAction.Primary,
            L1 to PadAction.Section(-1), R1 to PadAction.Section(1), L2 to PadAction.Page(Direction.UP),
            R2 to PadAction.Page(Direction.DOWN), R3 to PadAction.Click(Stick.RIGHT))
    )
}
