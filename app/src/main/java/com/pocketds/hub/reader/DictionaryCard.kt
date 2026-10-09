package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import com.pocketds.hub.ui.typeRole

/**
 * What a selection opens (#62), one card in three shapes, anchored beside the words and never over them ([CardPlacement]):
 *
 *  - **The dictionary card** for a word, or for a phrase the dictionary has as one entry: the word, a speaker that says it
 *    (the device's own offline voice), the definitions, then the row of actions.
 *  - **The bar** for a phrase it has no entry for: only the row, with Look up, which never fails silently: the card then says
 *    "No entry for “…”. Showing “…”." and shows the first word that has one.
 *  - **The highlight's menu** for a tap on a highlight: its note, the colours with the one in use marked, Note or Edit note, Remove.
 *
 * The row is the four colours (the last one used first), Note, Look up for a phrase it has no entry for, Say it for a phrase, and
 * Copy. Every part is a real control, so the pad walks it with ◀▶ and Ⓐ, and a tap does the same. Ⓑ or a tap beside it closes it.
 */
class DictionaryCard(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : FrameLayout(context) {
    /** The selection's actions, handed to the reader. */
    var onClose: () -> Unit = {}
    var onColor: (HighlightColor) -> Unit = {}
    var onNote: () -> Unit = {}
    var onCopy: () -> Unit = {}
    var onSay: (String) -> Unit = {}
    var onLookUp: () -> Unit = {}
    var onRemove: () -> Unit = {}

    val isOpen get() = visibility == View.VISIBLE

    /** Whether the definitions are showing (the card), as opposed to the bar or a highlight's menu. */
    var showsDefinitions: Boolean = false
        private set

    /** The heading, the note and the definitions as they read, for a test. */
    val headingText: CharSequence get() = heading.text
    val noteText: CharSequence get() = lookupNote.text
    val definitionText: CharSequence get() = definition.text
    val highlightNoteText: CharSequence get() = savedNote.text

    private var anchor = RectF()
    private var topInset = 0
    private var bottomInset = 0
    private var speech = ""
    private var current: HighlightColor? = null
    private var lookUpAvailable = false
    private var phrase = false
    private var removable = false
    private var hasNote = false

    private val heading = TextView(context).apply {
        typeRole(Type.Role.HEADING, 20f)
        setTextColor(Color.WHITE)
        maxLines = 2
    }
    private val speaker = OverlayButtons.round(context, colors.focusRing, AppIcon.SPEAKER, "Say it") { onSay(speech) }
    private val lookupNote = TextView(context).apply {
        textSize = 12f
        setTextColor(0xFFE9C97A.toInt())
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { setColor(0x33E9C97A); cornerRadius = Styler.dp(context, 8f) }
    }
    private val definition = TextView(context).apply {
        textSize = 14f
        setTextColor(0xFFE4E9EC.toInt())
        setLineSpacing(Styler.dp(context, 3f), 1f)
    }
    private val definitions = FocusScrollView(context).apply { addView(definition) }
    private val source = TextView(context).apply {
        text = "English dictionary · offline, built into the app"
        textSize = 10.5f
        setTextColor(GlassColors.QUIET)
    }
    private val savedNote = TextView(context).apply {
        textSize = 14f
        setTextColor(0xFFE4E9EC.toInt())
        setLineSpacing(Styler.dp(context, 3f), 1f)
    }
    private val dots = HighlightColor.entries.map { color ->
        ColorDot(context, ReaderMarks.base(color), colors.focusRing).apply {
            contentDescription = "Highlight ${color.label.lowercase()}"
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { onColor(color) }
        }
    }
    private val noteButton = pill("Note", "Add a note", AppIcon.NOTE) { onNote() }
    private val lookUpButton = pill("Look up", "Look it up in the dictionary", AppIcon.BOOK) { onLookUp() }
    private val sayButton = pill("Say it", "Say the phrase", AppIcon.SPEAKER) { onSay(speech) }
    private val copyButton = pill("Copy", "Copy the words", AppIcon.COPY) { onCopy() }
    private val removeButton = pill("Remove", "Remove the highlight", AppIcon.TRASH) { onRemove() }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        setPadding(dp(14), dp(10), dp(12), dp(8))
        // Opaque: the words under it must not show through, and it is read at a glance.
        GlassPanelDrawable.attach(this, Styler.dp(context, 16f), GlassColors::card)
        elevation = Styler.dp(context, 14f)
    }
    private var order: List<View> = emptyList()

    init {
        visibility = GONE
        isClickable = true
        setOnClickListener { onClose() }
        head.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(speaker, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        panel.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(lookupNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(definitions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        panel.addView(source, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        panel.addView(savedNote, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2); bottomMargin = dp(4) })
        val scroller = FocusHorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        panel.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(panel, LayoutParams(dp(PANEL_DP), LayoutParams.WRAP_CONTENT))
    }

    /** The room the card may use, clear of the reader's bars when they are showing. */
    fun setInsets(top: Int, bottom: Int) { topInset = top; bottomInset = bottom }

    // ---------------------------------------------------------------- the three shapes

    /** A word, or a phrase being looked up: the heading at once, the definitions when they arrive ([show]). */
    fun showLoading(text: String, rect: RectF, isPhrase: Boolean, lastColor: HighlightColor) {
        begin(rect, lastColor)
        phrase = isPhrase
        speech = text
        showsDefinitions = true
        heading.text = text
        lookupNote.visibility = GONE
        definition.text = "Looking up locally…"
        savedNote.visibility = GONE
        lookUpAvailable = false
        build()
    }

    /** The dictionary's answer to what [showLoading] was opened for (or to Look up). */
    fun show(answer: PhraseLookup.Answer, selection: String) {
        if (!isOpen) return
        phrase = answer.isPhrase
        showsDefinitions = true
        speech = if (answer.isPhrase) selection else answer.shown
        heading.text = answer.shown.ifBlank { selection }
        lookupNote.visibility = if (answer.note != null) VISIBLE else GONE
        lookupNote.text = answer.note.orEmpty()
        definition.text = if (!answer.found) "No entry in the offline dictionary." else buildString {
            answer.entry!!.definitions.take(5).forEachIndexed { index, item ->
                if (index > 0) append("\n")
                append("${index + 1}. ")
                if (item.partOfSpeech.isNotBlank()) append("(${item.partOfSpeech}) ")
                append(item.text)
            }
        }
        savedNote.visibility = GONE
        lookUpAvailable = false
        build(keepFocus = true)
    }

    fun showFailure(message: String) {
        if (!isOpen) return
        definition.text = message
    }

    /** A phrase the dictionary has no single entry for: the row alone, with Look up (never silent) and Say it. */
    fun showBar(text: String, rect: RectF, lastColor: HighlightColor, canLookUp: Boolean = true) {
        begin(rect, lastColor)
        phrase = true
        speech = text
        showsDefinitions = false
        lookUpAvailable = canLookUp
        build()
    }

    /** A tap on a highlight: its note when it has one, the colours with the one in use marked, Note or Edit note, Remove. */
    fun showHighlight(annotation: ReadingAnnotation, rect: RectF) {
        begin(rect, annotation.highlightColor)
        current = annotation.highlightColor
        phrase = false
        showsDefinitions = false
        lookUpAvailable = false
        removable = true
        hasNote = annotation.hasNote
        speech = annotation.quote.highlight
        savedNote.text = annotation.note
        savedNote.visibility = if (annotation.hasNote) VISIBLE else GONE
        build()
    }

    fun dismiss() { visibility = GONE; showsDefinitions = false }

    private fun begin(rect: RectF, lastColor: HighlightColor) {
        anchor = RectF(rect)
        current = lastColor
        removable = false
        hasNote = false
        savedNote.visibility = GONE
        lookupNote.visibility = GONE
        visibility = VISIBLE
    }

    /** Lays the parts out for the shape just chosen and puts the cursor on the colour the next highlight will be. */
    private fun build(keepFocus: Boolean = false) {
        val card = showsDefinitions
        head.visibility = if (card) VISIBLE else GONE
        definitions.visibility = if (card) VISIBLE else GONE
        source.visibility = if (card) VISIBLE else GONE
        speaker.visibility = if (card) VISIBLE else GONE
        heading.visibility = if (card) VISIBLE else GONE
        row.removeAllViews()
        dots.forEachIndexed { index, dot ->
            val color = HighlightColor.entries[index]
            dot.marked = removable && color == current
            row.addView(dot, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(4) })
        }
        row.addView(View(context).apply { setBackgroundColor(GlassColors.TRACK) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginStart = dp(4); marginEnd = dp(6) })
        noteButton.text = if (hasNote) "Edit note" else "Note"
        noteButton.contentDescription = if (hasNote) "Edit the note" else "Add a note"
        row.addView(noteButton, buttonParams())
        if (lookUpAvailable) row.addView(lookUpButton, buttonParams())
        if (phrase && !removable) row.addView(sayButton, buttonParams())
        if (removable) row.addView(removeButton, buttonParams()) else row.addView(copyButton, buttonParams())
        order = buildList {
            if (card) add(speaker)
            addAll(dots)
            add(noteButton)
            if (lookUpAvailable) add(lookUpButton)
            if (phrase && !removable) add(sayButton)
            add(if (removable) removeButton else copyButton)
        }
        if (!keepFocus || findFocus() == null) post { if (isOpen) focusStart() }
        positionAfterLayout()
    }

    /** The cursor starts on the colour in use, so a highlight is one press of Ⓐ away. */
    private fun focusStart() {
        val start = dots.getOrNull(HighlightColor.entries.indexOf(current).coerceAtLeast(0)) ?: noteButton
        start.requestFocus()
    }

    private fun buttonParams() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(2) }

    private fun pill(label: String, description: String, icon: AppIcon, tap: () -> Unit): TextView =
        OverlayButtons.pill(context, colors.focusRing, label, description, icon, tap).also {
            FocusDecorator.attach(it, ringVisible, scale = false)
        }

    // ---------------------------------------------------------------- the pad

    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        when (action) {
            PadAction.Back -> onClose()
            PadAction.Activate -> (findFocus() ?: order.firstOrNull())?.performClick()
            is PadAction.Step -> when (action.direction) {
                Direction.LEFT -> walk(-1)
                Direction.RIGHT -> walk(1)
                Direction.UP -> if (showsDefinitions && definitions.canScrollVertically(-1)) definitions.smoothScrollBy(0, -dp(48)) else walk(-1)
                Direction.DOWN -> if (showsDefinitions && definitions.canScrollVertically(1)) definitions.smoothScrollBy(0, dp(48)) else walk(1)
            }
            is PadAction.Pan -> definitions.scrollBy(0, (action.dy * definitions.height).toInt())
            else -> Unit
        }
        return true
    }

    private fun walk(step: Int) {
        if (order.isEmpty()) return
        val at = order.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 } ?: 0
        order[(at + step).coerceIn(0, order.lastIndex)].requestFocus()
    }

    /** Which control has the cursor, by its description, for a test. */
    val focusedLabel: CharSequence? get() = order.firstOrNull { it.hasFocus() }?.contentDescription

    /** The controls the pad walks, in order: their descriptions, for a test. */
    val controlLabels: List<CharSequence?> get() = order.map { it.contentDescription }

    /** A control by its description, for a test to press. */
    fun control(description: String): View? = order.firstOrNull { it.contentDescription == description }

    // ---------------------------------------------------------------- place

    /** The card's rectangle on the screen, for a test to hold against the selection's. */
    val bounds: RectF get() = RectF(panel.left.toFloat(), panel.top.toFloat(), panel.right.toFloat(), panel.bottom.toFloat())

    private fun positionAfterLayout() = post {
        if (!isOpen) return@post
        val margin = dp(12)
        val width = dp(if (lookUpAvailable) WIDE_DP else PANEL_DP).coerceAtMost((this.width - margin * 2).coerceAtLeast(dp(220)))
        val params = panel.layoutParams as LayoutParams
        if (params.width != width) { params.width = width; panel.layoutParams = params }
        val usable = CardPlacement.Box(margin.toFloat(), (topInset + margin).toFloat(), (this.width - margin).toFloat(), (this.height - bottomInset - margin).toFloat())
        val box = CardPlacement.Box(anchor.left, anchor.top, anchor.right, anchor.bottom)
        val gap = dp(10).toFloat()
        fun measure() = panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(this.height, MeasureSpec.AT_MOST))
        definitions.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        measure()
        var place = CardPlacement.place(box, width.toFloat(), panel.measuredHeight.toFloat(), usable, gap)
        // A card that fits nowhere beside the words lets its definitions scroll, so it covers as little as it can.
        if (place.covers && showsDefinitions) {
            val room = maxOf(usable.bottom - box.bottom - gap, box.top - gap - usable.top)
            definitions.layoutParams.height = maxOf(dp(56), definitions.measuredHeight - (panel.measuredHeight - room.toInt()))
            measure()
            place = CardPlacement.place(box, width.toFloat(), panel.measuredHeight.toFloat(), usable, gap)
        }
        params.leftMargin = place.x.toInt()
        params.topMargin = place.y.toInt()
        panel.layoutParams = params
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    /** A colour to choose, round, with a white ring when it is the one the highlight already has. */
    private class ColorDot(context: Context, private val fill: Int, ring: Int) : View(context) {
        var marked = false
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            Styler.makeFocusable(this)
            background = OverlayButtons.ringed(context, ring, GradientDrawable.OVAL, Color.TRANSPARENT)
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawCircle(cx, cy, 11f * d, paint)
            if (marked) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = Color.WHITE
                canvas.drawCircle(cx, cy, 11f * d, paint)
            }
        }
    }

    private companion object {
        const val PANEL_DP = 468
        /** The bar with Look up and Say it has more to hold than a card's row does. */
        const val WIDE_DP = 560
    }
}
