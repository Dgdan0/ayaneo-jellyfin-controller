package com.pocketds.hub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private fun View.dp(value: Int) = Styler.dpInt(context, value.toFloat())
private fun label(context: Context, size: Float, color: Int) = TextView(context).apply {
    textSize = size; setTextColor(color); includeFontPadding = false
    // Right-to-left titles start where the rest of the page does.
    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
}

object DetailStyler {
    fun action(view: TextView, colors: PocketColors, primary: Boolean = false) {
        view.setTextColor(if (primary) colors.accentText else colors.primaryText)
        // Round: a circle for an icon, a pill for words. The quiet fill shows
        // the button is there before focus reaches it.
        val quiet = androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x1C)
        fun face(fill: Int, stroke: Int = 0) = ThemeGradientDrawable().apply {
            cornerRadius = view.dp(999).toFloat()
            setColor(fill)
            if (stroke != 0) setStroke(view.dp(2), stroke)
        }
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(if (primary) colors.accent else colors.focusFill, colors.focusRing))
            addState(intArrayOf(android.R.attr.state_focused), face(
                if (primary) colors.accent else quiet, colors.focusRing))
            addState(intArrayOf(), face(if (primary) colors.accent else quiet))
        }
        view.minimumHeight = view.dp(48)
        view.minimumWidth = view.dp(48)
        view.gravity = Gravity.CENTER
        Styler.makeFocusable(view)
    }

    fun image(view: ImageView, data: Any?, loader: ImageLoader) = Artwork.bind(view, loader, data)

    /**
     * A round toggle of the page's glass beside Play -- watched,
     * favourite, download, more -- white while it is on (GLASS_PLAN.md). Its
     * face is [GLASS_TOGGLE_DP] across inside room for the focus ring; light
     * it with [com.pocketds.hub.ui.glass.GlassButtonBackground.lit].
     */
    fun glassToggle(view: TextView, colors: PocketColors, lit: Boolean = false) =
        com.pocketds.hub.ui.glass.GlassButtonBackground.attach(view, colors, view.dp(999).toFloat(), view.dp(PillButton.RING_DP.toInt()), lit).also {
            view.gravity = Gravity.CENTER
            Styler.makeFocusable(view)
        }

    /** The prototype's Pocket toggle (`.rb`), and the view round it with the ring's room. */
    const val GLASS_TOGGLE_DP = 34
    val GLASS_TOGGLE_VIEW_DP: Int get() = GLASS_TOGGLE_DP + 2 * PillButton.RING_DP.toInt()
}

/**
 * One header for media, books and downloaded items. It grows with text instead of clipping it.
 *
 * It is a title page as the prototype has it (GLASS_PLAN.md): the backdrop
 * across the top of the page, fading into it through a mask rather than into a
 * colour, and running on under the tabs below; the words in the prototype's
 * type at its left.
 *
 * A book's page sets [book] (the prototype's `.bhead`): no backdrop, the cover
 * at full size at the left (square for an audiobook, [squareCover]) or a
 * series' fan in its place ([replacePoster]), and the words beside its foot.
 */
