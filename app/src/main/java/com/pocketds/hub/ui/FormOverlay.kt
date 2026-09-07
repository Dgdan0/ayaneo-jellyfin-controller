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
            background = Styler.cardBackground(context, colors, cornerDp = 14f)
            val p = Styler.dpInt(context, 16f)
            setPadding(p, p, p, p)
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
            textSize = 16f
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(titleView)

        subtitleView = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 3f), 0, Styler.dpInt(context, 8f))
        }
        card.addView(subtitleView)

        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroller = ScrollView(context).apply {
            // Not focusable, and it does not need to be: focus in here is an
            // index in FormModel, so none of a ScrollView's own focus handling
            // -- which is what broke the cast row on the detail screen -- is in
            // play.
            isFocusable = false
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

    private fun rebuild() {
        val model = model ?: return
        list.removeAllViews()
        model.rows().forEachIndexed { position, row ->
            list.addView(buildRow(row, position, position == model.index))
        }
        val selected = list.getChildAt(model.index) ?: return
        scroller.post {
            scroller.smoothScrollTo(
                0,
                (selected.top - (scroller.height - selected.height) / 2).coerceAtLeast(0)
            )
        }
    }

    private fun buildRow(row: FormRow, position: Int, selected: Boolean): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val h = Styler.dpInt(context, 12f)
            val v = Styler.dpInt(context, 9f)
            setPadding(h, v, h, v)
            background = if (selected && ringVisible()) selectedFace() else plainFace()
            alpha = if (selected) 1f else 0.62f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Styler.dpInt(context, 5f) }
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
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(if (row.danger) colors.dangerText else colors.accent)
                }, wide())
            }
            is FormRow.Toggle -> {
                container.addView(TextView(context).apply {
                    // A box rather than a tick glyph: an empty box reads as
                    // "you may choose this", where a missing tick reads as
                    // nothing at all.
                    text = if (row.checked) "☑" else "☐"
                    textSize = 17f
                    setTextColor(if (row.checked) colors.accent else colors.mutedText)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    rightMargin = Styler.dpInt(context, 10f)
                })
                container.addView(labelBlock(row.label, row.detail), wide())
            }
            is FormRow.Choice -> {
                container.addView(TextView(context).apply {
                    text = row.label
                    textSize = 14f
                    setTextColor(colors.mutedText)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                container.addView(
                    labelBlock(
                        // The arrows are the affordance. Without them nothing on
                        // screen says this row is a list rather than a label.
                        if (selected) "‹ ${row.value} ›" else row.value,
                        row.detail,
                        alignEnd = true
                    ),
                    LinearLayout.LayoutParams(0, WRAP, 1.4f)
                )
            }
        }
        return container
    }

    private fun labelBlock(text: String, detail: String, alignEnd: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(colors.primaryText)
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

    // Qualified: GradientDrawable has its own `colors`, and an unqualified
    // reference inside apply{} silently resolves to that one instead.
    private fun plainFace() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(this@FormOverlay.colors.stripBackground)
    }

    private fun selectedFace() = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(this@FormOverlay.colors.focusFill)
        setStroke(Styler.dpInt(context, 3f), this@FormOverlay.colors.focusRing)
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val SCRIM = Color.argb(190, 0, 0, 0)
    }
}
