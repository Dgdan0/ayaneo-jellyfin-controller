package com.pocketds.hub.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import java.util.WeakHashMap

/**
 * The colours the Glass page is tinted with right now, per window, and the
 * glass that follows them.
 *
 * HubActivity sets them each time the page re-tints to new artwork. A side
 * sheet or a dialog opening over the page reads them, so it is a tint of the
 * artwork it opened over rather than a neutral panel (GLASS_PLAN.md), and the
 * glass that stays on screen -- the bars, a Details pill, a card's play disc --
 * [follow]s them. Classic never sets them.
 *
 * Keyed weakly by the Activity, like Theme's palettes. A follower is held only
 * while its view is attached, so a rebuilt window (a new look, a new profile)
 * leaves nothing behind holding the old one.
 */
object GlassPage {
    private class Page {
        var palette: ArtworkPalette = ArtworkPalette.NEUTRAL
        val followers = LinkedHashSet<(ArtworkPalette) -> Unit>()
    }

    private val pages = WeakHashMap<Context, Page>()

    fun palette(context: Context): ArtworkPalette = pages[window(context)]?.palette ?: ArtworkPalette.NEUTRAL

    fun set(context: Context, palette: ArtworkPalette) {
        val page = page(context)
        if (page.palette == palette) return
        page.palette = palette
        page.followers.toList().forEach { it(palette) }
    }

    /**
     * Calls [onTint] with the page's colours now, and again each time the page
     * re-tints, for as long as [view] is attached to its window.
     */
    fun follow(view: View, onTint: (ArtworkPalette) -> Unit) {
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attached: View) {
                val page = page(attached.context)
                page.followers += onTint
                onTint(page.palette)
            }

            override fun onViewDetachedFromWindow(detached: View) {
                pages[window(detached.context)]?.followers?.remove(onTint)
            }
        }
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view) else onTint(palette(view.context))
    }

    private fun page(context: Context): Page = pages.getOrPut(window(context)) { Page() }

    /** The Activity behind a view's context, which may be wrapped. */
    private fun window(context: Context): Context =
        generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { base -> base !== it } }
            .firstOrNull { it is Activity } ?: context
}
