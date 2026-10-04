package com.pocketds.hub.ui.glass

import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.EditText
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable

/**
 * The prototype's search field (`.search`): a pill of the page's glass with
 * the search mark before the words, drawn [pillDp] tall inside a touch target
 * as tall as the field is laid out. Focus rings it in white, as every glass
 * control is. Discover's search and the Library's.
 */
object GlassSearchField {
    /** The hint: white at 60%. */
    const val HINT = 0x99FFFFFF.toInt()
    /** The search mark: white at 80%. */
    const val ICON = 0xCCFFFFFF.toInt()

    fun style(field: EditText, colors: PocketColors, pillDp: Float, targetDp: Float = pillDp) {
        val context = field.context
        val inset = Styler.dpInt(context, ((targetDp - pillDp) / 2f).coerceAtLeast(0f))
        val corner = Styler.dp(context, 999f)
        val panel = GlassPanelDrawable(GlassColors.panel(GlassPage.palette(context)), corner)
        val ring = ThemeGradientDrawable.rounded(corner, Color.TRANSPARENT, Styler.dpInt(context, 2f), colors.focusRing)
        field.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), InsetDrawable(LayerDrawable(arrayOf(panel, ring)), 0, inset, 0, inset))
            addState(intArrayOf(), InsetDrawable(panel, 0, inset, 0, inset))
        }
        GlassPage.follow(field) { page -> panel.retint(GlassColors.panel(page)) }
        field.textSize = 12.5f
        field.setTextColor(Color.WHITE)
        field.setHintTextColor(HINT)
        field.setCompoundDrawablesRelative(AppIconDrawable(AppIcon.SEARCH, ICON).apply {
            val size = Styler.dpInt(context, 15f)
            setBounds(0, 0, size, size)
        }, null, null, null)
        field.compoundDrawablePadding = Styler.dpInt(context, 9f)
        field.setSingleLine()
        val h = Styler.dpInt(context, 12f)
        field.setPadding(h, inset, h, inset)
        field.minimumHeight = Styler.dpInt(context, targetDp)
    }
}
