package com.pocketds.hub.ui.glass

import com.pocketds.hub.model.ArtworkColorSet

/**
 * The four colours the Glass look tints with, as ARGB ints. The hub works them
 * out from the artwork (`GET /v1/img/colors`); the app never analyses pictures.
 */
data class ArtworkPalette(val dominant: Int, val dark: Int, val vivid: Int, val light: Int) {
    companion object {
        /** Before the colours arrive, and for artwork that has none: the Glass base. */
        val NEUTRAL = ArtworkPalette(
            dominant = 0xFF2A3140.toInt(),
            dark = 0xFF0B0D12.toInt(),
            vivid = 0xFF8A97AD.toInt(),
            light = 0xFFE6EAF0.toInt()
        )

        /** Null when the hub sent something that is not four colours. */
        fun from(set: ArtworkColorSet): ArtworkPalette? {
            val dominant = GlassColors.parse(set.dominant) ?: return null
            val dark = GlassColors.parse(set.dark) ?: return null
            val vivid = GlassColors.parse(set.vivid) ?: return null
            val light = GlassColors.parse(set.light) ?: return null
            return ArtworkPalette(dominant, dark, vivid, light)
        }
    }
}

/**
 * Colour arithmetic for Glass, on plain ints so it runs in a JVM test
 * (android.graphics.Color is a stub there).
 *
 * The Pocket draws panels as tints rather than live blur: over an ambient layer
 * that is already blurred, a translucent tint of the artwork's colour reads as
 * frosted glass and costs nothing at 120 Hz, and nothing ever blurs over video.
 */
object GlassColors {
    /** The ground every panel is mixed into: the prototype's #12141C. */
    const val PANEL_BASE: Int = 0xFF12141C.toInt()
    /** How much of the artwork's colour a panel takes. */
    const val PANEL_TINT = 0.30f
    /** A panel's opacity over the ambient layer: 80%, as in the prototype. */
    const val PANEL_ALPHA = 0xCC
    /** The hairline round a panel and the light along its top edge. */
    const val EDGE: Int = 0x2BFFFFFF
    const val HIGHLIGHT: Int = 0x26FFFFFF

    /** Dark type on white: the selected tab, a lit icon button, a key cap. The prototype's #0B0D12. */
    const val INK: Int = 0xFF0B0D12.toInt()
    /** A key cap in the hint bar, and the words beside it: white at 92%. */
    const val KEY_CAP: Int = 0xEBFFFFFF.toInt()
    const val BAR_TEXT: Int = 0xEBFFFFFF.toInt()
    /** The hint bar's top edge: white at 10%. */
    const val BAR_EDGE: Int = 0x1AFFFFFF
    /** The L1 and R1 caps: white words at 75% in a white outline at 45%. */
    const val SHOULDER_TEXT: Int = 0xBFFFFFFF.toInt()
    const val SHOULDER_EDGE: Int = 0x73FFFFFF
    /**
     * The shade behind the top bar, half black fading to nothing, so the
     * capsules and the caps read over the brightest artwork.
     */
    const val BAR_SCRIM: Int = 0x80000000.toInt()
    /** A notification count on its button. */
    const val BADGE: Int = 0xFFFF5A5F.toInt()

    fun parse(hex: String): Int? {
        if (hex.length != 7 || hex[0] != '#') return null
        val rgb = hex.substring(1).toIntOrNull(16) ?: return null
        return (0xFF shl 24) or rgb
    }

    fun alpha(c: Int) = c ushr 24
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF

    fun withAlpha(c: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    /** [t] of the way from [a] to [b], every channel including alpha. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = Math.round(x + (y - x) * k)
        return (ch(alpha(a), alpha(b)) shl 24) or (ch(red(a), red(b)) shl 16) or
            (ch(green(a), green(b)) shl 8) or ch(blue(a), blue(b))
    }

    /** A panel over the ambient layer: the base, tinted by the artwork, at 80%. */
    fun panel(palette: ArtworkPalette): Int =
        withAlpha(mix(PANEL_BASE, palette.dominant, PANEL_TINT), PANEL_ALPHA)

    /** The bars along the top and the bottom of the Pocket: a touch more solid than a panel. */
    fun bar(palette: ArtworkPalette): Int =
        withAlpha(mix(PANEL_BASE, palette.dominant, PANEL_TINT * 0.9f), 0xD6)

    /**
     * A side sheet or a dialog: the panel's tint, nearly solid. A panel is see-
     * through because what is under it is the already blurred page; a sheet
     * opens over a screen's own words and posters, and without a blur, words
     * showing through at a fifth of their strength fought the sheet's own
     * (the hero's runtime ran through "Who is watching?").
     */
    fun sheet(palette: ArtworkPalette): Int = withAlpha(panel(palette), SHEET_ALPHA)

    const val SHEET_ALPHA = 0xF2

    /** WCAG relative luminance, 0 for black to 1 for white. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
    }

    /** WCAG contrast ratio of two opaque colours, from 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** What a translucent [top] looks like over an opaque [under]. */
    fun over(top: Int, under: Int): Int = withAlpha(mix(under, withAlpha(top, 0xFF), alpha(top) / 255f), 0xFF)
}
