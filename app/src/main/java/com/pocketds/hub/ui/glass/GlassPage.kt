package com.pocketds.hub.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.WeakHashMap

/**
 * The colours the Glass page is tinted with right now, per window.
 *
 * HubActivity sets them each time the page re-tints to new artwork. A side
 * sheet or a dialog opening over the page reads them, so it is a tint of the
 * artwork it opened over rather than a neutral panel (GLASS_PLAN.md). Classic
 * never sets them.
 *
 * Keyed weakly by the Activity, like Theme's palettes; the values hold no
 * reference back to it.
 */
object GlassPage {
    private val palettes = WeakHashMap<Context, ArtworkPalette>()

    fun palette(context: Context): ArtworkPalette = palettes[window(context)] ?: ArtworkPalette.NEUTRAL

    fun set(context: Context, palette: ArtworkPalette) {
        palettes[window(context)] = palette
    }

    /** The Activity behind a view's context, which may be wrapped. */
    private fun window(context: Context): Context =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }
            .firstOrNull { it is Activity } ?: context
}
