package com.pocketds.hub.ui

import android.content.Context
import android.content.res.Configuration
import com.pocketds.hub.settings.ThemeSettings

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
    private val LIGHT = PocketColors(
        background = 0xFFF4F5F7.toInt(),
        cardSurface = 0xFFFFFFFF.toInt(),
        cardSurfacePressed = 0xFFDDE1E7.toInt(),
        primaryText = 0xFF12161C.toInt(),
        mutedText = 0xFF5F6873.toInt(),
        accent = 0xFF087D73.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        stripBackground = 0xFFE6E9EE.toInt(),
        focusRing = 0xFF12161C.toInt(),
        focusFill = 0xFFE2F6F4.toInt(),
        posterPlaceholder = 0xFFDDE1E7.toInt(),
        badgeAvailable = 0xFF1E9E5A.toInt(),
        badgePartial = 0xFF5B1E96.toInt(),
        badgePending = 0xFFC98A1B.toInt(),
        badgeFailed = 0xFFC5372C.toInt(),
        warningStrip = 0xFFD7F1EE.toInt(),
        warningStripText = 0xFF134E4A.toInt(),
        dangerText = 0xFFC5372C.toInt(),
        unreadSurface = 0xFFEAEFF5.toInt()
    )

    /**
     * Near-black rather than the old blue-grey: artwork is the colour on every
     * screen, and a neutral ground lets a poster or a pastel accent carry it.
     */
    private val DARK = PocketColors(
        background = 0xFF0A0D12.toInt(),
        cardSurface = 0xFF131821.toInt(),
        cardSurfacePressed = 0xFF222A36.toInt(),
        primaryText = 0xFFF3F5F8.toInt(),
        mutedText = 0xFFA3ADBB.toInt(),
        accent = 0xFF3DDBC6.toInt(),
        accentText = 0xFF0C2C28.toInt(),
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

    private val palettes = java.util.WeakHashMap<Context, PocketColors>()

    /** A stable palette object lets retained screen callbacks use the new accent. */
    fun colors(context: Context): PocketColors = palettes.getOrPut(context) {
        preview(context, com.pocketds.hub.settings.ContentModeSettings.get(context))
    }

    fun preview(context: Context, domain: com.pocketds.hub.state.ContentMode): PocketColors =
        palette(context, domain, isDark(context))

    /**
     * For anything drawn over video -- the player's controls and panels: the
     * dark palette whatever the app's theme, since the picture behind is dark,
     * with the person's media accent.
     */
    fun onVideo(context: Context): PocketColors = palette(context, com.pocketds.hub.state.ContentMode.MEDIA, dark = true)

    private fun palette(context: Context, domain: com.pocketds.hub.state.ContentMode, dark: Boolean): PocketColors {
        val base = if (dark) DARK else LIGHT
        val preset = com.pocketds.hub.settings.DomainPreferences.accent(context, domain)
        val accent = preset.color(dark)
        // The focus ring is the text colour, not the accent: white on the dark
        // theme reads on every poster and every pastel, where an accent ring
        // vanished into artwork of the same hue.
        return base.copy(accent=accent, focusRing=base.primaryText, accentText=preset.ink(dark),
            focusFill=androidx.core.graphics.ColorUtils.blendARGB(base.cardSurface,accent,if(dark) .14f else .09f))
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
