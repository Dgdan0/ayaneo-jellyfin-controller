package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction

/**
 * A menu, and a confirmation, in the layout the screen already owns.
 *
 * Deliberately not an `AlertDialog`. A dialog opens a second window with its own
 * focus rules, which leaves the hint bar behind it describing a screen the user
 * can no longer reach, and puts the gamepad pipeline -- which is per-window
 * state -- on the wrong side of the boundary.
 *
 * Focus is moved by index rather than by `FocusFinder`. That is not laziness:
 * the tab bar and the list behind this overlay are still in the view hierarchy,
 * and directional search would happily walk out of the menu and leave a
 * destructive confirmation on screen with the selection somewhere else entirely.
 * An index cannot escape.
 */
class ChoiceOverlay(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : FrameLayout(context) {

    /**
     * @param danger renders in the failure colour and, by convention on the
     *   calling side, is never the entry that starts focused.
     */
    data class Choice(
        val id: String,
        val label: String,
        val detail: String = "",
        val danger: Boolean = false
    )

    private val card: LinearLayout
    private val titleView: TextView
    private val subtitleView: TextView
    private val list: LinearLayout
    private val scroller: android.widget.ScrollView

    private var choices: List<Choice> = emptyList()
    private var index = 0
    private var onPick: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        // The scrim is clickable so a stray trackpad tap lands here and not on
        // the list underneath, which would move focus out from behind the menu.
        setBackgroundColor(SCRIM)
        isClickable = true
        isFocusable = false
        visibility = View.GONE
        setOnClickListener { cancel() }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 14f)
            val p = Styler.dpInt(context, 16f)
            setPadding(p, p, p, p)
            isClickable = true
            layoutParams = LayoutParams(
                Styler.dpInt(context, 420f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            // The card must never be taller than the space it sits in. WRAP on
            // its own let a five-action menu run off the bottom of the screen.
            //
            // The cap is only ever *applied* here; show() puts the height back to
            // WRAP first. Without that reset a two-line confirmation inherits the
            // height of whatever taller menu opened before it and floats in the
            // middle of an empty card.
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val available = this@ChoiceOverlay.height - Styler.dpInt(context, 32f)
                if (available > 0 && bottom - top > available) {
                    view.layoutParams = view.layoutParams.also { it.height = available }
                    view.requestLayout()
                }
            }
        }
        addView(card)

        titleView = TextView(context).apply {
            textSize = 16f
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(titleView)

        subtitleView = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 4f), 0, Styler.dpInt(context, 10f))
        }
        card.addView(subtitleView)

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // A stuck item offers five actions and the card ran off the bottom of
        // the screen. Scrolling is safe *here* specifically because focus inside
        // this overlay is moved by index rather than by directional search --
        // a ScrollView's own focus handling is what caused the cast-row bug on
        // the detail screen, and none of it is in play.
        scroller = android.widget.ScrollView(context).apply {
            isFocusable = false
            isFillViewport = false
            addView(list)
        }
        card.addView(scroller)
    }

    fun show(
        title: String,
        subtitle: String,
        choices: List<Choice>,
        startIndex: Int = 0,
        onCancel: () -> Unit = {},
        onPick: (String) -> Unit
    ) {
        this.choices = choices
        this.onPick = onPick
        this.onCancel = onCancel
        titleView.text = title
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE

        list.removeAllViews()
        choices.forEachIndexed { position, choice ->
            list.addView(buildRow(choice, position))
        }
        card.layoutParams = card.layoutParams.also {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scroller.scrollTo(0, 0)
        visibility = View.VISIBLE
        bringToFront()
        index = startIndex.coerceIn(0, (choices.size - 1).coerceAtLeast(0))
        applySelection()
    }

    fun dismiss() {
        visibility = View.GONE
        choices = emptyList()
        onPick = null
        onCancel = null
        list.removeAllViews()
    }

    private fun cancel() {
        val callback = onCancel
        dismiss()
        callback?.invoke()
    }

    /**
     * @return true if the overlay consumed it. Everything is consumed while
     *   open -- an unhandled press must not reach the screen behind a
     *   confirmation.
     */
    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        when (action) {
            is PadAction.Step -> when (action.direction) {
                Direction.UP -> move(-1)
                Direction.DOWN -> move(1)
                // Left and right do nothing rather than falling through: on this
                // list they have no meaning, and letting them out would move the
                // selection behind the menu.
                else -> Unit
            }
            PadAction.Activate -> pick()
            PadAction.Back -> cancel()
            else -> Unit
        }
        return true
    }

    private fun move(delta: Int) {
        if (choices.isEmpty()) return
        // Clamped, not wrapped. Wrapping a two-item confirmation puts "Delete"
        // under the cursor when the user pressed up to get away from it.
        index = (index + delta).coerceIn(0, choices.size - 1)
        applySelection()
    }

    private fun pick() {
        val choice = choices.getOrNull(index) ?: return
        val callback = onPick
        dismiss()
        callback?.invoke(choice.id)
    }

    /**
     * Paints the selection directly rather than through a StateListDrawable.
     *
     * These rows are not focusable -- focus stays wherever it was behind the
     * overlay -- so there is no `state_focused` to select, and setting the state
     * by hand does not survive: a View calls `background.setState(...)` with its
     * *own* drawable state on every change, silently replacing it. Two concrete
     * drawables is both simpler and the only version that actually draws a ring.
     */
    private fun applySelection() {
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            val selected = i == index
            row.background = if (selected && ringVisible()) selectedFace() else plainFace()
            row.alpha = if (selected) 1f else 0.62f
            if (selected) {
                // Keep the cursor on screen when the menu is taller than the card.
                scroller.post {
                    scroller.smoothScrollTo(
                        0,
                        (row.top - (scroller.height - row.height) / 2).coerceAtLeast(0)
                    )
                }
            }
        }
    }

    // Qualified, because GradientDrawable has its own `colors` property and an
    // unqualified reference inside apply{} silently resolves to that one.
    private fun plainFace() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(this@ChoiceOverlay.colors.stripBackground)
    }

    private fun selectedFace() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(this@ChoiceOverlay.colors.focusFill)
        setStroke(Styler.dpInt(context, 3f), this@ChoiceOverlay.colors.focusRing)
    }

    private fun buildRow(choice: Choice, position: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val h = Styler.dpInt(context, 12f)
            val v = Styler.dpInt(context, 9f)
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Styler.dpInt(context, 6f) }
            // Tappable as well as navigable: one definition of each action,
            // reachable by both input paths.
            setOnClickListener {
                index = position
                pick()
            }
        }
        row.addView(TextView(context).apply {
            text = choice.label
            textSize = 14f
            setTextColor(if (choice.danger) colors.dangerText else colors.primaryText)
        })
        if (choice.detail.isNotEmpty()) {
            row.addView(TextView(context).apply {
                text = choice.detail
                textSize = 11f
                setTextColor(colors.mutedText)
            })
        }
        return row
    }

    private companion object {
        /** Dark enough to read the card against a bright poster grid. */
        val SCRIM = Color.argb(190, 0, 0, 0)
    }
}
