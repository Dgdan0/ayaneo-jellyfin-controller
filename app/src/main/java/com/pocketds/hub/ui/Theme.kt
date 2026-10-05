package com.pocketds.hub.ui

import android.content.Context
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors

/**
 * The palette. Teal stays the default accent so this and the sibling keyboard
 * project read as a set; each media type can pick its own pastel instead
 * (settings/AccentPreset). On top of that: a ring colour, a card surface
 * distinct from the page, a poster placeholder, and the availability badges.
 */
data class PocketColors(
    var background: Int,
    var cardSurface: Int,
    var cardSurfacePressed: Int,
    var primaryText: Int,
    var mutedText: Int,
    var accent: Int,
    var accentText: Int,
    /**
     * Words and icons drawn on a [primaryText] fill: a selected tab's white
     * pill, the status pill. The page has no colour of its own (see Theme), so
     * they have their own token.
     */
    var inverseText: Int,
    var stripBackground: Int,
    /** The focus ring: the text colour, so it reads on any poster and any accent. */
    var focusRing: Int,
    /** Behind a focused card, under the poster. */
    var focusFill: Int,
    /** Flat fill shown while a poster loads. Never a spinner -- see PosterCardView. */
    var posterPlaceholder: Int,
    var badgeAvailable: Int,
    /**
     * Partly in the library.
     *
     * Ultra Violet, not a shade of green: a series missing half its episodes is
     * not "you have this". Violet was chosen over the indigo and near-black in
     * the same palette because a badge sits on top of a poster -- indigo is too
     * close to the card surface to register, and the darkest violet reads as a
     * hole punched in the artwork. This one is unmistakably not the green,
     * amber or red used by every other state, and still carries white text.
     */
    var badgePartial: Int,
    var badgePending: Int,
    var badgeFailed: Int,
    /** Stale-cache / degraded-service strip. */
    var warningStrip: Int,
    var warningStripText: Int,
    var dangerText: Int,
    /**
     * Quiet inner tint for notifications not yet seen: a raised neutral. It was
     * a dark red, and with ninety unread a whole column read as errors; red is
     * for problems, which the dot and the count already carry.
     */
    var unreadSurface: Int
)

object Theme {
    /**
     * Near-black rather than the old blue-grey: artwork is the colour on every
     * screen, and a neutral ground lets a poster or a pastel accent carry it.
     * What is drawn over video uses it as it is ([onVideo]).
     */
    private val DARK = PocketColors(
        background = 0xFF0A0D12.toInt(),
        cardSurface = 0xFF131821.toInt(),
        cardSurfacePressed = 0xFF222A36.toInt(),
        primaryText = 0xFFF3F5F8.toInt(),
        mutedText = 0xFFA3ADBB.toInt(),
        accent = 0xFF3DDBC6.toInt(),
        accentText = 0xFF0C2C28.toInt(),
        inverseText = 0xFF0A0D12.toInt(),
        stripBackground = 0xFF141922.toInt(),
        focusRing = 0xFFF3F5F8.toInt(),
        focusFill = 0xFF15302E.toInt(),
        posterPlaceholder = 0xFF1A212C.toInt(),
        badgeAvailable = 0xFF3ECB80.toInt(),
        badgePartial = 0xFF7B34C4.toInt(),
        badgePending = 0xFFE0AE4A.toInt(),
        badgeFailed = 0xFFE0685C.toInt(),
        warningStrip = 0xFF123C38.toInt(),
        warningStripText = 0xFFA7F3E8.toInt(),
        dangerText = 0xFFFF7A7A.toInt(),
        unreadSurface = 0xFF1C2431.toInt()
    )

    /**
     * The page (GLASS_PLAN.md): always dark, with the ambient layer behind every
     * screen as the page. So the page colour paints nothing: its alpha is zero,
     * which turns off every screen's own fill, the page-title strip and the
     * bars' solid ground in one place. Its RGB stays the dark base, because a
     * fade into the page (ScrimDrawable) still fades into that darkness. Cards
     * and chips are glass tints rather than solid surfaces.
     */
    private val GLASS = DARK.copy(
        background = GlassColors.withAlpha(ArtworkPalette.NEUTRAL.dark, 0),
        inverseText = GlassColors.INK,
        cardSurface = GlassColors.panel(ArtworkPalette.NEUTRAL),
        stripBackground = GlassColors.bar(ArtworkPalette.NEUTRAL)
    )

    /** The palettes before the accents: the page's, and the solid one drawn over video. */
    internal val page: PocketColors get() = GLASS
    internal val video: PocketColors get() = DARK

    private val palettes = java.util.WeakHashMap<Context, PocketColors>()

    /** A stable palette object lets retained screen callbacks use the new accent. */
    fun colors(context: Context): PocketColors = palettes.getOrPut(context) {
        preview(context, com.pocketds.hub.settings.ContentModeSettings.get(context))
    }

    fun preview(context: Context, domain: com.pocketds.hub.state.ContentMode): PocketColors = palette(context, domain, GLASS)

    /**
     * For anything drawn over video -- the player's controls and panels: the
     * dark palette, solid, with the person's media accent. Nothing is tinted
     * or see-through over a moving picture.
     */
    fun onVideo(context: Context): PocketColors = palette(context, com.pocketds.hub.state.ContentMode.MEDIA, DARK)

    private fun palette(context: Context, domain: com.pocketds.hub.state.ContentMode, base: PocketColors): PocketColors {
        val preset = com.pocketds.hub.settings.DomainPreferences.accent(context, domain)
        val accent = preset.color
        // The focus ring is the text colour, not the accent: white reads on
        // every poster and every pastel, where an accent ring vanished into
        // artwork of the same hue.
        return base.copy(accent=accent, focusRing=base.primaryText, accentText=preset.ink,
            focusFill=androidx.core.graphics.ColorUtils.blendARGB(base.cardSurface,accent,.14f))
    }

    /** Repaint existing views only. This never replaces a screen, WebView or media session. */
    fun refresh(context: Context, root: android.view.View, domain: com.pocketds.hub.state.ContentMode = com.pocketds.hub.settings.ContentModeSettings.get(context)) {
        val old = colors(context).copy()
        val next = preview(context, domain)
        if (old == next) return
        listOf(colors(context)).forEach { it.background=next.background; it.cardSurface=next.cardSurface; it.cardSurfacePressed=next.cardSurfacePressed; it.primaryText=next.primaryText; it.mutedText=next.mutedText; it.accent=next.accent; it.accentText=next.accentText; it.inverseText=next.inverseText; it.stripBackground=next.stripBackground; it.focusRing=next.focusRing; it.focusFill=next.focusFill; it.posterPlaceholder=next.posterPlaceholder; it.badgeAvailable=next.badgeAvailable; it.badgePartial=next.badgePartial; it.badgePending=next.badgePending; it.badgeFailed=next.badgeFailed; it.warningStrip=next.warningStrip; it.warningStripText=next.warningStripText; it.dangerText=next.dangerText; it.unreadSurface=next.unreadSurface }
        AccentRebinder.apply(root,old,next)
    }
}
