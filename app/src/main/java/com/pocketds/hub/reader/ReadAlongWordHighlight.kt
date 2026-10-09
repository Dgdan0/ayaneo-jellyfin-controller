package com.pocketds.hub.reader

/**
 * The read-along highlight's colours (#66), fixed and the same on the Pocket and Apple: the owner's approved demo
 * (`word-trail.html`) gives these numbers, and both apps copy them exactly.
 */
enum class ReadAlongColor(val id: String, val label: String, val rgb: Int) {
    GOLD("gold", "Gold", 0xF0C96A),
    EMBER("ember", "Ember", 0xDE8C4C),
    ROSE("rose", "Rose", 0xE98FA8),
    LAVENDER("lavender", "Lavender", 0xB39DEB),
    SKY("sky", "Sky", 0x7DB7E8),
    TEAL("teal", "Teal", 0x5CC2B5),
    MINT("mint", "Mint", 0x9AD47E),
    MOON("moon", "Moon", 0xBEC4D6);

    /** As an opaque ARGB colour. */
    val argb: Int get() = (0xFF shl 24) or rgb

    companion object {
        fun of(id: String?): ReadAlongColor? = entries.firstOrNull { it.id == id }
    }
}

/** One page theme's highlight: its colour, and how strong the trail is as a share of the word's strength (0 to 100). */
data class HighlightLook(val color: ReadAlongColor, val trailPercent: Int)

/** The highlight of each page theme, as this device keeps it; a theme with none kept has its default. */
data class ReadAlongHighlights(val looks: Map<EpubTheme, HighlightLook> = emptyMap()) {
    fun of(theme: EpubTheme): HighlightLook = looks[theme] ?: ReadAlongWordHighlight.defaultLook(theme)
    fun with(theme: EpubTheme, look: HighlightLook): ReadAlongHighlights = copy(looks = looks + (theme to look))
    /** "Use the default": the theme's colour and trail both. */
    fun reset(theme: EpubTheme): ReadAlongHighlights = copy(looks = looks - theme)
}

/** The colours a page's highlight is drawn in: the word, the trail (null at 0%: no trail at all) and a sentence edition's wash. */
data class HighlightTints(val word: Int, val trail: Int?, val sentence: Int)

/**
 * Read along by the word, style A (#66): the sentence's words already spoken in a light wash, the word being said in a
 * strong one, the trail cleared at each new sentence and never over the space between two sentences. This is the one
 * owner of the look: the colours and strengths here, the bookkeeping of what is lit, and the script that draws it
 * ([ReadAlongGlow.fitScript]); the screen only hands it the segment being read.
 *
 * Strengths, from the demo: on a light page the word is 0.62 of the way from the page to the colour, on a dark page
 * 0.42; the trail is [DEFAULT_TRAIL]% of that unless the reader sets it otherwise. Every mix is held so the page's ink
 * keeps 4.5:1 on it ([ReadAlongGlow.wash]). A book with no word pack reads along by the sentence, in the same colour, at
 * [SENTENCE] (the strength the sentence wash always had).
 */
object ReadAlongWordHighlight {
    const val WORD_LIGHT = 0.62
    const val WORD_DARK = 0.42
    const val SENTENCE = 0.45
    const val DEFAULT_TRAIL = 40
    const val TRAIL_STEP = 5

    fun defaultColor(theme: EpubTheme): ReadAlongColor = if (isDark(theme)) ReadAlongColor.EMBER else ReadAlongColor.GOLD
    fun defaultLook(theme: EpubTheme): HighlightLook = HighlightLook(defaultColor(theme), DEFAULT_TRAIL)
    fun isDefault(theme: EpubTheme, look: HighlightLook): Boolean = look == defaultLook(theme)

    /** A dark page: Dim, Dark and Blue (Paper by day and Dark at night is resolved before this is asked). */
    fun isDark(theme: EpubTheme): Boolean = EpubPagePalette.isDark(theme)

    fun wordStrength(dark: Boolean): Double = if (dark) WORD_DARK else WORD_LIGHT

    /** The trail's strength before it is held for the ink: the word's times the percent, as the demo works it out. */
    fun trailStrength(dark: Boolean, percent: Int): Double = wordStrength(dark) * percent.coerceIn(0, 100) / 100.0

    /** A trail percent as the slider steps it: 0 to 100, by [TRAIL_STEP]. */
    fun stepped(percent: Int): Int = (Math.round(percent.coerceIn(0, 100) / TRAIL_STEP.toDouble()) * TRAIL_STEP).toInt()

