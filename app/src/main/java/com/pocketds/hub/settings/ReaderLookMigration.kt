package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubLookMigration

/**
 * The reader's look changed under devices that already had one (#47), and each device is moved once, at
 * launch, as [RetiredSettings] clears what is not read any more. Each step leaves nothing to find the second
 * time.
 */
object ReaderLookMigration {
    /** Comfort's black-page switch, which the Dark theme replaced. */
    private const val KEY_BLACK_PAGE = "reader_comfort_black_page"

    /** The typeface moved to Literata once; this marks that it has, in the reader's own settings file. */
    private const val KEY_FONT_MOVED = "fontMoved47"

    fun run(context: Context) {
        blackPage(context)
        fontFamily(context)
    }

    /**
     * The device's typeface becomes Literata once ([EpubLookMigration.fontFamily]); a device with nothing stored
     * has Literata already and is only marked, so a later choice of Original is never undone.
     */
    private fun fontFamily(context: Context) {
        val store = context.getSharedPreferences("epub-reader", Context.MODE_PRIVATE)
        val moved = store.getBoolean(KEY_FONT_MOVED, false)
        if (moved) return
        val next = EpubLookMigration.fontFamily(store.getString("fontFamily", null), alreadyMoved = false)
        store.edit().apply { next?.let { putString("fontFamily", it) }; putBoolean(KEY_FONT_MOVED, true) }.apply()
    }

    /** A device that read on a black page moves to the Dark theme, and the switch's key goes. */
    private fun blackPage(context: Context) {
        val prefs = Prefs.of(context)
        if (!prefs.contains(KEY_BLACK_PAGE)) return
        val wasOn = prefs.getBoolean(KEY_BLACK_PAGE, false)
        if (wasOn) {
            val look = EpubAppearanceStore.load(context)
            EpubAppearanceStore.save(context, look.copy(theme = EpubLookMigration.theme(true, look.theme)))
        }
        prefs.edit().remove(KEY_BLACK_PAGE).apply()
    }
}