class DetailHeaderView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : FrameLayout(context) {
    val landscape: ImageView = com.pocketds.hub.ui.glass.FadedImageView(context).apply {
        stops = com.pocketds.hub.ui.glass.FadedImageView.TITLE
        shade = com.pocketds.hub.ui.glass.FadedImageView.TITLE_SHADE
        scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    /** A line over the title, "NOT IN YOUR LIBRARY" on a title you can request. */
    val eyebrowView = label(context, 10.5f, EYEBROW).apply {
        typeRole(Type.Role.EYEBROW, 10.5f); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; visibility = GONE
        // An eyebrow is capitals however a screen writes it ("Book 6 · Red Rising").
        isAllCaps = true
    }
    val poster = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = ThemeGradientDrawable.rounded(Styler.dp(context, COVER_CORNER_DP), colors.posterPlaceholder)
        clipToOutline = true
        elevation = Styler.dp(context, 14f)
    }
    /**
     * A book's cover and what is under it (#39): your stars, when you finished, your shelves. Nothing
     * shows when there is nothing to say.
     */
    val coverColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    val ratingView = StarRatingView(context, colors, ringVisible).apply { visibility = GONE }
    val finishedView = label(context, 11.5f, FACTS).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; visibility = GONE }
    val shelvesView = label(context, 11.5f, colors.accent).apply {
        textWeight(700); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; visibility = GONE
    }
    /** The formats as a row of icon and name, between the facts and the actions (#39). */
    val formatRow = ReadingFormatRowView(context, colors, ringVisible).apply { visibility = GONE }
    /** The genres as one quiet line under the actions (#39). */
    val genresView = label(context, 11.5f, com.pocketds.hub.ui.glass.GlassColors.QUIET).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = GONE
    }
    val titleView = label(context, TITLE_SP, colors.primaryText).apply {
        typeRole(Type.Role.HERO); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        typeface = Type.display(context, 800)
        textSize = TITLE_SP
        setLineSpacing(0f, .95f)
    }
    val subtitleView = label(context, 12f, SUBTITLE).apply { visibility = GONE }
    /** Links under the title: a book's author and series. */
    val links = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; visibility = GONE }
    /** The prototype's bar (`.prog .b`), 4dp in the accent on a faint track. */
    private val progressBar = com.pocketds.hub.ui.glass.GlassProgressBar(context, colors.accent, com.pocketds.hub.ui.glass.GlassColors.TRACK)
    /** How far through, in words after the bar: "49% · page 363 of 735". */
    val progressLabel = label(context, 12f, FACTS)
    val progressRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        addView(progressBar, LinearLayout.LayoutParams(Styler.dpInt(context, PROGRESS_DP.toFloat()),
            Styler.dpInt(context, com.pocketds.hub.ui.glass.GlassProgressBar.HEIGHT_DP)))
        addView(progressLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Styler.dpInt(context, 12f) })
    }
    /**
     * The facts line. Read left to right part by part, so "11 min" after a
     * Hebrew episode title stays "11 min"; screens join it with [Bidi.join].
     */
    val metadataView = label(context, 12f, FACTS).apply {
        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        textDirection = TEXT_DIRECTION_LTR; textAlignment = TEXT_ALIGNMENT_VIEW_START
    }
    /** Under the facts line, before the overview: the request page's pipeline. */
    val underFacts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; visibility = GONE }
    val formatStatus = ReadingFormatStatusView(context).apply { visibility=GONE }
    val stateView = label(context, 12f, colors.accent).apply { textWeight(700); visibility = GONE }
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    val overview = DetailOverviewView(context, colors, ringVisible).apply { tone(12.5f, OVERVIEW) }
    val continuation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
    // Lets the action row reach a ring's width left of the words (below); a
    // series' fan leans past the row's edge, which must not cut it.
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; clipChildren = false; clipToPadding = false
    }
    /**
     * The action row starts a ring's width left of the words, with that much
     * padding, so its buttons still begin under the words. A screen whose first
     * action is a [PillButton] pulls it back by [PillButton.RING_DP] so the pill
     * itself lines up with the title; its focus ring then lands in this room.
     * Without it the row clipped the ring and the left of Play.
     */
    private val actionScroll = FocusHorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false; clipChildren = false
        setPadding(dp(PillButton.RING_DP.toInt()), dp(4), dp(3), dp(4))
        addView(actions, ViewGroup.LayoutParams(WRAP, WRAP))
    }
    /** A book's page, its cover beside the words and no backdrop. */
    var book = false
        set(value) { field = value; layoutKey = ""; requestLayout() }
    /** A book's page: an audiobook's cover is square. */
    var squareCover = false
        set(value) { field = value; layoutKey = ""; requestLayout() }
    /**
     * A book's page under the owner's layout "1" (#39): the formats and the actions follow the facts, the
     * genres come under them, and under the cover are your stars. Set before the page is bound.
     */
    var reading = false
        set(value) { if (field != value) { field = value; arrangeBody(); layoutKey = ""; requestLayout() } }
    private var hasLandscape = false
    private var layoutKey = ""
    private var imageKey: List<Any?> = emptyList()

    init {
        // The faded backdrop is taller than the words and runs on under the
        // tabs, as the prototype's does; the scroll view still clips it.
        clipChildren = false
        setBackgroundColor(colors.background)
        addView(landscape, LayoutParams(MATCH, MATCH))
        coverColumn.addView(poster, LinearLayout.LayoutParams(dp(92), dp(138)))
        coverColumn.addView(ratingView, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8); marginStart = -dp(RATING_BLEED_DP) })
        coverColumn.addView(finishedView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        coverColumn.addView(shelvesView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        row.addView(coverColumn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(20) })
        arrangeBody()
        row.addView(body, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(row, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    /**
     * The words beside the cover, in the order a page keeps them: a title page puts the overview before the
     * actions, which are read after it; a book's page under layout "1" ([reading], #39) goes straight from
     * the facts to the formats, the actions and the genres, with the overview after them.
     */
    private fun arrangeBody() {
        body.removeAllViews()
        fun add(view: View, top: Int = 0, bottom: Int = 0, start: Int = 0) =
            body.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top); bottomMargin = dp(bottom); marginStart = start })
        val ring = -dp(PillButton.RING_DP.toInt())
        add(eyebrowView, bottom = 6)
        add(titleView)
        add(links, top = 6)
        add(subtitleView, top = 4)
        add(metadataView, top = 7)
        add(underFacts, top = 6)
        add(formatStatus, top = 4)
        add(stateView, top = 5)
        if (reading) {
            add(formatRow, top = 6, start = ring)
            add(actionScroll, top = 2, start = ring)
            add(genresView, top = 2)
            add(progressRow, top = 8)
            add(overview, top = 6)
        } else {
            add(progressRow, top = 8)
            // The overview is read before acting on it, so it sits above the buttons.
            add(overview, top = 6)
            add(actionScroll, top = 6, start = ring)
        }
        add(continuation, top = 10)
    }

    /** How far through: the bar under the facts. */
    fun showProgress(fraction: Double) {
        progressBar.fraction = fraction
    }

    private var leading: View? = null

    /** Something in the poster's place: a series page shows a fan of its books. */
    fun replacePoster(view: View, widthDp: Int, heightDp: Int, startDp: Int = 0, endDp: Int = 20) {
        leading?.let(row::removeView)
        leading = view
        row.addView(view, 0, LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { marginEnd = dp(endDp); marginStart = dp(startDp) })
        requestLayout()
    }

    /** Whether there is a backdrop to show across the top. */
    fun setPresentation(hasLandscape: Boolean) {
        this.hasLandscape = hasLandscape
        requestLayout()
    }

    fun bindArtwork(type: String, landscapeData: Any?, posterData: Any?, loader: ImageLoader) {
        val next = listOf(type, landscapeData, posterData)
        if (next == imageKey) return
        imageKey = next
        if (book) {
            // A book's page shows its cover, at the left, and no backdrop.
            setPresentation(false)
            DetailStyler.image(poster, posterData, loader)
            return
        }
        // No poster beside the words: the backdrop, else the poster, fills the
        // top of the page and fades into it.
        setPresentation(landscapeData != null || posterData != null)
        Artwork.bind(landscape, loader, landscapeData ?: posterData, opaque = true)
    }

    /**
     * The prototype's title page: the words at the left from 64dp down, no
     * wider than 560dp, and the backdrop [ART_DP] tall however long the words
     * run, so it reaches under the tabs and fades out there.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val next = "$width:$book:$squareCover:${leading != null}"
        if (layoutKey != next) {
            layoutKey = next
            if (book) {
                // The prototype's `.bhead`: 8dp under the bar, the cover 112dp
                // wide, the words 18dp beside it, both standing on one line
                // (the pills' ring room lies below them).
                val ring = dp(PillButton.RING_DP.toInt())
                // Under layout "1" (#39) the cover and the words start together, and what is under the cover hangs below it.
                row.gravity = if (reading) Gravity.TOP else Gravity.BOTTOM
                row.setPadding(dp(EDGE_DP), dp(BOOK_TOP_DP), dp(EDGE_DP), dp(4))
                coverColumn.visibility = if (leading == null) VISIBLE else GONE
                poster.visibility = VISIBLE
                leading?.visibility = VISIBLE
                poster.layoutParams = LinearLayout.LayoutParams(dp(COVER_DP), dp(if (squareCover) COVER_DP else COVER_DP * 3 / 2))
                coverColumn.layoutParams = LinearLayout.LayoutParams(dp(COVER_DP), WRAP).apply {
                    marginEnd = dp(BOOK_GAP_DP); bottomMargin = ring
                }
                (leading?.layoutParams as? LinearLayout.LayoutParams)?.let { it.marginEnd = dp(BOOK_GAP_DP); it.bottomMargin = ring }
                body.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            } else {
                row.gravity = Gravity.TOP
                coverColumn.visibility = GONE
                leading?.visibility = GONE
                row.setPadding(dp(EDGE_DP), dp(TOP_DP), dp(EDGE_DP), dp(4))
                body.layoutParams = LinearLayout.LayoutParams(minOf(dp(WORDS_DP), width - 2 * dp(EDGE_DP)), WRAP)
            }
            overview.previewLines(2)
        }
        landscape.visibility = if (hasLandscape && !book) VISIBLE else GONE
        eyebrowView.visibility = if (eyebrowView.text.isNullOrBlank()) GONE else VISIBLE
        underFacts.visibility = if (underFacts.childCount > 0) VISIBLE else GONE
        actionScroll.visibility = if (actions.childCount > 0 && actions.visibility != GONE) VISIBLE else GONE
        continuation.visibility = if (continuation.childCount > 0) VISIBLE else GONE
        // Only text and actions decide the height; the backdrop is measured to it afterwards.
        row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, resolveSize(row.measuredHeight, heightMeasureSpec))
        landscape.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(ART_DP), MeasureSpec.EXACTLY))
    }

    private companion object {
        /** The prototype's Pocket title page (`.dart`, `.dhead`). */
        const val ART_DP = 330
        const val TOP_DP = 64
        const val EDGE_DP = 22
        const val WORDS_DP = 560
        const val TITLE_SP = 30f
        /** A book's page (`.bhead`): 8dp under the bar, a 112dp cover 18dp from the words, a long bar. */
        const val BOOK_TOP_DP = 8
        const val COVER_DP = 112
        const val COVER_CORNER_DP = 9f
        const val BOOK_GAP_DP = 18
        const val PROGRESS_DP = 220
        /** Your stars hang this far left of the cover's edge, their ring's room, so the first star lines up with it. */
        const val RATING_BLEED_DP = 3
        /** The facts in white at 82%, an original title at 60%, the overview at 86%, an eyebrow at 72%. */
        const val FACTS = com.pocketds.hub.ui.glass.GlassColors.FACTS
        const val SUBTITLE = 0x99FFFFFF.toInt()
        const val OVERVIEW = 0xDBFFFFFF.toInt()
        const val EYEBROW = com.pocketds.hub.ui.glass.GlassColors.EYEBROW
    }
}

