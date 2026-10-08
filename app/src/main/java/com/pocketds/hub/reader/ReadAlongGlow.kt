package com.pocketds.hub.reader

/**
 * The sentence being read, as Readium draws it (#16, X7, #52): a soft wash of the
 * accent with a glow round it, as the prototype's read-along has, instead of
 * Readium's flat box in a fixed gold. The tint is the Books accent, handed in
 * with each highlight. Pure strings, so a JVM test pins them; EpubReaderScreen
 * gives them to Readium as its highlight template.
 *
 * Readium lays one box over each line of a sentence, and the boxes of two lines
 * overlap by a few pixels (or sit a few apart, with the lines further apart); each
 * box also has a ring and a glow, which spread over its neighbours. Boxes that were
 * themselves translucent added up where they met, and a darker band showed between
 * every two lines. So the boxes, ring and glow are solid, and the transparency is
 * applied once, to the whole group of them ([GROUP], which Readium gives its own
 * container): overlaps of one solid colour are the same colour, and what the eye sees
 * is a single even tint with one soft edge.
 *
 * With the lines further apart than Readium's boxes are tall (line spacing 1.8), a few pixels between two lines would
 * stay bare: so each box reaches half a line's spacing up and down, [REACH_EM], into its neighbours, which are solid
 * and the same colour; only the first line's top and the last line's foot keep Readium's own edge, so the tint
 * never spills over the sentence before or after.
 */
object ReadAlongGlow {
    const val CLASS = "pocket-narration"
    /** The decoration group Readium is given the sentence in; its container carries this as `data-group`. */
    const val GROUP = "readalong"
    /** How strong the tint is, wash, ring and glow together, once, over the whole sentence. */
    const val OPACITY = 0.30
    /** The ring round the words and how far the glow reaches beyond it, in CSS pixels. */
    private const val RING_PX = 3
    private const val GLOW_BLUR_PX = 14
    private const val GLOW_SPREAD_PX = 4
    /** How far a box reaches towards the line above and the line below, in the text's own size: more than half the biggest gap. */
    private const val REACH_EM = 0.45

    /** Readium lays one of these over each line of the sentence. */
    fun element(tint: Int): String = """<div class="$CLASS" style="${style(tint)}"></div>"""

    fun style(tint: Int): String {
        val solid = rgb(tint)
        return "background-color: $solid !important; " +
            "box-shadow: 0 0 0 ${RING_PX}px $solid, 0 0 ${GLOW_BLUR_PX}px ${GLOW_SPREAD_PX}px $solid !important;"
    }

    /**
     * The room round the words and the soft corners, as Readium's own highlight has, and the one place the tint's
     * transparency is applied: the group, composited whole, then faded.
     */
    const val STYLESHEET = ".$CLASS { margin-left: -3px; margin-top: -${REACH_EM}em; padding-top: ${REACH_EM}em; padding-bottom: ${REACH_EM}em; " +
        "border-radius: 5px; box-sizing: content-box; } " +
        ".$CLASS:first-child { margin-top: -1px; padding-top: 0; } " +
        ".$CLASS:last-child { padding-bottom: 2px; } " +
        "[data-group=\"$GROUP\"] { opacity: $OPACITY !important; }"

    /** A colour as CSS, without its transparency: a box that is not see-through cannot add up with another. */
    fun rgb(color: Int): String = "rgb(${(color shr 16) and 0xFF}, ${(color shr 8) and 0xFF}, ${color and 0xFF})"
}
