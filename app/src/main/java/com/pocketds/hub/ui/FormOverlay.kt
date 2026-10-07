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
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow
import com.pocketds.hub.state.section
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable

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
 *
 * It is drawn as the prototype's request sheet: the shared side sheet of the
 * page's glass ([SidePanelView]), a small capital heading over each part
 * (`FormRow.section`), "‹ HD-1080p ›" on a row that steps with left and right,
 * and the white Request at the foot.
 */
class FormOverlay(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    /** The side whose request this is; its main action takes the side's face (PillButton.mainFace). */
    private val side: com.pocketds.hub.state.ContentMode = com.pocketds.hub.state.ContentMode.MEDIA,
    /** A small centred card in place of the side sheet: a short question, "When did you finish?" (#39). */
    centred: Boolean = false
) : FrameLayout(context) {

    /** The side sheet the rows are drawn into. It brings its own shade and takes a tap outside it as Cancel. */
    private val sheet: SidePanelView = if (centred) CentredSheet(context, colors, ringVisible)
        else SidePanelView(context, colors, ringVisible, side = true)

    /** The centred form of the same panel, as high as its rows and no wider than a question needs. */
    private class CentredSheet(context: Context, colors: PocketColors, ringVisible: () -> Boolean) :
        SidePanelView(context, colors, ringVisible, side = false) {
        override val wrapsHeight: Boolean get() = true
        override val centredWidthDp: Int get() = 380
    }

    private var model: FormModel? = null
    private var onSubmit: ((String, FormModel) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var onChanged: ((FormModel) -> Unit)? = null

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        isClickable = true
        isFocusable = false
        visibility = View.GONE
        setOnClickListener { cancel() }
        addView(sheet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        visibility = View.VISIBLE
        bringToFront()
        sheet.resetBody()
        sheet.open(title, subtitle) { cancel() }
        rebuild()
    }

    fun dismiss() {
        visibility = View.GONE
        model = null
        onSubmit = null
        onCancel = null
        onChanged = null
        sheet.dismiss()
    }

    /** Redraw after the caller changed the rows, e.g. hiding the season list. */
    fun refresh() {
        if (isOpen) rebuild()
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
     * The rows into the side sheet, a heading where a part begins and each
     * part on its own glass card; the actions at its foot. Rebuilt on every
     * press, keeping the sheet's scroll. The model's index still names the
     * row, so the cursor follows the press.
     */
    private fun rebuild() {
        val model = model ?: return
        sheet.resetBody(keepScroll = true)
        var section = ""
        var group: LinearLayout? = null
        var selectedView: View? = null
        model.rows().forEachIndexed { position, row ->
            val selected = position == model.index
            if (row is FormRow.Action) {
                sheet.footer.addView(sheetAction(row, position, selected), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    topMargin = Styler.dpInt(context, 6f); bottomMargin = Styler.dpInt(context, 8f)
                })
                return@forEachIndexed
            }
            if (row.section.isNotEmpty() && row.section != section) {
                sheet.section(row.section)
                section = row.section
                group = null
            }
            val card = group ?: sheet.group().also { group = it }
            val view = sheetRow(row, position, selected)
            card.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP))
            if (selected) selectedView = view
        }
        selectedView?.let(sheet::reveal)
    }

    private fun sheetRow(row: FormRow, position: Int, selected: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Styler.dpInt(context, 44f)
        val h = Styler.dpInt(context, 11f)
        val v = Styler.dpInt(context, 8f)
        setPadding(h, v, h, v)
        // The cursor is the model's, so its ring is drawn here: inset, as
        // every row inside a card has it.
        background = if (selected && ringVisible()) ThemeGradientDrawable.rounded(Styler.dp(context, 12f), colors.focusFill,
            Styler.dpInt(context, 2f), colors.focusRing) else null
        setOnClickListener {
            val current = this@FormOverlay.model ?: return@setOnClickListener
            current.focus(position)
            current.adjust(1)
            changed()
        }
        when (row) {
            is FormRow.Choice -> {
                addView(sheetLabel(row.label, row.detail), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(TextView(context).apply {
                    // Always the arrows: they say the row steps with left and right.
                    text = android.text.SpannableStringBuilder().apply {
                        append("‹  ", android.text.style.ForegroundColorSpan(SHEET_ARROW), 0)
                        val start = length
                        append(row.value)
                        setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), start, length, 0)
                        setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length, 0)
                        append("  ›", android.text.style.ForegroundColorSpan(SHEET_ARROW), 0)
                    }
                    textSize = 13f
                    gravity = Gravity.END
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                }, LinearLayout.LayoutParams(0, WRAP, 1.3f))
            }
            is FormRow.Toggle -> {
                addView(sheetLabel(row.label, row.detail), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(android.widget.ImageView(context).apply {
                    setImageDrawable(AppIconDrawable(AppIcon.CHECK, if (row.checked) colors.accent else SHEET_UNCHECKED))
                    contentDescription = if (row.checked) "On" else "Off"
                }, LinearLayout.LayoutParams(Styler.dpInt(context, 18f), Styler.dpInt(context, 18f)).apply { marginStart = Styler.dpInt(context, 10f) })
            }
            is FormRow.Action -> Unit
        }
    }

    private fun sheetLabel(text: String, detail: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            textWeight(700)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (detail.isNotEmpty()) addView(TextView(context).apply {
            this.text = detail
            textSize = 11f
            setTextColor(SHEET_NOTE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply { topMargin = Styler.dpInt(context, 2f) })
    }

    /** Request as the page's main action is (white on Media, gold on Books); a destructive one is glass in the danger colour. */
    private fun sheetAction(row: FormRow.Action, position: Int, selected: Boolean): View = CenteredIconTextView(context).apply {
        text = row.label
        textSize = 13f
        textWeight(700)
        gravity = Gravity.CENTER
        includeFontPadding = false
        // Glass in the danger colour for a destructive one, glass in white words for a Cancel, else the side's main face.
        val glass = row.danger || row.quiet
        val ink = if (row.danger) colors.dangerText else if (row.quiet) Color.WHITE else PillButton.mainInk(colors, side)
        setTextColor(ink)
        // The icon beside the word, the pair centred, as the prototype's Request has it.
        if (!glass && row.icon) setCenteredIcon(MediaActionIconDrawable(context, MediaActionIcon.DOWNLOAD, ink),
            Styler.dpInt(context, 14f), Styler.dpInt(context, 7f))
        val ring = Styler.dpInt(context, PillButton.RING_DP)
        val corner = Styler.dp(context, 11f)
        val fill = if (glass) com.pocketds.hub.ui.glass.GlassPanelDrawable(GlassColors.panel(GlassPage.palette(context)), corner)
            else ThemeGradientDrawable.rounded(corner, PillButton.mainFace(colors, side))
        background = if (selected && ringVisible()) android.graphics.drawable.LayerDrawable(arrayOf(
            ThemeGradientDrawable.rounded(corner + ring, Color.TRANSPARENT, Styler.dpInt(context, 2f), colors.focusRing),
            android.graphics.drawable.InsetDrawable(fill, ring)))
        else android.graphics.drawable.InsetDrawable(fill, ring)
        minimumHeight = Styler.dpInt(context, 36f) + 2 * ring
        setPadding(ring, ring, ring, ring)
        setOnClickListener {
            val current = this@FormOverlay.model ?: return@setOnClickListener
            current.focus(position)
            onSubmit?.invoke(row.id, current)
        }
    }

    private companion object {
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The sheet's arrows white at 62%, a row's note at 60%, an unticked tick at 25%. */
        const val SHEET_ARROW = 0x9EFFFFFF.toInt()
        const val SHEET_NOTE = 0x99FFFFFF.toInt()
        const val SHEET_UNCHECKED = 0x40FFFFFF
    }
}
