package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow

/**
 * The request dialog: quality profile, root folder, seasons.
 *
 * In-layout rather than an `AlertDialog`, for the reason recorded in A1 -- a
 * dialog opens a second window with its own focus rules and leaves the hint bar
 * behind it describing a screen the user can no longer reach.
 *
 * All the behaviour lives in [FormModel], which is pure and unit-tested; this
 * class only draws it. That split is why "does holding down wrap onto the
 * Request button" is a test rather than something discovered by accident on the
 * device.
 */
class FormOverlay(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : FrameLayout(context) {

    private val card: LinearLayout
    private val titleView: TextView
    private val subtitleView: TextView
    private val scroller: ScrollView
    private val list: LinearLayout

    private var model: FormModel? = null
    private var onSubmit: ((String, FormModel) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var onChanged: ((FormModel) -> Unit)? = null

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(SCRIM)
        isClickable = true
        isFocusable = false
        visibility = View.GONE
        setOnClickListener { cancel() }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The centred panel's look: the page colour, a hairline, rows on a raised card.
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 16f), colors.background,
                Styler.dpInt(context, 1f), androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x1A))
            val p = Styler.dpInt(context, 18f)
            setPadding(p, Styler.dpInt(context, 16f), p, Styler.dpInt(context, 14f))
            isClickable = true
            layoutParams = LayoutParams(
                Styler.dpInt(context, 460f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
                val available = this@FormOverlay.height - Styler.dpInt(context, 24f)
                if (available > 0 && bottom - top > available) {
                    view.layoutParams = view.layoutParams.also { it.height = available }
                    view.requestLayout()
                }
            }
        }
        addView(card)

        titleView = TextView(context).apply {
            typeRole(Type.Role.HEADING, 18f)
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(titleView)

        subtitleView = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 4f), 0, Styler.dpInt(context, 12f))
        }
        card.addView(subtitleView)

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller = FocusScrollView(context).apply {
            addView(list)
        }
        card.addView(scroller)
    }

    fun show(
        title: String,
        subtitle: String,
        model: FormModel,
        onCancel: () -> Unit = {},
        onChanged: (FormModel) -> Unit = {},
        onSubmit: (String, FormModel) -> Unit
    ) {
        this.model = model
        this.onSubmit = onSubmit
        this.onCancel = onCancel
        this.onChanged = onChanged
        titleView.text = title
        subtitleView.text = subtitle
        subtitleView.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        card.layoutParams = card.layoutParams.also {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        visibility = View.VISIBLE
        bringToFront()
        rebuild()
    }

    fun dismiss() {
        visibility = View.GONE
        model = null
        onSubmit = null
        onCancel = null
        onChanged = null
        list.removeAllViews()
    }

    /** Redraw after the caller changed the rows, e.g. hiding the season list. */
    fun refresh() {
        if (isOpen) rebuild()
    }

    fun setSubtitle(text: String) {
        subtitleView.text = text
        subtitleView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun cancel() {
        val callback = onCancel
        dismiss()
        callback?.invoke()
    }

    /** @return true if consumed. Everything is, while this is open. */
    fun onPad(action: PadAction): Boolean {
        val model = model ?: return false
        if (!isOpen) return false
        when (action) {
            is PadAction.Step -> when (action.direction) {
                Direction.UP -> { model.move(-1); rebuild() }
                Direction.DOWN -> { model.move(1); rebuild() }
                Direction.LEFT -> if (model.adjust(-1)) changed()
                Direction.RIGHT -> if (model.adjust(1)) changed()
            }
            PadAction.Activate -> {
                val actionId = model.activate()
                if (actionId == null) {
                    changed()
                } else {
                    val submit = onSubmit
                    val current = model
                    submit?.invoke(actionId, current)
                }
            }
            PadAction.Back -> cancel()
            else -> Unit
        }
        return true
    }

    private fun changed() {
        val model = model ?: return
        onChanged?.invoke(model)
        rebuild()
    }

    /**
     * The rows that set something share one raised card; an action ends it and
     * is a pill of its own. The model's index still names the row, so the
     * views are kept in order beside the cards that hold them.
     */
    private fun rebuild() {
        val model = model ?: return
        list.removeAllViews()
        val views = mutableListOf<View>()
        var group: LinearLayout? = null
        model.rows().forEachIndexed { position, row ->
            val view = buildRow(row, position, position == model.index)
            views += view
            if (row is FormRow.Action) {
                group = null
                list.addView(view)
            } else {
                val card = group ?: LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = Styler.dpInt(context, 3f)
                    setPadding(pad, pad, pad, pad)
                    background = ThemeGradientDrawable.rounded(Styler.dp(context, 14f), colors.cardSurface)
                }.also {
                    group = it
                    list.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply {
                        bottomMargin = Styler.dpInt(context, 12f)
                    })
                }
                card.addView(view)
            }
        }
        val selected = views.getOrNull(model.index) ?: return
        scroller.post {
            val rect = android.graphics.Rect()
            selected.getDrawingRect(rect)
            list.offsetDescendantRectToMyCoords(selected, rect)
            scroller.smoothScrollTo(0, (rect.top - (scroller.height - rect.height()) / 2).coerceAtLeast(0))
        }
    }

    private fun buildRow(row: FormRow, position: Int, selected: Boolean): View {
        val focused = selected && ringVisible()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Styler.dpInt(context, 48f)
            val h = Styler.dpInt(context, 12f)
            val v = Styler.dpInt(context, 8f)
            setPadding(h, v, h, v)
            background = when {
                row is FormRow.Action -> actionFace(row.danger, focused)
                focused -> selectedFace()
                else -> null
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (row is FormRow.Action) topMargin = Styler.dpInt(context, 2f) }
            setOnClickListener {
                val current = this@FormOverlay.model ?: return@setOnClickListener
                current.focus(position)
                if (row is FormRow.Action) {
                    onSubmit?.invoke(row.id, current)
                } else {
                    current.adjust(1)
                    changed()
                }
            }
        }

        when (row) {
            is FormRow.Action -> {
                container.addView(TextView(context).apply {
                    text = row.label
                    textSize = 14f
                    textWeight(700)
                    gravity = Gravity.CENTER
                    setTextColor(if (row.danger) colors.dangerText else colors.accentText)
                }, wide())
            }
            is FormRow.Toggle -> {
                // A box rather than a bare tick: an empty box reads as "you may
                // choose this", where a missing tick reads as nothing at all.
                container.addView(android.widget.ImageView(context).apply {
                    val size = Styler.dpInt(context, 22f)
                    background = if (row.checked) ThemeGradientDrawable.rounded(Styler.dp(context, 6f), colors.accent)
                        else ThemeGradientDrawable.rounded(Styler.dp(context, 6f), android.graphics.Color.TRANSPARENT,
                            Styler.dpInt(context, 2f), colors.mutedText)
                    if (row.checked) setImageDrawable(AppIconDrawable(AppIcon.CHECK, colors.accentText))
                    val pad = Styler.dpInt(context, 4f)
                    setPadding(pad, pad, pad, pad)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = Styler.dpInt(context, 12f) }
                })
                container.addView(labelBlock(row.label, row.detail), wide())
            }
            is FormRow.Choice -> {
                container.addView(TextView(context).apply {
                    text = row.label
                    textSize = 14f
                    textWeight(600)
                    setTextColor(colors.primaryText)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                container.addView(
                    labelBlock(
                        // The arrows are the affordance. Without them nothing on
                        // screen says this row is a list rather than a label.
                        if (selected) "‹  ${row.value}  ›" else row.value,
                        row.detail,
                        alignEnd = true,
                        quiet = !selected
                    ),
                    LinearLayout.LayoutParams(0, WRAP, 1.4f)
                )
            }
        }
        return container
    }

    private fun labelBlock(text: String, detail: String, alignEnd: Boolean = false, quiet: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(if (quiet) colors.mutedText else colors.primaryText)
                if (alignEnd) gravity = Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            if (detail.isNotEmpty()) {
                addView(TextView(context).apply {
                    this.text = detail
                    textSize = 11f
                    setTextColor(colors.mutedText)
                    if (alignEnd) gravity = Gravity.END
                })
            }
        }

    private fun wide() = LinearLayout.LayoutParams(0, WRAP, 1f)

    private fun selectedFace() = ThemeGradientDrawable.rounded(Styler.dp(context, 11f), colors.focusFill,
        Styler.dpInt(context, 2f), colors.focusRing)

    /** Request: an accent pill; a destructive answer: a quiet one in the danger colour. Focused, both get the ring. */
    private fun actionFace(danger: Boolean, focused: Boolean) = ThemeGradientDrawable.rounded(
        Styler.dp(context, 999f),
        if (danger) androidx.core.graphics.ColorUtils.setAlphaComponent(colors.dangerText, 0x24) else colors.accent,
        if (focused) Styler.dpInt(context, 2f) else 0, colors.focusRing
    )

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val SCRIM = Color.argb(190, 0, 0, 0)
    }
}
