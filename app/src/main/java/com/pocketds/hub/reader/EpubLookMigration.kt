package com.pocketds.hub.reader

/**
 * What a device's stored reader look becomes when the look changed under it (#47). Pure, so each step is
 * tested; [com.pocketds.hub.settings.ReaderLookMigration] reads the stored values, applies these once and
 * writes the result.
 */
object EpubLookMigration {
    /**
     * Comfort's black-page switch is gone: a device that had it on reads on a black page, which is the Dark
     * theme ([EpubTheme.BLACK]) now. One that had it off keeps the theme it chose.
     */
    fun theme(blackPageWasOn: Boolean, theme: EpubTheme): EpubTheme = if (blackPageWasOn) EpubTheme.BLACK else theme

    /**
     * Every device reads in Literata from the update on (#47): the typeface a device stored before then was
     * `publisher` (the book's own, which meant Times or Noto Serif), `serif` (the same Times) or `sans-serif`,
     * and none of them is what the owner chose. The sans moves to the menu's sans, Atkinson Hyperlegible; the
     * rest to Literata. Once: [alreadyMoved] says it has been, and a face the menu has now (Original too, picked
     * after the move) is left alone. Null when nothing is to change.
     */
    fun fontFamily(stored: String?, alreadyMoved: Boolean): String? = when {
        alreadyMoved || stored == null -> null
        stored == "sans-serif" -> EpubFonts.Face.ATKINSON.id
        else -> EpubFonts.DEFAULT.id
    }
}
