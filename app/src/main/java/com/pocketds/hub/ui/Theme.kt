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
    val background: Int,
    val cardSurface: Int,
    val cardSurfacePressed: Int,
    val primaryText: Int,
    val mutedText: Int,
    val accent: Int,
    val accentText: Int,
    val stripBackground: Int,
    /** The focus ring. Deliberately the accent: one colour means one meaning. */
    val focusRing: Int,
    /** Behind a focused card, under the poster. */
    val focusFill: Int,
    /** Flat fill shown while a poster loads. Never a spinner -- see PosterCardView. */
    val posterPlaceholder: Int,
    val badgeAvailable: Int,
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
    val badgePartial: Int,
    val badgePending: Int,
    val badgeFailed: Int,
    /** Stale-cache / degraded-service strip. */
    val warningStrip: Int,
    val warningStripText: Int,
    val dangerText: Int,
    /** Quiet inner tint for notification cards that have not been focused yet. */
    val unreadSurface: Int
)

object Theme {
    private val LIGHT = PocketColors(
        background = 0xFFF1F1F4.toInt(),
        cardSurface = 0xFFFFFFFF.toInt(),
        cardSurfacePressed = 0xFFD8D8DE.toInt(),
        primaryText = 0xFF1B1B1F.toInt(),
        mutedText = 0xFF6E6E76.toInt(),
        accent = 0xFF0FADA0.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        stripBackground = 0xFFE3E3E8.toInt(),
        focusRing = 0xFF0FADA0.toInt(),
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
        background = 0xFF15151A.toInt(),
        cardSurface = 0xFF25252B.toInt(),
        cardSurfacePressed = 0xFF3A3A42.toInt(),
        primaryText = 0xFFF1F1F3.toInt(),
        mutedText = 0xFF9A9AA4.toInt(),
        accent = 0xFF2BE0CE.toInt(),
        accentText = 0xFF0A0A0B.toInt(),
        stripBackground = 0xFF1D1D22.toInt(),
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

    fun colors(context: Context): PocketColors = if (isDark(context)) DARK else LIGHT

    fun isDark(context: Context): Boolean = when (ThemeSettings.getMode(context)) {
        ThemeSettings.Mode.LIGHT -> false
        ThemeSettings.Mode.DARK -> true
        ThemeSettings.Mode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
