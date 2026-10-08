package com.pocketds.hub.reader

import kotlinx.serialization.Serializable

/** The stored ids stay: [DARK] is the grey (called Dim), [BLACK] the true black (called Dark); see [EpubPagePalette]. */
@Serializable
enum class EpubTheme { SYSTEM, LIGHT, SEPIA, DARK, BLUE, BLACK }

@Serializable
enum class EpubColumns { AUTO, ONE, TWO }

/**
 * The reader's look. The typography defaults are Kindle's (#42, Part 3): the reader's own,
 * not the book's. A book's stylesheet keeps a tight `line-height: 1.2em`, no hyphenation and
 * ragged gaps in two columns; Readium's advanced mode with justified, hyphenated text at 1.5
 * line spacing reads clearly better, and keeps the book's pictures, ornaments and drop caps.
 * [publisherStyles] on is the way back to the book's own look. Only a device with nothing
 * stored gets these: [EpubAppearanceStore] falls back to them key by key.
 */
@Serializable
data class EpubReaderPreferences(
    val theme: EpubTheme = EpubTheme.SEPIA,
    /** The face's stored id ([EpubFonts.Face.id]): Literata for a new device; `publisher` is the book's own font. */
    val fontFamily: String = EpubFonts.DEFAULT.id,
    val fontScale: Float = 1.3f,
    val lineHeight: Float = 1.5f,
    val pageMargins: Float = 1.0f,
    val columns: EpubColumns = EpubColumns.AUTO,
    val scroll: Boolean = false,
    val publisherStyles: Boolean = false,
    val textAlignment: String = "justify",
    val onePagePerScreen: Boolean = false,
    /** Readium's `hyphens`: a book's words break at their syllables, which keeps justified lines even. */
    val hyphenation: Boolean = true
)

object EpubLayoutPolicy {
    /** The sizes the slider offers (#47): 70 to 200% in steps of 10, the 14 marks of Kindle's. */
    val SIZES = com.pocketds.hub.ui.ValueRange(0.7f, 2f, 0.1f)

    /** "130%", for the slider. */
    fun sizeLabel(scale: Float): String = "${Math.round(scale * 100)}%"

    /** The line spacings the sheet offers (#47): 1.5 is the default and, in Literata, Kindle's own spacing. */
    val SPACING: List<Pair<Float, String>> = listOf(1.3f to "Tight", 1.5f to "Relaxed", 1.8f to "Open")

    private const val AUTO_TWO_COLUMN_MIN_WIDTH_DP = 840

    fun columnCount(
        preferences: EpubReaderPreferences,
        viewportWidthDp: Int,
        publicationAllowsSpreads: Boolean
    ): Int {
        if (preferences.onePagePerScreen || preferences.scroll || !publicationAllowsSpreads) return 1
        return when (preferences.columns) {
            EpubColumns.ONE -> 1
            EpubColumns.TWO -> 2
            EpubColumns.AUTO -> if (viewportWidthDp >= AUTO_TWO_COLUMN_MIN_WIDTH_DP) 2 else 1
        }
    }

    fun selectColumns(value: EpubReaderPreferences, columns: EpubColumns): EpubReaderPreferences =
        value.copy(columns = columns, scroll = if (columns == EpubColumns.TWO) false else value.scroll, onePagePerScreen = false)

    fun selectScroll(value: EpubReaderPreferences, enabled: Boolean): EpubReaderPreferences =
        value.copy(scroll = enabled, columns = if (enabled && value.columns == EpubColumns.TWO) EpubColumns.AUTO else value.columns,
            onePagePerScreen = if (enabled) false else value.onePagePerScreen)

    fun selectOnePage(value: EpubReaderPreferences, enabled: Boolean): EpubReaderPreferences =
        if (enabled) value.copy(onePagePerScreen = true, columns = EpubColumns.ONE, scroll = false)
        else value.copy(onePagePerScreen = false)

    /**
     * "Reset text style" (#42, Part 3): the reader's own typography as a device with nothing stored has it, for a
     * device that changed its look once and so never saw the new default. The four settings of the text's style
     * (Publisher styling, alignment, hyphenation, line spacing) and the typeface (Literata, #47) go back to
     * [EpubReaderPreferences]'s defaults, read from an instance so a changed default cannot be missed here; the
     * size, theme, margins and columns stay as they are.
     */
    fun resetTextStyle(value: EpubReaderPreferences): EpubReaderPreferences {
        val defaults = EpubReaderPreferences()
        return value.copy(
            publisherStyles = defaults.publisherStyles,
            textAlignment = defaults.textAlignment,
            hyphenation = defaults.hyphenation,
            lineHeight = defaults.lineHeight,
            fontFamily = defaults.fontFamily
        )
    }

    /** The Spacing row's line: "1.5 · Balanced margins", from the look it opens (#47). */
    fun spacingSummary(value: EpubReaderPreferences): String =
        "${String.format(java.util.Locale.US, "%.1f", value.lineHeight)} · ${PageGeometry.preset(value.pageMargins).label} margins"

    /** What the reset does, for its row: "Literata, justified, hyphenated, 1.5 spacing", said from the defaults it applies. */
    fun textStyleSummary(): String {
        val defaults = EpubReaderPreferences()
        return listOfNotNull(
            EpubFonts.face(defaults.fontFamily).label,
            if (defaults.publisherStyles) "the book's own style" else null,
            when (defaults.textAlignment) { "justify" -> "justified"; "center" -> "centred"; else -> null },
            if (defaults.hyphenation) "hyphenated" else null,
            String.format(java.util.Locale.US, "%.1f spacing", defaults.lineHeight)
        ).mapIndexed { index, part -> if (index == 0) part.replaceFirstChar { it.uppercase() } else part }.joinToString(", ")
    }
}

object EpubChromePolicy {
    fun handlesTap(horizontalFraction: Float, controlsVisible: Boolean) = controlsVisible || horizontalFraction in .3f.. .7f
}

/** Appearance is applied live and committed with each change. Closing retains the last setting. */
class EpubPreferenceState(initial: EpubReaderPreferences) {
    var visible: EpubReaderPreferences = initial
        private set
    var saved: EpubReaderPreferences = initial
        private set
    var dirty: Boolean = false
        private set

    fun preview(value: EpubReaderPreferences) {
        visible = value
    }

    fun cancel() {
        visible = saved
    }

    fun commit(): Boolean {
        if (visible == saved) return false
        saved = visible
        dirty = true
        return true
    }

    fun markPersisted() {
        dirty = false
    }
}