/** A bounded synopsis. Collapsed content is itself a 48dp first-tap target. */
class DetailOverviewView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : LinearLayout(context) {
    var expanded = false; private set
    var onChanged: (() -> Unit)? = null
    val actionHint: String? get() = when {
        collapsed.hasFocus() -> "Read more"
        close.hasFocus() -> "Collapse description"
        else -> null
    }
    private var value = ""
    private val collapsed = LinearLayout(context).apply { orientation = VERTICAL; minimumHeight = dp(48); setPadding(dp(3), dp(3), dp(3), dp(3)) }
    private val preview = label(context, 13f, colors.mutedText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.13f) }
    private val prompt = label(context, 11f, colors.mutedText).apply { text = "Read more"; setPadding(0, dp(3), 0, 0) }
    private val copy = label(context, 14f, colors.primaryText).apply { setLineSpacing(0f, 1.16f) }
    private val box = ScrollView(context).apply { addView(copy); isFillViewport = false; isVerticalScrollBarEnabled = true; setPadding(dp(6), dp(6), dp(6), dp(6)) }
    private val close = label(context, 12f, colors.mutedText).apply { text = "Collapse description"; DetailStyler.action(this, colors) }

    init {
        orientation = VERTICAL; visibility = GONE
        collapsed.addView(preview, LayoutParams(MATCH, WRAP)); collapsed.addView(prompt)
        collapsed.background = Styler.cardBackground(context, colors, 8f, Color.TRANSPARENT, 2f)
        Styler.makeFocusable(collapsed); FocusDecorator.attach(collapsed, ringVisible, scale = false)
        collapsed.activateOnTap(::expand)
        close.activateOnTap { collapse(); collapsed.requestFocus() }
        FocusDecorator.attach(close, ringVisible, scale = false)
        listOf(collapsed, box, close).forEach { target ->
            target.setOnFocusChangeListener { view, _ ->
                if (view != box) FocusDecorator.refresh(view, ringVisible())
                onChanged?.invoke()
            }
        }
        addView(collapsed, LayoutParams(MATCH, WRAP))
        addView(box, LayoutParams(MATCH, dp(118)))
        addView(close, LayoutParams(WRAP, dp(48)))
        collapse()
    }

    fun previewLines(count: Int) { preview.maxLines = count }
    /** Glass: the preview in the prototype's size and white. */
    fun tone(sizeSp: Float, color: Int) { preview.textSize = sizeSp; preview.setTextColor(color) }

    fun bind(text: String) {
        if (value != text) { value = text; collapse() }
        preview.text = text; copy.text = text
        collapsed.contentDescription = "Read full description"
        visibility = if (text.isBlank()) GONE else VISIBLE
    }
    fun expand() {
        expanded = true; collapsed.visibility = GONE; box.visibility = VISIBLE; close.visibility = VISIBLE
        Styler.makeFocusable(box); box.background = Styler.cardBackground(context, colors, 8f, Color.TRANSPARENT, 2f)
        box.requestFocus()
        onChanged?.invoke()
    }
    fun collapse() {
        expanded = false; collapsed.visibility = VISIBLE; box.visibility = GONE; close.visibility = GONE; box.scrollTo(0, 0)
        onChanged?.invoke()
    }
    fun onPad(action: PadAction): Boolean {
        if (!hasFocus()) return false
        if (action == PadAction.Activate) {
            when {
                collapsed.hasFocus() -> expand()
                close.hasFocus() -> { collapse(); collapsed.requestFocus() }
            }
            return true
        }
        if (expanded && action == PadAction.Back) {
            collapse(); collapsed.requestFocus(); return true
        }
        if (!expanded || !box.hasFocus() || action !is PadAction.Step) return false
        val direction = when (action.direction) { Direction.UP -> -1; Direction.DOWN -> 1; else -> 0 }
        if (direction == 0 || !box.canScrollVertically(direction)) return false
        box.smoothScrollBy(0, direction * dp(44)); return true
    }
}

