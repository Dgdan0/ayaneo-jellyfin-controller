package com.pocketds.hub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * A rounded panel holding one setting or a few related ones; the dashboards'
 * cards too (Activity, Server monitor).
 *
 * On the Glass page it is the prototype's card (`.scard2`, `.dcard`): a panel
 * of the page's glass with 15dp corners, its heading in bold Figtree. [attention]
 * edges it in amber, as the card saying what needs attention is.
 */
class SettingsCard(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    private val glass = Theme.onGlass(colors)
    private val panel: com.pocketds.hub.ui.glass.GlassPanelDrawable?

    init {
        orientation = VERTICAL
        if (glass) {
            panel = com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, GLASS_CORNER_DP))
            setPadding(Styler.dpInt(context, 13f), Styler.dpInt(context, 11f), Styler.dpInt(context, 13f), Styler.dpInt(context, 12f))
        } else {
            panel = null
            val pad = Styler.dpInt(context, 14f)
            setPadding(pad, pad, pad, pad)
            background = ThemeGradientDrawable().apply {
                cornerRadius = Styler.dp(context, 16f)
                setColor(this@SettingsCard.colors.cardSurface)
            }
        }
        clipChildren = false
        clipToPadding = false
    }

    /**
     * The card that says what needs attention: an amber edge and an amber
     * heading on Glass (the prototype's `.dcard.warn`). Classic draws its own.
     */
    fun attention(on: Boolean) {
        panel?.edge(if (on) ATTENTION_EDGE else null, Styler.dp(context, 1.5f))
        titleView?.setTextColor(if (on && glass) ATTENTION_INK else colors.primaryText)
    }

    /** The heading, the quiet text at its end, and the row holding both: set by [title]. */
    var titleView: TextView? = null; private set
    var trailingView: TextView? = null; private set
    private var header: LinearLayout? = null

    fun title(text: String, trailing: String = ""): SettingsCard {
        header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            titleView = TextView(context).apply {
                this.text = text; textSize = if (glass) 13.5f else 14f; textWeight(if (glass) 800 else 700); setTextColor(colors.primaryText)
            }
            addView(titleView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            trailingView = TextView(context).apply { textSize = 11f; setTextColor(colors.mutedText) }
            addView(trailingView)
        }
        addView(header)
        trailing(trailing)
        return this
    }

    /** Changes the text at the end of the heading; blank hides it. */
    fun trailing(text: String, color: Int = if (glass) GLASS_QUIET else colors.mutedText) {
        trailingView?.apply { this.text = text; setTextColor(color); visibility = if (text.isBlank()) GONE else VISIBLE }
    }

    /** A control at the end of the heading, such as "See all". */
    fun headerAction(view: View): SettingsCard {
        header?.apply { gravity = Gravity.CENTER_VERTICAL; addView(view) }
        return this
    }

    fun hint(text: String): SettingsCard {
        addView(TextView(context).apply {
            this.text = text; textSize = 11.5f; setTextColor(if (glass) GLASS_QUIET else colors.mutedText); setLineSpacing(0f, 1.15f)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Styler.dpInt(context, 3f) })
        return this
    }

    /** [fill] spans the card's width, for rows that end at its right edge. */
    fun body(view: View, topDp: Float = 10f, fill: Boolean = false): SettingsCard {
        addView(view, LayoutParams(if (fill) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Styler.dpInt(context, topDp)
        })
        return this
    }

    companion object {
        /** The prototype's Pocket card corner. */
        const val GLASS_CORNER_DP = 15f
        /** Quiet words on a Glass card: white at 64%. */
        const val GLASS_QUIET = 0xA3FFFFFF.toInt()
        /** The attention card's edge, amber at 55%, and its heading. */
        const val ATTENTION_EDGE = 0x8CF2B544.toInt()
        const val ATTENTION_INK = 0xFFF5C75A.toInt()
    }
}

/**
 * A setting that is on or off: its name, a line saying what each state means,
 * and a switch whose knob slides over. The whole row is the control, so A or a
 * tap anywhere on it flips it.
 *
 * On Glass the row sits straight on its card, as the prototype's do, ringed
 * while focused, and the switch is its 38 x 23dp one.
 */
