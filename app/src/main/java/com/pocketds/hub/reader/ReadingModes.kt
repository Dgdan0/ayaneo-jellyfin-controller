package com.pocketds.hub.reader

/**
 * The three ways to take a book in (#62): read it, hear it, or both at once. One button in the reader's top bar and on the audiobook
 * screen switches between whichever of them the book has. Plain Kotlin, so a JVM test pins every rule.
 */
enum class ReadingMode(val label: String) {
    EBOOK("Ebook"), AUDIO("Audio"), ALONG("Read along");

    /** "Listening" for the voice alone, "Reading along" with the text. */
    val doing: String get() = when (this) { EBOOK -> "Reading"; AUDIO -> "Listening"; ALONG -> "Reading along" }

    companion object {
        /** The modes a book has, in the order the button shows them; none repeats. */
        fun available(ebook: Boolean, audio: Boolean, aligned: Boolean): List<ReadingMode> =
            listOfNotNull(EBOOK.takeIf { ebook }, AUDIO.takeIf { audio }, ALONG.takeIf { aligned })
    }
}

/**
 * The mode button's state (#62): it shows only the mode you are in; opened, it shows them all with the one in focus named, and
 * it closes by itself after [OPEN_MS] or when something else is touched. Ⓨ opens it, ◀▶ choose, Ⓐ switches, Ⓑ closes.
 */
class ModePicker(val modes: List<ReadingMode>, val current: ReadingMode) {
    var isOpen: Boolean = false
        private set
    /** The mode in focus while it is open: the name written under the button. */
    var focused: ReadingMode = current
        private set
    private var openedAt = 0L

    /** A button that has nothing to choose between is not shown. */
    val worthShowing: Boolean get() = modes.size > 1 && current in modes

    fun open(now: Long) {
        if (!worthShowing) return
        isOpen = true
        focused = current
        openedAt = now
    }

    fun close() { isOpen = false; focused = current }

    /** ◀ or ▶: the next mode over, and the five seconds start again. Stops at either end. */
    fun move(delta: Int, now: Long) {
        if (!isOpen) return
        val at = modes.indexOf(focused).coerceAtLeast(0)
        focused = modes[(at + delta).coerceIn(0, modes.lastIndex)]
        openedAt = now
    }

    /** Hovering or pointing at a mode names it without choosing it. */
    fun point(mode: ReadingMode, now: Long) {
        if (isOpen && mode in modes) { focused = mode; openedAt = now }
    }

    /** Ⓐ: the mode to switch to, closing the button; null when it is closed or the focused one is the one you are in. */
    fun pick(): ReadingMode? {
        if (!isOpen) return null
        val chosen = focused
        close()
        return chosen.takeIf { it != current }
    }

    /** Whether the five seconds are over. */
    fun expired(now: Long): Boolean = isOpen && now - openedAt >= OPEN_MS

    companion object { const val OPEN_MS = 5_000L }
}

/**
 * Where a switch of mode starts (#62), one rule for the reader and the audiobook screen, so the three modes agree about a place.
 * From the page: the sentence of a word just selected; else where the voice stopped, if it was listened to on this page (the
 * ebook shows "Heard to here" there); else the top of the page. To the page: it opens at the voice, with that mark.
 */
object ModePlace {
    enum class Start { SELECTED_SENTENCE, WHERE_VOICE_STOPPED, TOP_OF_PAGE }

    /** [selected]: a word is selected on the page. [heardHere]: the page shows the "Heard to here" mark, so the voice stopped on it. */
    fun startingFromPage(selected: Boolean, heardHere: Boolean): Start = when {
        selected -> Start.SELECTED_SENTENCE
        heardHere -> Start.WHERE_VOICE_STOPPED
        else -> Start.TOP_OF_PAGE
    }

    /** What the screen says as the new mode begins. */
    fun note(start: Start, to: ReadingMode): String = when (start) {
        Start.SELECTED_SENTENCE -> "${to.doing} from the start of the sentence you selected"
        Start.WHERE_VOICE_STOPPED -> "Going on from where the voice stopped"
        Start.TOP_OF_PAGE -> "${to.doing} from the top of your page"
    }

    /**
     * Leaving the voice for the page: what the screen says, once, as the page comes up. With the mark drawn it names it ("Heard to here",
     * which is also the mark's accessibility text), so a person who has not met the tab in the margin learns what it is; without
     * one (the sentence is not in this edition) it says only what happened to the voice.
     */
    fun noteToEbook(from: ReadingMode, marked: Boolean): String = when {
        marked && from == ReadingMode.AUDIO -> "Heard to here: the page opens where the voice was"
        marked -> "Heard to here: paused, your place is kept"
        from == ReadingMode.AUDIO -> "The voice's place is not in this edition: the page stays where you were"
        else -> "Paused. Your place is kept"
    }

    /** Between Audio and Read along the voice keeps going where it is; to or from the page it does not. */
    fun voiceKeepsGoing(from: ReadingMode, to: ReadingMode): Boolean =
        from != ReadingMode.EBOOK && to != ReadingMode.EBOOK && from != to
}

/**
 * "You listened further on <device>" (#62), asked when a listening session begins and the hub holds a place written by another
 * device, further on than this device's own. Go there takes it, Stay here keeps ours (and the hub's place becomes ours).
 */
object ListenedFurther {
    /** The hub's place has to be more than this far ahead, in the book's own time, to be worth asking about. */
    const val MIN_AHEAD_MS = 60_000L
    /** The same in a book's share, where only that is known: a hundredth of the book. */
    const val MIN_AHEAD_SHARE = 0.01

    data class Away(val device: String, val where: String, val agoMs: Long)

    /**
     * [ours] and [theirs] are how far through the book each place is, 0 to 1 (or in milliseconds when [inMs]); [byThisDevice]
     * says the hub's place was written by the asking device itself, which is not news. Null when there is nothing to ask.
     */
    fun decide(ours: Double?, theirs: Double?, inMs: Boolean, byThisDevice: Boolean, device: String, where: String, ageMs: Long): Away? {
        if (ours == null || theirs == null || byThisDevice) return null
        val ahead = theirs - ours
        if (ahead < (if (inMs) MIN_AHEAD_MS.toDouble() else MIN_AHEAD_SHARE)) return null
        return Away(device.ifBlank { "another device" }, where, ageMs.coerceAtLeast(0))
    }

    /** "You listened further on iPad". */
    fun title(away: Away): String = "You listened further on ${away.device}"

    /** "Chapter 7 · 12 minutes ago". */
    fun detail(away: Away): String = listOf(away.where.takeIf(String::isNotBlank), ago(away.agoMs)).filterNotNull().joinToString(" · ")

    fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes == 1L -> "a minute ago"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 120 -> "an hour ago"
            minutes < 24 * 60 -> "${minutes / 60} hours ago"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }
}

/**
 * Where a screen opens when it is the result of a switch of mode (#62): where the switch came from, which rule chose the place
 * ([ModePlace.Start]), the sentence to begin at or to mark ([anchor], found by its words in whatever edition the screen shows), the
 * place in the audiobook's tracks when the voice moves between Audio and Read along ([audioPlace]), and whether the voice goes on
 * ([playing]). An ordinary opening has none of these.
 */
data class ModeEntry(
    val from: ReadingMode? = null,
    val start: ModePlace.Start = ModePlace.Start.TOP_OF_PAGE,
    val anchor: SentenceAnchor? = null,
    val audioPlace: AudioPlace? = null,
    val playing: Boolean = false
)
