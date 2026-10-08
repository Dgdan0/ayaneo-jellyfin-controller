package com.pocketds.hub.reader

/**
 * The reader's page colours (#47): the page and the ink on it, for each theme. Pure, so the palettes, the
 * names and what "Use system colours" resolves to are tested without a screen.
 *
 * The stored ids are stable: [EpubTheme.DARK] is still the grey, and is called Dim; the true black is
 * [EpubTheme.BLACK], called Dark, as Kindle calls it. Sepia and Dark are Kindle's own colours, measured:
 * `#FCF0D9` on `#5A4931` (7.7:1) and `#AFAFAF` on `#000000` (9.6:1). Dim keeps its grey page with softer
 * ink, `#C8C8C2` (9.7:1), so the grey is gentle in a lit room and the black is best in the dark.
 */
object EpubPagePalette {
    /** The themes in the order the menu shows them (Use system colours is its own row). */
    val CHOICES: List<EpubTheme> = listOf(EpubTheme.LIGHT, EpubTheme.SEPIA, EpubTheme.DARK, EpubTheme.BLACK, EpubTheme.BLUE)

    /** What a theme is called in the menu. */
    fun label(theme: EpubTheme): String = when (theme) {
        EpubTheme.SYSTEM -> "System"
        EpubTheme.LIGHT -> "Paper"
        EpubTheme.SEPIA -> "Sepia"
        EpubTheme.DARK -> "Dim"
        EpubTheme.BLACK -> "Dark"
        EpubTheme.BLUE -> "Blue"
    }

    /** The theme that is really drawn: "Use system colours" is Paper by day and Dark at night. */
    fun resolve(theme: EpubTheme, night: Boolean): EpubTheme =
        if (theme == EpubTheme.SYSTEM) (if (night) EpubTheme.BLACK else EpubTheme.LIGHT) else theme

    /** The page, then the ink on it, as ARGB. */
    fun of(theme: EpubTheme, night: Boolean = false): Pair<Int, Int> = when (resolve(theme, night)) {
        EpubTheme.LIGHT, EpubTheme.SYSTEM -> 0xfffbfaf6.toInt() to 0xff282b29.toInt()
        EpubTheme.SEPIA -> 0xfffcf0d9.toInt() to 0xff5a4931.toInt()
        EpubTheme.DARK -> 0xff202020.toInt() to 0xffc8c8c2.toInt()
        EpubTheme.BLACK -> 0xff000000.toInt() to 0xffafafaf.toInt()
        EpubTheme.BLUE -> 0xff1d303d.toInt() to 0xffdce6e8.toInt()
    }

    /** Whether the page is dark: what Readium's own dark theme is for. */
    fun isDark(theme: EpubTheme, night: Boolean = false): Boolean =
        resolve(theme, night).let { it == EpubTheme.DARK || it == EpubTheme.BLACK || it == EpubTheme.BLUE }
}
