package com.pocketds.hub.nav

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.pocketds.hub.R
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

data class SectionRailItem(val title: String, @DrawableRes val icon: Int)

/**
 * Persistent top-level navigation for the Pocket DS landscape display.
 *
 * The collapsed rail spends horizontal pixels to recover the entire height of
 * the former tab row. Labels are still one tap or Start away, and L1/R1 keeps
 * section switching independent from this view. Rail entries deliberately do
 * not accept focus: RecyclerView item detachment must never strand gamepad
 * focus in app chrome.
 */
class SectionRailView(context: Context, private val colors: PocketColors) : LinearLayout(context) {

    var onSelect: ((Int) -> Unit)? = null
    var onExpandedChange: ((Boolean) -> Unit)? = null

    private val rows = mutableListOf<LinearLayout>()
    private val icons = mutableListOf<ImageView>()
    private val labels = mutableListOf<TextView>()
    private val badges = mutableListOf<TextView>()
    private var current = 0
    private var animator: ValueAnimator? = null
    var isExpanded: Boolean = false
        private set

    val preferredWidth: Int get() = dp(if (isExpanded) EXPANDED_DP else COLLAPSED_DP)

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        setPadding(dp(8), dp(5), dp(8), dp(5))
        setBackgroundColor(colors.stripBackground)
    }

    fun setSections(items: List<SectionRailItem>) {
        removeAllViews()
        rows.clear()
        icons.clear()
        labels.clear()
        badges.clear()
        addView(buildHeader(), LayoutParams(MATCH, dp(42)).apply { bottomMargin = dp(3) })
        items.forEachIndexed { index, item ->
            val row = buildRow(item, index)
            rows += row
            addView(row, LayoutParams(MATCH, dp(40)).apply { bottomMargin = dp(2) })
        }
        setCurrent(current.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        applyExpandedVisuals()
    }

    fun setCurrent(index: Int) {
        current = index
        rows.forEachIndexed { position, row ->
            val selected = position == index
            row.background = Styler.chipBackground(context, colors, selected)
            icons.getOrNull(position)?.imageTintList = ColorStateList.valueOf(
                if (selected) colors.accentText else colors.mutedText
            )
            labels.getOrNull(position)?.setTextColor(
                if (selected) colors.accentText else colors.mutedText
            )
        }
    }

    /** Shows unread items on a section without turning the rail itself into an inbox. */
    fun setBadge(index: Int, count: Int) {
        val badge = badges.getOrNull(index) ?: return
        val safe = count.coerceAtLeast(0)
        badge.text = when {
            safe > 99 -> "99+"
            safe > 0 -> safe.toString()
            else -> ""
        }
        badge.visibility = if (safe > 0) View.VISIBLE else View.GONE
        rows.getOrNull(index)?.contentDescription = buildString {
            append(labels.getOrNull(index)?.text ?: "Section")
            if (safe > 0) append(", ").append(safe).append(" unread notifications")
        }
    }

    fun toggle(animate: Boolean = true) = setExpanded(!isExpanded, animate)

    fun setExpanded(expanded: Boolean, animate: Boolean = false) {
        if (isExpanded == expanded && layoutParams?.width == preferredWidth) return
        animator?.cancel()
        isExpanded = expanded
        if (expanded) applyExpandedVisuals()
        val target = preferredWidth
        val params = layoutParams
        if (!animate || params == null || width <= 0) {
            params?.let {
                it.width = target
                layoutParams = it
            }
            applyExpandedVisuals()
            onExpandedChange?.invoke(expanded)
            return
        }
        animator = ValueAnimator.ofInt(width, target).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val lp = layoutParams
                lp.width = it.animatedValue as Int
                layoutParams = lp
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyExpandedVisuals()
                    onExpandedChange?.invoke(expanded)
                }
            })
            start()
        }
    }

    private fun buildHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = false
        background = Styler.chipBackground(context, colors)
        contentDescription = if (isExpanded) "Collapse navigation" else "Expand navigation"
        setPadding(dp(10), 0, dp(10), 0)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = rounded(0xFF0FADA0.toInt())
            setPadding(dp(2), dp(2), dp(2), dp(2))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(30), dp(30)))
        val title = TextView(context).apply {
            text = "Ayaneo Hub"
            textSize = 13f
            setTextColor(colors.primaryText)
            setPadding(dp(10), 0, 0, 0)
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setTag(TAG_HEADER_LABEL, true)
        }
        addView(title, LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = "‹"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(colors.mutedText)
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            setTag(TAG_HEADER_ARROW, true)
        }, LayoutParams(dp(22), MATCH))
        setOnClickListener { toggle() }
    }

    private fun buildRow(item: SectionRailItem, index: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = false
            // 68dp collapsed width - 16dp rail padding leaves 52dp. Ten on
            // either side leaves the full icon frame visible, including
            // its unread badge.
            setPadding(dp(10), 0, dp(10), 0)
            contentDescription = item.title
            addView(FrameLayout(context).apply {
                val icon = ImageView(context).apply {
                    setImageResource(item.icon)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    imageTintList = ColorStateList.valueOf(colors.mutedText)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                icons += icon
                addView(icon, FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER))
                val badge = TextView(context).apply {
                    textSize = 7f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFFFFFFF.toInt())
                    background = rounded(colors.badgeFailed)
                    visibility = View.GONE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                badges += badge
                addView(
                    badge,
                    FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(2)
                        rightMargin = dp(2)
                    }
                )
            }, LayoutParams(dp(30), dp(30)))
            val label = TextView(context).apply {
                text = item.title
                textSize = 14f
                setTextColor(colors.mutedText)
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
                visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
            labels += label
            addView(label, LayoutParams(0, WRAP, 1f))
            setOnClickListener { onSelect?.invoke(index) }
        }

    private fun applyExpandedVisuals() {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? ViewGroup ?: continue
            for (j in 0 until child.childCount) {
                val nested = child.getChildAt(j)
                if (nested is TextView && (nested.getTag(TAG_HEADER_LABEL) == true ||
                        nested.getTag(TAG_HEADER_ARROW) == true || child !== getChildAt(0))) {
                    nested.visibility = if (isExpanded) View.VISIBLE else View.GONE
                }
            }
            if (i == 0) {
                child.contentDescription = if (isExpanded) "Collapse navigation" else "Expand navigation"
            }
        }
        requestLayout()
    }

    private fun rounded(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(color)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLLAPSED_DP = 68
        const val EXPANDED_DP = 190
        const val TAG_HEADER_LABEL = -0x7fffff01
        const val TAG_HEADER_ARROW = -0x7fffff02
    }
}
