package com.pocketds.hub.reader

/**
 * What the reader draws on the page besides the narration's glow (#62), as Readium's underline decoration: the colours of the
 * highlights, the note mark at the end of one that has a note, and the "Heard to here" mark where the voice stopped. One
 * template serves all three, told apart by the decoration's `kind`; [ReadAlongGlow] keeps the highlight decoration for the
 * narration. Pure strings and colours, so a JVM test pins them; the pixels are checked on a real page by
 * ReaderHighlightsTest.
 *
 * The highlight's box sits behind the words, as the narration's does, and is an opaque mix of the colour with the page
 * ([ReadAlongGlow.wash]): the ink is the page's own in every theme, and two highlights that overlap are one colour instead of a darker one.
 */
object ReaderMarks {
    const val GROUP = "annotations"
    const val NOTES_GROUP = "annotation-notes"
    const val HEARD_GROUP = "heard"
    const val HIGHLIGHT = "highlight"
    const val NOTE = "note"
    const val HEARD = "heard"
    const val KIND = "kind"
    const val HL = "pd-hl"

    /** The four colours as they are drawn: bright enough to read as the colour on the dark pages, which [wash] pales for the light ones. */
    fun base(color: HighlightColor): Int = when (color) {
        HighlightColor.YELLOW -> 0xFFF2D34B.toInt()
        HighlightColor.BLUE -> 0xFF6FA8D6.toInt()
        HighlightColor.PINK -> 0xFFE48AA6.toInt()
        HighlightColor.GREEN -> 0xFF86BE66.toInt()
    }

    /** The tint of a highlight on a page of [page] with [ink] text: as much of the colour as keeps the ink readable. */
    fun tint(color: HighlightColor, page: Int, ink: Int): Int = ReadAlongGlow.wash(base(color), page, ink)

    /** The element Readium lays over each line of a decoration of [kind], [tint] being the colour its style carries. */
    fun element(kind: String?, tint: Int): String = when (kind) {
        NOTE -> """<div class="pd-note" style="--mark: ${ReadAlongGlow.rgb(tint)};"></div>"""
        HEARD -> """<div class="pd-heard" style="--heard: ${ReadAlongGlow.rgb(tint)};"></div>"""
        else -> """<div class="$HL" style="background-color: ${ReadAlongGlow.rgb(tint)} !important;"></div>"""
    }

    /** A note's page as a mask so the mark takes the highlight's colour darkened by the page's ink. */
    private const val NOTE_MASK = "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' " +
        "stroke-width='3' stroke-linejoin='round'%3E%3Cpath d='M5 4h14v12l-4 4H5z'/%3E%3Cpath d='M15 20v-4h4'/%3E%3C/svg%3E"

    /**
     * The highlight behind the words with soft corners; the note mark up at the end of the last line, over the text and out of the
     * way of it; and the "Heard to here" label above the first line of the sentence, with an underline in the accent under all of it.
     */
    val STYLESHEET: String = """
        .$HL { z-index: -1 !important; margin-left: -2px; padding: 0 2px; box-sizing: content-box; border-radius: 3px; }
        .pd-note { z-index: 6 !important; background: transparent !important; }
        .pd-note::after { content: ""; position: absolute; left: 100%; top: -8px; width: 16px; height: 16px; margin-left: -4px;
            background-color: var(--mark); -webkit-mask: url("$NOTE_MASK") center / contain no-repeat; filter: brightness(.55) saturate(1.4); }
        .pd-heard { z-index: 6 !important; background: transparent !important; border-bottom: 2px solid var(--heard); box-sizing: border-box; }
        .pd-heard:first-child::before { content: "Heard to here"; position: absolute; left: 0; top: -15px; font: 600 9.5px sans-serif;
            letter-spacing: .04em; text-transform: uppercase; color: #2B2008; background: var(--heard); padding: 1px 6px; border-radius: 6px;
            white-space: nowrap; }
    """.trimIndent().replace(Regex("\\s*\\n\\s*"), " ")
}