/**
 * Where to carry on, as its own card: the prototype's `.cont` (GLASS_PLAN.md).
 * A card of the page's glass with 18dp corners and the ring hugging it, the
 * picture small at its left, the words with a bar in the accent, and a play
 * disc in the side's main face -- white on Media, gold on Books
 * ([PillButton.mainFace]). A Books series' book being read and a downloaded
 * series' next episode both draw this one.
 */
class ContinuationCardView(
    context: Context,
    colors: PocketColors,
    private val ringVisible: () -> Boolean,
    /** A cover (2:3) rather than a still (16:9). */
    portrait: Boolean = false,
    /** Whose main action the disc is, which decides its face. */
    side: com.pocketds.hub.state.ContentMode = com.pocketds.hub.state.ContentMode.MEDIA
) : LinearLayout(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        background = ThemeGradientDrawable.rounded(Styler.dp(context, 4f), colors.posterPlaceholder)
        clipToOutline = true
    }
    val titleView = label(context, 13f, Color.WHITE).apply { textWeight(700); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
    val metadataView = label(context, 11.5f, com.pocketds.hub.ui.glass.GlassColors.QUIET).apply {
        isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
    }
    /** How far through, in the accent; nothing to show hides it. */
    val progressView = com.pocketds.hub.ui.glass.GlassProgressBar(context, colors.accent, TRACK)
    var onFocused: (() -> Unit)? = null
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, CORNER_DP))
        // The prototype's ring hugs the card (`.cont.pf`).
        foreground = Styler.focusOutline(context, colors, CORNER_DP, 3f)
        setPadding(dp(6), dp(6), dp(10), dp(6))
        Styler.makeFocusable(this); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(image, LayoutParams(dp(if (portrait) PICTURE_DP * 2 / 3 else PICTURE_DP * 16 / 9), dp(PICTURE_DP)))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(titleView, LayoutParams(MATCH, WRAP))
            addView(metadataView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
            addView(progressView, LayoutParams(MATCH, dp(4)).apply { topMargin = dp(5) })
        }, LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
        addView(FrameLayout(context).apply {
            background = ThemeGradientDrawable.oval(PillButton.mainFace(colors, side))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(ImageView(context).apply {
                setImageDrawable(AppIconDrawable(AppIcon.PLAY, PillButton.mainInk(colors, side)))
            }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER).apply { leftMargin = dp(1) })
        }, LayoutParams(dp(DISC_DP), dp(DISC_DP)))
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) onFocused?.invoke() }
    }
    /**
     * Down from this card goes to [target]: the card spans the page over a row,
     * and Android's search took the one under its middle (the middle season of a
     * downloaded series, #23; book 4 of 6 on a series page, #26).
     */
    fun downTo(target: View?) {
        if (target == null) { nextFocusDownId = View.NO_ID; return }
        if (target.id == View.NO_ID) target.id = View.generateViewId()
        nextFocusDownId = target.id
    }

    fun bind(title: String, metadata: String, fraction: Double, completed: Boolean) {
        titleView.text = title; metadataView.text = metadata
        progressView.fraction = if (completed) 0.0 else fraction
        contentDescription = "$title, $metadata"
    }

    private companion object {
        /** The prototype's Pocket `.cont`: 18dp corners, a 48dp picture, an 18% track and a 30dp play disc. */
        const val CORNER_DP = 18f
        const val PICTURE_DP = 48
        const val TRACK = 0x2EFFFFFF
        const val DISC_DP = 30
    }
}

