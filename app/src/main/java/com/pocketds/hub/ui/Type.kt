package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.pocketds.hub.R

/**
 * The two typefaces and the handful of roles they play.
 *
 * Body text needs nothing: the theme makes Figtree the default for every
 * TextView. Titles are Bricolage Grotesque, and they go through here so the
 * sizes stay a short, deliberate list instead of a number picked per screen.
 * Sizes are sp on the device's 853 x 456 dp screen.
 */
object Type {
    enum class Role(val sizeSp: Float, val weight: Int, val tracking: Float = 0f) {
        /** A hero or detail page's title. */
        HERO(30f, 700, -0.02f),
        /** A screen's own heading: "On your Pocket", "Settings". */
        SCREEN(22f, 700, -0.01f),
        /** A row or panel heading: "Continue watching". */
        HEADING(16f, 600),
        /** A small heading inside a panel. */
        SUBHEADING(14f, 600),
        /** Capitals above a title: "CONTINUE WATCHING · S2 E5". Figtree, not Bricolage. */
        EYEBROW(10f, 700, 0.14f)
    }

    fun display(context: Context, weight: Int = 700): Typeface =
        ResourcesCompat.getFont(context, when {
            weight >= 800 -> R.font.bricolage_extrabold
            weight >= 700 -> R.font.bricolage_bold
            else -> R.font.bricolage_semibold
        }) ?: Typeface.DEFAULT_BOLD

    fun text(context: Context, weight: Int = 400): Typeface =
        ResourcesCompat.getFont(context, when {
            weight >= 700 -> R.font.figtree_bold
            weight >= 600 -> R.font.figtree_semibold
            weight >= 500 -> R.font.figtree_medium
            else -> R.font.figtree_regular
        }) ?: Typeface.DEFAULT

    fun apply(view: TextView, role: Role, sizeSp: Float = role.sizeSp) {
        view.typeface = if (role == Role.EYEBROW) text(view.context, role.weight) else display(view.context, role.weight)
        view.textSize = sizeSp
        view.letterSpacing = role.tracking
        view.includeFontPadding = false
    }
}

fun TextView.typeRole(role: Type.Role, sizeSp: Float = role.sizeSp) = Type.apply(this, role, sizeSp)

/** Figtree at an explicit weight, for the few places 400 and 700 are not enough. */
fun TextView.textWeight(weight: Int) { typeface = Type.text(context, weight) }
