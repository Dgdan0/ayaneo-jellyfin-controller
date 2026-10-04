package com.pocketds.hub.screens.home

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.model.JellyfinUser
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.textWeight

/**
 * "Who is watching?" in Glass: a centred card of one tile per Jellyfin
 * profile, its initial on a colour of its own ([ProfileAvatar]), the profile
 * in use ringed in the accent and marked "Watching now", as the prototype
 * draws it. Left and right move between the tiles, A chooses, B or a tap
 * outside the card cancels.
 *
 * It is the shared centred panel ([SidePanelView]) with tiles for a body, so
 * it opens, closes, hides the page from accessibility and gives focus back
 * like every other panel.
 */
class ProfilePickerView(
    context: Context,
    colors: PocketColors,
    private val ringVisible: () -> Boolean
) : SidePanelView(context, colors, ringVisible, side = false) {

    /** Four tiles across with the prototype's room between them. */
    override val centredWidthDp: Int get() = 600
    override val wrapsHeight: Boolean get() = true

    init {
        centreHeading(30f)
    }

    fun show(users: List<JellyfinUser>, onCancel: () -> Unit, onPick: (JellyfinUser) -> Unit) {
        resetBody()
        open("Who is watching?", "", onCancel)
        val palette = ProfileAvatar.colors(users)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        var current: View? = null
        users.forEach { user ->
            val tile = tile(user, palette[user.id] ?: ProfileAvatar.PALETTE[0]) {
                dismiss()
                onPick(user)
            }
            row.addView(tile, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(11); marginEnd = dp(11) })
            if (user.selected) current = tile
        }
        body.clipChildren = false
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
        body.addView(TextView(context).apply {
            text = "Each person has their own Continue watching, Next up and progress."
            textSize = 13f
            setTextColor(NOTE)
            gravity = Gravity.CENTER
            textAlignment = TEXT_ALIGNMENT_CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        focusBody(current ?: row.getChildAt(0))
    }

    private fun tile(user: JellyfinUser, color: Int, pick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)
        contentDescription = if (user.selected) "${user.name}, watching now" else user.name
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible)
        activateOnTap(pick)
        addView(TextView(context).apply {
            text = ProfileAvatar.initial(user.name)
            typeface = Type.display(context, 800)
            textSize = 32f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(GlassColors.INK)
            // The ring follows the tile's focus: white while focused, the
            // accent round the profile in use otherwise.
            isDuplicateParentStateEnabled = true
            background = avatar(color, user.selected)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(AVATAR_DP + 2 * (RING_DP + GAP_DP)), dp(AVATAR_DP + 2 * (RING_DP + GAP_DP))))
        addView(TextView(context).apply {
            text = user.name
            textSize = 13f
            textWeight(700)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        if (user.selected) addView(TextView(context).apply {
            text = "Watching now"
            textSize = 12f
            textWeight(700)
            setTextColor(this@ProfilePickerView.colors.accent)
            gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
    }

    /**
     * A 76dp rounded square in the profile's colour, with room round it for a
     * 3dp ring. The ring stands 2dp off the square: Dgdan's teal ring on a teal
     * square merged into it on the device.
     */
    private fun avatar(color: Int, current: Boolean): StateListDrawable {
        val ring = dp(RING_DP)
        fun face() = InsetDrawable(ThemeGradientDrawable.rounded(dp(AVATAR_CORNER_DP).toFloat(), color), dp(RING_DP + GAP_DP))
        fun ringed(stroke: Int) = LayerDrawable(arrayOf(
            ThemeGradientDrawable.rounded(dp(AVATAR_CORNER_DP + RING_DP + GAP_DP).toFloat(), Color.TRANSPARENT, ring, stroke), face()))
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ringed(Color.WHITE))
            addState(intArrayOf(), if (current) ringed(this@ProfilePickerView.colors.accent) else face())
        }
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        /** The prototype's Pocket avatar: 76dp, 20dp corners, a 3dp ring. */
        const val AVATAR_DP = 76
        const val AVATAR_CORNER_DP = 20
        const val RING_DP = 3
        const val GAP_DP = 2
        /** The note under the tiles, white at 60%. */
        const val NOTE = 0x99FFFFFF.toInt()
    }
}