    /** The colours [look] draws on a page of [page] and [ink], [dark] or light. */
    fun tints(look: HighlightLook, page: Int, ink: Int, dark: Boolean): HighlightTints {
        val color = look.color.argb
        val trail = look.trailPercent.coerceIn(0, 100)
        return HighlightTints(
            word = ReadAlongGlow.wash(color, page, ink, wordStrength(dark)),
            trail = if (trail == 0) null else ReadAlongGlow.wash(color, page, ink, trailStrength(dark, trail)),
            sentence = ReadAlongGlow.wash(color, page, ink, SENTENCE)
        )
    }

    /** The colours of [theme] (as drawn: [EpubPagePalette.resolve] first) for [highlights]. */
    fun tints(highlights: ReadAlongHighlights, theme: EpubTheme): HighlightTints {
        val (page, ink) = EpubPagePalette.of(theme)
        return tints(highlights.of(theme), page, ink, isDark(theme))
    }

    /** "Gold · trail 40%", "Ember · no trail": what the setting's row says. */
    fun summary(look: HighlightLook): String =
        look.color.label + " · " + if (look.trailPercent <= 0) "no trail" else "trail ${look.trailPercent}%"

    /** The slider's value as words: "None" at 0, else the percent. */
    fun trailLabel(percent: Int): String = if (percent <= 0) "None" else "$percent%"

    // ---------------------------------------------------------------- what is lit

    /**
     * What the page shows of the narration: the sentence ([sentence], the element the wash is drawn in) and, in a word
     * edition, the word being said in it. Null [word] is a sentence edition's sentence, washed whole.
     */
    data class Mark(val textHref: String, val sentence: String, val word: String?)

    fun mark(segment: ReadAlongSegment?): Mark? =
        segment?.let { Mark(it.textHref, it.sentenceFragment, it.fragment.takeIf { _ -> it.isWord }) }

    /** How the page moves from one mark to the next. */
    sealed interface Change {
        /** Nothing is read: the highlight goes. */
        data object Clear : Change
        /** Another sentence: its wash is laid down anew, and the trail starts again from its first word. */
        data class Sentence(val mark: Mark) : Change
        /** The next word of the same sentence: only the word and the trail's end move. */
        data class Word(val mark: Mark) : Change
        data object Same : Change
    }

    fun change(from: Mark?, to: Mark?): Change = when {
        to == null -> if (from == null) Change.Same else Change.Clear
        from == to -> Change.Same
        from == null || from.textHref != to.textHref || from.sentence != to.sentence -> Change.Sentence(to)
        else -> Change.Word(to)
    }

    /**
     * The words the trail covers while [segment] is being said: the sentence's words before it, in the order they are
     * read. The page draws it as one stretch from the sentence's first letter to the word's last, so a word the
     * dramatization cut (it has no clip, and so no segment) is inside it too; this is the timed ones, for a test.
     */
    fun trail(timeline: ReadAlongTimeline, segment: ReadAlongSegment): List<String> {
        if (!segment.isWord) return emptyList()
        val sentence = timeline.sentence(segment.textHref, segment.sentenceFragment) ?: return emptyList()
        val words = mutableListOf<String>()
        var at: ReadAlongTimeline.Located? = sentence.first
        while (at != null && at.segment.textHref == segment.textHref && at.segment.sentenceFragment == segment.sentenceFragment) {
            if (at.segment.fragment == segment.fragment) return words
            words += at.segment.fragment
            at = timeline.after(at)
        }
        return words
    }

    /** What the script is told to draw: the sentence, the word in it, its colour, and whether a trail is drawn at all. */
    data class Paint(val sentence: String, val word: String?, val decorationTint: Int, val wordTint: Int?, val trail: Boolean)

    /**
     * [mark] drawn in [tints]: a sentence edition's sentence washed whole in the sentence wash; a word with the trail's
     * wash on the sentence (cut back by the script to the words already said) and the word's own over it. At a trail of
     * 0% the sentence's boxes are all hidden and only the word is drawn.
     */
    fun paint(mark: Mark, tints: HighlightTints): Paint =
        if (mark.word == null) Paint(mark.sentence, null, tints.sentence, null, trail = true)
        else Paint(mark.sentence, mark.word, tints.trail ?: tints.word, tints.word, trail = tints.trail != null)
}
