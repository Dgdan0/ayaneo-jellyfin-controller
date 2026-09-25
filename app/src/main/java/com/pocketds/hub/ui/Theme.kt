package com.pocketds.hub.ui

import android.content.Context
import android.content.res.Configuration
import com.pocketds.hub.settings.ThemeSettings

/**
 * The palette. Keeps the sibling keyboard project's teal accent so the two apps
 * read as a set, and adds what a focus-driven, poster-heavy UI needs on top:
 * a ring colour, a card surface distinct from the page, a poster placeholder,
 * and the availability badge colours.
 */
data class PocketColors(
    var background: Int,
    var cardSurface: Int,
    var cardSurfacePressed: Int,
    var primaryText: Int,
    var mutedText: Int,
    var accent: Int,
    var accentText: Int,
    var stripBackground: Int,
    /** The focus ring. Deliberately the accent: one colour means one meaning. */
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
    /** Quiet inner tint for notification cards that have not been focused yet. */
    var unreadSurface: Int
)

object Theme {
    private val LIGHT = PocketColors(
        background = 0xFFF5F6F4.toInt(),
        cardSurface = 0xFFFFFFFF.toInt(),
        cardSurfacePressed = 0xFFD8D8DE.toInt(),
        primaryText = 0xFF1B1B1F.toInt(),
        mutedText = 0xFF6E6E76.toInt(),
        accent = 0xFF087D73.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        stripBackground = 0xFFE3E3E8.toInt(),
        focusRing = 0xFF087D73.toInt(),
        focusFill = 0xFFE2F6F4.toInt(),
        posterPlaceholder = 0xFFDCDCE2.toInt(),
        badgeAvailable = 0xFF1E9E5A.toInt(),
        badgePartial = 0xFF5B1E96.toInt(),
        badgePending = 0xFFC98A1B.toInt(),
        badgeFailed = 0xFFC5372C.toInt(),
        warningStrip = 0xFFD7F1EE.toInt(),
        warningStripText = 0xFF134E4A.toInt(),
        dangerText = 0xFFC5372C.toInt(),
        unreadSurface = 0xFFFFE5E2.toInt()
    )

    private val DARK = PocketColors(
        background = 0xFF151C23.toInt(),
        cardSurface = 0xFF202B35.toInt(),
        cardSurfacePressed = 0xFF3A3A42.toInt(),
        primaryText = 0xFFF1F1F3.toInt(),
        mutedText = 0xFF9A9AA4.toInt(),
        accent = 0xFF2BE0CE.toInt(),
        accentText = 0xFF0A0A0B.toInt(),
        stripBackground = 0xFF19232C.toInt(),
        focusRing = 0xFF2BE0CE.toInt(),
        focusFill = 0xFF15302E.toInt(),
        posterPlaceholder = 0xFF2A2A31.toInt(),
        badgeAvailable = 0xFF3ECB80.toInt(),
        badgePartial = 0xFF7B34C4.toInt(),
        badgePending = 0xFFE0AE4A.toInt(),
        badgeFailed = 0xFFE0685C.toInt(),
        warningStrip = 0xFF123C38.toInt(),
        warningStripText = 0xFFA7F3E8.toInt(),
        dangerText = 0xFFE0685C.toInt(),
        unreadSurface = 0xFF422628.toInt()
    )

    private val palettes = java.util.WeakHashMap<Context, PocketColors>()

    /** A stable palette object lets retained screen callbacks use the new accent. */
    fun colors(context: Context): PocketColors = palettes.getOrPut(context) {
        preview(context, com.pocketds.hub.settings.ContentModeSettings.get(context))
    }

    fun preview(context: Context, domain: com.pocketds.hub.state.ContentMode): PocketColors {
        val dark = isDark(context)
        val base = if (dark) DARK else LIGHT
        val preset = com.pocketds.hub.settings.DomainPreferences.accent(context, domain)
        val accent = if (dark) preset.dark else preset.light
        return base.copy(accent=accent, focusRing=accent,
            accentText=if (dark) 0xff132c32.toInt() else -1,
            focusFill=androidx.core.graphics.ColorUtils.blendARGB(base.cardSurface,accent,if(dark) .16f else .09f))
    }

    /** Repaint existing views only. This never replaces a screen, WebView or media session. */
    fun refresh(context: Context, root: android.view.View, domain: com.pocketds.hub.state.ContentMode = com.pocketds.hub.settings.ContentModeSettings.get(context)) {
        val old = colors(context).copy()
        val next = preview(context, domain)
        if (old == next) return
        listOf(colors(context)).forEach { it.background=next.background; it.cardSurface=next.cardSurface; it.cardSurfacePressed=next.cardSurfacePressed; it.primaryText=next.primaryText; it.mutedText=next.mutedText; it.accent=next.accent; it.accentText=next.accentText; it.stripBackground=next.stripBackground; it.focusRing=next.focusRing; it.focusFill=next.focusFill; it.posterPlaceholder=next.posterPlaceholder; it.badgeAvailable=next.badgeAvailable; it.badgePartial=next.badgePartial; it.badgePending=next.badgePending; it.badgeFailed=next.badgeFailed; it.warningStrip=next.warningStrip; it.warningStripText=next.warningStripText; it.dangerText=next.dangerText; it.unreadSurface=next.unreadSurface }
        AccentRebinder.apply(root,old,next)
    }

    fun isDark(context: Context): Boolean = when (ThemeSettings.getMode(context)) {
        ThemeSettings.Mode.LIGHT -> false
        ThemeSettings.Mode.DARK -> true
        ThemeSettings.Mode.SYSTEM -> {
            val uiMode = context.applicationContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
