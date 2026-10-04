package com.pocketds.hub.ui

import android.content.res.ColorStateList
import android.graphics.drawable.*
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/** Retained Views keep data, scroll and focus; only exact accent tokens are replaced.
 * Bitmap artwork, semantic status colors and untinted service logos are never modified. */
object AccentRebinder {
    const val PREVIEW_TAG = -0x7fffffc5
    private const val BOUND_TAG = -0x7fffffc4
    private const val TRACKED_TAG = -0x7fffffc3
    /** Cached RecyclerView children are detached during a palette change. Rebind them on return. */
    fun track(root:View,colors:PocketColors) {
        if(root.getTag(PREVIEW_TAG)==true)return
        if(root.getTag(BOUND_TAG)==null)root.setTag(BOUND_TAG,colors.copy())
        if(root is androidx.recyclerview.widget.RecyclerView && root.getTag(TRACKED_TAG)!=true) {
            root.setTag(TRACKED_TAG,true)
            root.addOnChildAttachStateChangeListener(object:androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view:View) {
                    val old=view.getTag(BOUND_TAG) as? PocketColors
                    if(old!=null && old!=colors)apply(view,old,colors)
                    track(view,colors)
                }
                override fun onChildViewDetachedFromWindow(view:View)=Unit
            })
        }
        if(root is ViewGroup)(0 until root.childCount).forEach{track(root.getChildAt(it),colors)}
    }
    fun apply(root: View, old: PocketColors, next: PocketColors) {
        val replacements = (if (old.background == next.background) mapOf(old.accent to next.accent, old.focusFill to next.focusFill, old.focusRing to next.focusRing, old.accentText to next.accentText)
            else mapOf(old.background to next.background, old.cardSurface to next.cardSurface, old.cardSurfacePressed to next.cardSurfacePressed, old.primaryText to next.primaryText, old.mutedText to next.mutedText, old.accent to next.accent, old.accentText to next.accentText, old.inverseText to next.inverseText, old.stripBackground to next.stripBackground, old.focusRing to next.focusRing, old.focusFill to next.focusFill, old.posterPlaceholder to next.posterPlaceholder, old.badgeAvailable to next.badgeAvailable, old.badgePartial to next.badgePartial, old.badgePending to next.badgePending, old.badgeFailed to next.badgeFailed, old.warningStrip to next.warningStrip, old.warningStripText to next.warningStripText, old.dangerText to next.dangerText, old.unreadSurface to next.unreadSurface)).filter { it.key != it.value }
        val fills=if(old.background!=next.background) replacements + mapOf(old.cardSurface to next.cardSurface,old.background to next.background) else replacements
        fun color(value: Int) = replacements[value] ?: value
        fun fill(value: Int) = fills[value] ?: value
        val states = arrayOf(intArrayOf(android.R.attr.state_selected, android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_selected), intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        fun tint(value: ColorStateList?): ColorStateList? = value?.let {
            ColorStateList(states, states.map { state -> color(it.getColorForState(state,it.defaultColor)) }.toIntArray())
        }
        fun drawable(value: Drawable?) {
            when(value) {
                is AppIconDrawable -> value.recolor(replacements)
                is MediaActionIconDrawable -> value.recolor(replacements)
                is ColorDrawable -> value.color=fill(value.color)
                is GradientDrawable -> {
                    value.color?.let { value.color=ColorStateList(states,states.map{state->fill(it.getColorForState(state,it.defaultColor))}.toIntArray()) }
                    value.colors?.let { value.colors=it.map(::fill).toIntArray() }
                    if(value is ThemeGradientDrawable) value.rebindStroke(::tint)
                }
                is InsetDrawable -> drawable(value.drawable)
                is LayerDrawable -> (0 until value.numberOfLayers).forEach { drawable(value.getDrawable(it)) }
                is StateListDrawable -> {
                    if (android.os.Build.VERSION.SDK_INT >= 29) (0 until value.stateCount).forEach { drawable(value.getStateDrawable(it)) }
                    else { val previous=value.state; states.forEach { value.state=it; drawable(value.current) }; value.state=previous }
                }
            }
        }
        fun visit(view: View) {
            if (view.getTag(PREVIEW_TAG) == true) return
            view.setTag(BOUND_TAG,next.copy())
            drawable(view.background); drawable(view.foreground)
            view.backgroundTintList=tint(view.backgroundTintList)
            if(view is TextView) {
                view.setTextColor(tint(view.textColors)); view.compoundDrawables.forEach(::drawable)
                if(view is CenteredIconTextView) view.recolorIcon(replacements)
            }
            if(view is ImageView) { ServiceLogo.refresh(view); view.imageTintList=tint(view.imageTintList); if(view.drawable is AppIconDrawable) drawable(view.drawable) }
            if(view is ProgressBar) { view.progressTintList=tint(view.progressTintList); view.indeterminateTintList=tint(view.indeterminateTintList) }
            if(view is SeekBar) view.thumbTintList=tint(view.thumbTintList)
            if(view is ViewGroup) (0 until view.childCount).forEach { visit(view.getChildAt(it)) }
            view.invalidate()
        }
        visit(root)
    }
}