class SwitchRowView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean,
    title: String,
    detail: String
) : LinearLayout(context) {
    var onChange: ((Boolean) -> Unit)? = null
    var checked = false
        private set
    private val toggle = Switch(context)
    private val label = title
    private val glass = Theme.onGlass(colors)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (glass) {
            setPadding(Styler.dpInt(context, 8f), Styler.dpInt(context, 7f), Styler.dpInt(context, 8f), Styler.dpInt(context, 7f))
            foreground = Styler.focusOutline(context, colors, 10f, 2f)
        } else {
            val pad = Styler.dpInt(context, 14f)
            setPadding(pad, Styler.dpInt(context, 12f), pad, Styler.dpInt(context, 12f))
            background = Styler.cardBackground(context, colors, cornerDp = 16f, focusStrokeDp = 2f)
        }
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply { text = title; textSize = if (glass) 13f else 14f; textWeight(700); setTextColor(colors.primaryText) })
            if (detail.isNotBlank()) addView(TextView(context).apply {
                text = detail; textSize = 11.5f; setTextColor(if (glass) SettingsCard.GLASS_QUIET else colors.mutedText); setLineSpacing(0f, 1.12f)
            }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Styler.dpInt(context, 2f) })
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(toggle, if (glass) LayoutParams(Styler.dpInt(context, 38f), Styler.dpInt(context, 23f)).apply { marginStart = Styler.dpInt(context, 12f) }
            else LayoutParams(Styler.dpInt(context, 40f), Styler.dpInt(context, 24f)).apply { marginStart = Styler.dpInt(context, 12f) })
        FocusDecorator.attach(this, ringVisible, scale = false)
        activateOnTap { set(!checked, animate = true); onChange?.invoke(checked) }
        describe()
    }

    fun set(on: Boolean, animate: Boolean = false) {
        checked = on
        toggle.move(on, animate)
        isSelected = on
        describe()
    }

    private fun describe() { contentDescription = "$label, ${if (checked) "on" else "off"}" }

    private inner class Switch(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var position = 0f
        private var animator: ValueAnimator? = null

        fun move(on: Boolean, animate: Boolean) {
            animator?.cancel()
            val target = if (on) 1f else 0f
            if (!animate || !ValueAnimator.areAnimatorsEnabled()) { position = target; invalidate(); return }
            animator = ValueAnimator.ofFloat(position, target).apply {
                duration = 250L
                interpolator = PathInterpolator(0.3f, 1.35f, 0.5f, 1f)
                addUpdateListener { position = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val on = position.coerceIn(0f, 1f)
            // Off is white at 22% on Glass, as the prototype's switch is.
            paint.color = ColorUtils.blendARGB(ColorUtils.setAlphaComponent(colors.primaryText, if (glass) 0x38 else 0x30), colors.accent, on)
            rect.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            val knob = h / 2 - Styler.dp(context, 3f)
            val travel = width - h
            paint.color = Color.WHITE
            canvas.drawCircle(h / 2 + travel * position, h / 2, knob, paint)
        }
    }
}

/**
 * Colour choices as round swatches. The chosen one is a little larger with a
 * ring of the page colour and one of its own, the focused one has the white
 * focus ring.
 *
 * On Glass they are the prototype's 24dp swatches: the chosen one ringed in
 * white with a dark gap, the focused one in a heavier white ring.
 */
class SwatchRowView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {
    data class Swatch(val id: String, val name: String, val color: Int)

    var onPick: ((Swatch) -> Unit)? = null
    var onFocused: ((Swatch) -> Unit)? = null
    private val views = linkedMapOf<String, FrameLayout>()
    private var swatches = emptyList<Swatch>()
    private var chosen = ""
    private val glass = Theme.onGlass(colors)

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    fun bind(swatches: List<Swatch>, selected: String) {
        this.swatches = swatches
        removeAllViews()
        views.clear()
        swatches.forEach { swatch ->
            val view = FrameLayout(context).apply {
                contentDescription = swatch.name
                tag = swatch.id
                Styler.makeFocusable(this)
                activateOnTap { select(swatch.id); onPick?.invoke(swatch) }
                setOnFocusChangeListener { _, focused ->
                    paint(this, swatch, focused)
                    if (focused) onFocused?.invoke(swatch)
                }
            }
            views[swatch.id] = view
            val size = Styler.dpInt(context, if (glass) GLASS_SIZE_DP else SIZE_DP)
            addView(view, LayoutParams(size, size).apply {
                marginEnd = Styler.dpInt(context, if (glass) 2f else 6f)
            })
        }
        select(selected)
    }

    fun select(id: String) {
        chosen = id
        swatches.forEach { swatch -> views[swatch.id]?.let { paint(it, swatch, it.isFocused) } }
    }

    fun focus(id: String = chosen): Boolean = views[id]?.requestFocus() == true

    private fun paint(view: View, swatch: Swatch, focused: Boolean) {
        val on = swatch.id == chosen
        if (glass) return paintGlass(view, swatch, on, focused && ringVisible())
        val ring = Styler.dpInt(context, 2f)
        view.background = android.graphics.drawable.LayerDrawable(arrayOf(
            ThemeGradientDrawable.oval(Color.TRANSPARENT, if (focused && ringVisible() || on) ring else 0,
                if (focused && ringVisible()) colors.focusRing else swatch.color),
            android.graphics.drawable.InsetDrawable(ThemeGradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatch.color)
            }, if (on || focused) ring * 2 else ring * 3)
        ))
        view.contentDescription = swatch.name + if (on) ", chosen" else ""
        view.isSelected = on
    }

    /** The colour 24dp across in the middle; round it a dark gap and a white ring when chosen or focused. */
    private fun paintGlass(view: View, swatch: Swatch, on: Boolean, focused: Boolean) {
        val inset = Styler.dpInt(context, (GLASS_SIZE_DP - GLASS_SWATCH_DP) / 2f)
        val layers = mutableListOf<android.graphics.drawable.Drawable>()
        if (on || focused) {
            layers += ThemeGradientDrawable.oval(Color.TRANSPARENT, Styler.dpInt(context, if (focused) 3f else 2f), Color.WHITE)
            layers += android.graphics.drawable.InsetDrawable(ThemeGradientDrawable.oval(com.pocketds.hub.ui.glass.GlassColors.INK),
                Styler.dpInt(context, if (focused) 3f else 2f))
        }
        layers += android.graphics.drawable.InsetDrawable(ThemeGradientDrawable.oval(swatch.color), inset)
        view.background = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
        view.contentDescription = swatch.name + if (on) ", chosen" else ""
        view.isSelected = on
    }

    private companion object {
        const val SIZE_DP = 40f
        /** Glass: a 24dp swatch in a 34dp target, room for the ring and its gap. */
        const val GLASS_SWATCH_DP = 24f
        const val GLASS_SIZE_DP = 34f
    }
}
