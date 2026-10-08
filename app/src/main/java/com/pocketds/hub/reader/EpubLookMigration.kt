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
}
