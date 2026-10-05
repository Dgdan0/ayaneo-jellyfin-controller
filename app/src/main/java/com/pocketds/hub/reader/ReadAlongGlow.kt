package com.pocketds.hub.reader

import java.util.Locale

/**
 * The sentence being read, as Readium draws it (#16, X7): a soft wash of the
 * accent with a glow round it, as the prototype's read-along has, instead of
 * Readium's flat box in a fixed gold. The tint is the Books accent, handed in
 * with each highlight. Pure strings, so a JVM test pins them; EpubReaderScreen
 * gives them to Readium as its highlight template.
 */
object ReadAlongGlow {
    const val CLASS = "pocket-narration"
    /** How strong the wash under the words, the ring round them and the glow beyond. */
    const val WASH = 0.28
    const val RING = 0.22
    const val GLOW = 0.42

    /** Readium lays one of these over each line of the sentence. */
    fun element(tint: Int): String = """<div class="$CLASS" style="${style(tint)}"></div>"""

    fun style(tint: Int): String =
        "background-color: ${rgba(tint, WASH)} !important; " +
            "box-shadow: 0 0 0 3px ${rgba(tint, RING)}, 0 0 14px 4px ${rgba(tint, GLOW)} !important;"

    /** The room round the words and the soft corners, as Readium's own highlight has. */
    const val STYLESHEET = ".$CLASS { margin-left: -3px; padding-right: 6px; margin-top: -1px; padding-bottom: 2px; " +
        "border-radius: 5px; box-sizing: border-box; }"

    fun rgba(color: Int, alpha: Double): String =
        "rgba(${(color shr 16) and 0xFF}, ${(color shr 8) and 0xFF}, ${color and 0xFF}, ${String.format(Locale.US, "%.2f", alpha)})"
}
