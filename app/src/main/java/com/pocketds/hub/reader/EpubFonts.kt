package com.pocketds.hub.reader

/**
 * The reader's typefaces (#47): pure, so the menu, the migration and the files each face needs are tested
 * without a screen. [EpubFontDeclarations] declares them to Readium and [EpubTypefaces] draws the menu's
 * "Aa" in them.
 *
 * Literata is the default on every device. Its small letters, capitals and stroke weights are the closest
 * to Kindle's Bookerly of everything measured (READER_TYPOGRAPHY_PLAN.md, 2.1): Times, which "Serif" was, has
 * hairline strokes that fade on a backlit screen. All of them are SIL Open Font License 1.1; the texts are in
 * `assets/licenses` and on the Fonts and licences page.
 *
 *  - **Literata** 3.103 (Google Fonts; The Literata Project Authors): one variable file for the roman and one
 *    for the italic, the weight 200 to 900 and the optical size 7 to 72, which the web view follows from the
 *    text's size by itself.
 *  - **Atkinson Hyperlegible Next** 2.001 (Braille Institute; Google Fonts): variable, weight 200 to 800.
 *  - **Charis** 7.000 (SIL Global's own release): Charter, extended. Static Regular, Bold, Italic and Bold
 *    Italic. Its Reserved Font Names are "Charis" and "SIL", so the files are shipped as released: not
 *    subsetted, not renamed inside.
 *
 * None of them has Hebrew; the system draws it in its own face.
 */
object EpubFonts {
    /** The assets Readium may serve to a book: the fonts, under `fonts/`. */
    const val SERVED_ASSETS = "fonts/.*"

    /** One file of a face: the asset, whether it is the italic, and the weights it covers (a variable file spans a range). */
    data class Source(val asset: String, val italic: Boolean, val minWeight: Int, val maxWeight: Int) {
        val variable: Boolean get() = minWeight != maxWeight
    }

    /**
     * A choice in the menu. [id] is what a device stores (`publisher` as it always was, for the book's own
     * font); [css] is the family name Readium is told and the font is declared under, or null for the book's own.
     */
    enum class Face(val id: String, val label: String, val css: String?, val sources: List<Source>) {
        ORIGINAL("publisher", "Original", null, emptyList()),
        LITERATA("literata", "Literata", "Literata", listOf(
            Source("fonts/Literata.ttf", false, 200, 900),
            Source("fonts/Literata-Italic.ttf", true, 200, 900)
        )),
        CHARIS("charis", "Charis", "Charis", listOf(
            Source("fonts/Charis-Regular.ttf", false, 400, 400),
            Source("fonts/Charis-Bold.ttf", false, 700, 700),
            Source("fonts/Charis-Italic.ttf", true, 400, 400),
            Source("fonts/Charis-BoldItalic.ttf", true, 700, 700)
        )),
        ATKINSON("atkinson", "Atkinson Hyperlegible", "Atkinson", listOf(
            Source("fonts/AtkinsonHyperlegibleNext.ttf", false, 200, 800),
            Source("fonts/AtkinsonHyperlegibleNext-Italic.ttf", true, 200, 800)
        ));

        /** The file that draws this face's "Aa" in the menu: the roman. */
        val preview: Source? get() = sources.firstOrNull { !it.italic }
    }

    /** What a new device reads in, and what "Reset text style" brings back. */
    val DEFAULT: Face = Face.LITERATA

    /** The menu's order: the book's own, then the reader's three. */
    val CHOICES: List<Face> = Face.entries

    /** The face a stored id names; a name this build does not know is the default. */
    fun face(id: String?): Face = Face.entries.firstOrNull { it.id == id } ?: DEFAULT

    /** Whether [id] is one of the menu's. */
    fun known(id: String?): Boolean = Face.entries.any { it.id == id }

    /** Every file the faces need, in the assets. */
    val ASSETS: List<String> get() = Face.entries.flatMap { face -> face.sources.map { it.asset } }
}