/**
 * Artwork has no inset white frame; captions and focus clearance are measured together.
 *
 * It is the prototype's book card: the cover with 11dp corners and the 3dp
 * ring on it, no box round the card, the title on one bold line over a quiet
 * one, and the marks of a poster (a white bar inside the cover, the tick in
 * the accent).
 */
class DetailArtworkCardView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : LinearLayout(context) {
    private var portraitRing: android.graphics.drawable.Drawable? = null
    /** The ring on the cover, which takes the card's focused state. */
    private val coverRing = Styler.focusOutline(context, colors, ArtworkFrame.GLASS_CORNER_DP, 3f)
    private var coverMarks: android.graphics.drawable.Drawable? = null
    /** The small round mark of a book that is an ebook and an audiobook too (#54). */
    private var formatMark: android.graphics.drawable.Drawable? = null
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 12f, colors.primaryText).apply { textWeight(700); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val subtitleView = label(context, 11f, SettingsCard.GLASS_QUIET).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, dp(6))
        image.background = ThemeGradientDrawable().apply { cornerRadius = Styler.dp(context, ArtworkFrame.GLASS_CORNER_DP); setColor(this@DetailArtworkCardView.colors.posterPlaceholder) }
        image.isDuplicateParentStateEnabled = true
        image.foreground = coverRing
        Styler.makeFocusable(this); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        image.clipToOutline = true
        addView(image, LayoutParams(MATCH, dp(156)))
        addView(titleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        addView(subtitleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
        minimumHeight = dp(DetailLayout.posterCardHeight(156, resources.configuration.fontScale))
        FocusDecorator.attach(this, ringVisible)
    }
    /** Always its natural height, like [EpisodeCardView]: a short row squashed the title to a sliver. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

    /**
     * On the cover: a check in the corner once finished, else a slim bar along
     * the bottom for how far in -- the marks a media card carries, so a book
     * you have finished no longer looks like one you have not opened.
     */
    fun marks(fraction: Double, finished: Boolean) {
        coverMarks = when {
            finished -> CoverMarks(colors, 0.0, true, resources.displayMetrics.density)
            fraction > 0 -> CoverMarks(colors, fraction, false, resources.displayMetrics.density)
            else -> null
        }
        updateForeground()
    }

    /** The format mark at the cover's top left (#54): a book that is both an ebook and an audiobook; none clears it. */
    fun formatMark(mark: com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark) {
        formatMark = FormatMark.drawable(context, mark)
        updateForeground()
    }

    /** The marks under the ring, in the one foreground the cover has. */
    private fun updateForeground() {
        val ring = coverRing.takeIf { portraitRing == null }
        val layers = listOfNotNull(coverMarks, formatMark, ring)
        image.foreground = when (layers.size) {
            0 -> null
            1 -> layers[0]
            else -> android.graphics.drawable.LayerDrawable(layers.toTypedArray())
        }
    }

    /**
     * The cover [heightDp] tall. In a row of covers of different shapes, [slotDp] is the tall one's height: a shorter
     * (square) cover sits at its foot so the row's covers line up along their bottom edge and the captions stay on one
     * line (#54).
     */
    fun artworkHeight(heightDp: Int, slotDp: Int = heightDp) {
        image.layoutParams = (image.layoutParams as LinearLayout.LayoutParams).apply { height = dp(heightDp); topMargin = dp(slotDp - heightDp) }
        minimumHeight = dp(DetailLayout.posterCardHeight(slotDp, resources.configuration.fontScale))
    }
    /**
     * A person: a round portrait with the name centred under it. The ring goes
     * round the portrait, not a box round the whole card.
     */
    fun portrait(sizeDp: Int) {
        image.layoutParams = LayoutParams(dp(sizeDp), dp(sizeDp)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        image.background = ThemeGradientDrawable.oval(colors.posterPlaceholder)
        image.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        titleView.gravity = Gravity.CENTER_HORIZONTAL
        subtitleView.gravity = Gravity.CENTER_HORIZONTAL
        // The words' own alignment wins over gravity: centred under the face, not at its start.
        titleView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        subtitleView.textAlignment = View.TEXT_ALIGNMENT_CENTER
        background = null
        portraitRing = ThemeGradientDrawable.oval(Color.TRANSPARENT, dp(3), colors.focusRing)
        // A portrait's ring is round and drawn on focus; the cover's square one goes.
        image.isDuplicateParentStateEnabled = false
        image.foreground = null
        minimumHeight = 0
    }

    fun available(available: Boolean) {
        image.alpha = if (available) 1f else .42f
        image.colorFilter = if (available) null else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        portraitRing?.let { image.foreground = if (gainFocus && ringVisible()) it else null }
        if (gainFocus) post {
            if (hasFocus() && height > 0) {
                // ScrollView normally reveals only the unscaled rectangle. Reserve the
                // same lift here as in the shelf, so the hint bar cannot cover the ring.
                val clearance = dp(DetailLayout.focusClearance((height / resources.displayMetrics.density).toInt()))
                requestRectangleOnScreen(Rect(-clearance, -clearance, width + clearance, height + clearance), true)
            }
        }
    }
}

/**
 * [DetailArtworkCardView.marks]: drawn over the cover, sized from its bounds:
 * the prototype's marks, a white bar on a faint track inside the cover and the
 * tick in the accent, as on posters and stills.
 */
private class CoverMarks(
    private val colors: PocketColors,
    private val fraction: Double,
    private val finished: Boolean,
    private val density: Float
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val tick = FinishedTick.drawable(colors, density)

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (!finished) {
            val inset = 6 * density
            val height = 4 * density
            val top = b.bottom - inset - height
            paint.color = GLASS_TRACK
            canvas.drawRoundRect(b.left + inset, top, b.right - inset, top + height, height / 2, height / 2, paint)
            paint.color = Color.WHITE
            val end = b.left + inset + ((b.width() - 2 * inset) * fraction.coerceIn(0.0, 1.0)).toFloat().coerceAtLeast(height)
            canvas.drawRoundRect(b.left + inset, top, end, top + height, height / 2, height / 2, paint)
            return
        }
        tick.setBounds(b)
        tick.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        /** The track under the bar, as on stills. */
        const val GLASS_TRACK = com.pocketds.hub.ui.glass.GlassProgressBar.TRACK
    }
}
