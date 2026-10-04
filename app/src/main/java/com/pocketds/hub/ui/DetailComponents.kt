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
import android.widget.ProgressBar
import com.pocketds.hub.ui.ProgressLine.showFraction
import android.widget.ScrollView
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
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
     * Glass: a round toggle of the page's glass beside Play -- watched,
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
 * [glass] is a title page in Glass (GLASS_PLAN.md): the backdrop across the
 * top of the page, fading into it through a mask rather than into a colour,
 * and running on under the tabs below; the words in the prototype's type at
 * its left. A styling switch only: what the header holds is the same.
 *
 * A Glass book page sets [book] (the prototype's `.bhead`): no backdrop, the
 * cover at full size at the left (square for an audiobook, [squareCover]) or a
 * series' fan in its place ([replacePoster]), and the words beside its foot.
 */
class DetailHeaderView(context: Context, private val colors: PocketColors, ringVisible: () -> Boolean,
                       private val glass: Boolean = false) : FrameLayout(context) {
    val landscape: ImageView = (if (glass) com.pocketds.hub.ui.glass.FadedImageView(context).apply {
        stops = com.pocketds.hub.ui.glass.FadedImageView.TITLE
        shade = com.pocketds.hub.ui.glass.FadedImageView.TITLE_SHADE
    } else ImageView(context)).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    /** Glass: a line over the title, "NOT IN YOUR LIBRARY" on a title you can request. */
    val eyebrowView = label(context, 10.5f, GLASS_EYEBROW).apply {
        typeRole(Type.Role.EYEBROW, 10.5f); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; visibility = GONE
    }
    val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 27f, colors.primaryText).apply { typeRole(Type.Role.HERO); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val subtitleView = label(context, 12f, colors.mutedText).apply { visibility = GONE }
    /** Links under the title: a book's author and series. */
    val links = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; visibility = GONE }
    /** How far through, as a bar and words: "49% · page 363 of 735". */
    val progressBar = ProgressLine.create(context, colors)
    /** Glass: the prototype's bar (`.prog .b`), 4dp in the accent on a faint track. */
    private val glassProgress = com.pocketds.hub.ui.glass.GlassProgressBar(context, colors.accent, com.pocketds.hub.ui.glass.GlassColors.TRACK)
    val progressLabel = label(context, 11f, colors.mutedText)
    val progressRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
        addView(progressBar, LinearLayout.LayoutParams(Styler.dpInt(context, 220f), Styler.dpInt(context, 6f)))
        addView(progressLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Styler.dpInt(context, 10f) })
    }
    /**
     * The facts line. Read left to right part by part, so "11 min" after a
     * Hebrew episode title stays "11 min"; screens join it with [Bidi.join].
     */
    val metadataView = label(context, 12f, colors.mutedText).apply {
        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
        textDirection = TEXT_DIRECTION_LTR; textAlignment = TEXT_ALIGNMENT_VIEW_START
    }
    /** Glass: under the facts line, before the overview: the request page's pipeline. */
    val underFacts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; visibility = GONE }
    val formatStatus = ReadingFormatStatusView(context, colors).apply { visibility=GONE }
    val stateView = label(context, 11f, colors.accent).apply { visibility = GONE }
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    val overview = DetailOverviewView(context, colors, ringVisible)
    val continuation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    // Lets the action row reach a ring's width left of the words (below).
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; clipChildren = false }
    private val masks = FrameLayout(context)
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
    var compact = false
    /**
     * Room above the words for a see-through tab bar, on a page that draws
     * under it ([com.pocketds.hub.nav.Screen.drawsUnderTopBar]).
     */
    var topInsetDp = 0
        set(value) { field = value; layoutKey = ""; requestLayout() }
    /** Glass: a book's page, its cover beside the words and no backdrop. */
    var book = false
        set(value) { field = value; layoutKey = ""; requestLayout() }
    /** Glass book page: an audiobook's cover is square. */
    var squareCover = false
        set(value) { field = value; layoutKey = ""; requestLayout() }
    private var type = ""
    private var hasLandscape = false
    private var hasPoster = false
    private var layoutKey = ""
    private var imageKey: List<Any?> = emptyList()

    init {
        // CENTER_CROP may draw beyond an ImageView when its parent stops clipping.
        // Header controls do not scale, so keep the hero inside its own bounds.
        clipChildren = true
        setBackgroundColor(colors.background)
        addView(landscape, LayoutParams(MATCH, MATCH))
        // Glass: the faded backdrop is taller than the words and runs on under
        // the tabs, as the prototype's does; the scroll view still clips it.
        if (glass) clipChildren = false
        // Solid behind the words, the art clear on the right, and the page
        // colour again at the bottom where the tabs begin.
        masks.addView(View(context).apply { background = ScrimDrawable(colors, ScrimDrawable.Edge.LEFT,
            listOf(0f to 1f, .38f to .9f, .75f to .2f, 1f to .05f)) }, LayoutParams(MATCH, MATCH))
        masks.addView(View(context).apply { background = ScrimDrawable(colors, ScrimDrawable.Edge.BOTTOM,
            listOf(0f to 1f, .12f to 1f, .5f to 0f)) }, LayoutParams(MATCH, MATCH))
        addView(masks, LayoutParams(MATCH, MATCH))
        row.addView(poster, LinearLayout.LayoutParams(dp(92), dp(138)).apply { marginEnd = dp(20) })
        body.addView(eyebrowView, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        body.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(links, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        body.addView(subtitleView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        body.addView(metadataView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        body.addView(underFacts, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        body.addView(formatStatus, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin=dp(4) })
        body.addView(stateView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        body.addView(progressRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        // The overview is read before acting on it, so it sits above the buttons.
        body.addView(overview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        body.addView(actionScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6); marginStart = -dp(PillButton.RING_DP.toInt()) })
        body.addView(continuation, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        row.addView(body, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(row, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        if (glass) {
            masks.visibility = GONE
            titleView.typeface = Type.display(context, 800)
            titleView.textSize = GLASS_TITLE_SP
            titleView.setLineSpacing(0f, .95f)
            metadataView.setTextColor(GLASS_FACTS)
            subtitleView.setTextColor(GLASS_SUBTITLE)
            stateView.textSize = 12f
            stateView.textWeight(700)
            overview.tone(12.5f, GLASS_OVERVIEW)
            body.clipChildren = false
            row.clipChildren = false
            // A series' fan leans past the row's edge; the row must not cut it.
            row.clipToPadding = false
            // An eyebrow is capitals however a screen writes it ("Book 6 · Red Rising").
            eyebrowView.isAllCaps = true
            // The bar in the accent and the words after it, as Books home has them.
            progressRow.removeView(progressBar)
            progressRow.addView(glassProgress, 0, LinearLayout.LayoutParams(dp(GLASS_PROGRESS_DP), dp(com.pocketds.hub.ui.glass.GlassProgressBar.HEIGHT_DP.toInt())))
            progressLabel.textSize = 12f
            progressLabel.setTextColor(GLASS_FACTS)
            (progressLabel.layoutParams as LinearLayout.LayoutParams).marginStart = dp(12)
            poster.scaleType = ImageView.ScaleType.CENTER_CROP
            poster.background = ThemeGradientDrawable.rounded(Styler.dp(context, GLASS_COVER_CORNER_DP), colors.posterPlaceholder)
            poster.clipToOutline = true
            poster.elevation = Styler.dp(context, 14f)
        }
    }

    /** How far through: the bar under the facts, whichever look draws it. */
    fun showProgress(fraction: Double) {
        progressBar.showFraction(fraction)
        glassProgress.fraction = fraction
    }

    private var leading: View? = null

    /** Something in the poster's place: a series page shows a fan of its covers. */
    fun replacePoster(view: View, widthDp: Int, heightDp: Int) {
        leading?.let(row::removeView)
        leading = view
        row.addView(view, 0, LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)).apply { marginEnd = dp(20) })
        requestLayout()
    }

    fun setPresentation(type: String, hasLandscape: Boolean, hasPoster: Boolean) {
        this.type = type; this.hasLandscape = hasLandscape; this.hasPoster = hasPoster
        requestLayout()
    }

    fun bindArtwork(type: String, landscapeData: Any?, posterData: Any?, loader: ImageLoader) {
        val next = listOf(type, landscapeData, posterData)
        if (next == imageKey) return
        imageKey = next
        if (glass && book) {
            // A book's page shows its cover, at the left, and no backdrop.
            setPresentation(type, false, posterData != null)
            DetailStyler.image(poster, posterData, loader)
            return
        }
        if (glass) {
            // Glass has no poster beside the words: the backdrop, else the
            // poster, fills the top of the page and fades into it.
            setPresentation(type, landscapeData != null || posterData != null, false)
            Artwork.bind(landscape, loader, landscapeData ?: posterData, opaque = true)
            return
        }
        setPresentation(type, landscapeData != null, posterData != null)
        DetailStyler.image(poster, posterData, loader)
        loader.enqueue(ImageRequest.Builder(context).data(landscapeData).target(
            onStart = { landscape.setImageDrawable(null) },
            onSuccess = { if (imageKey == next) landscape.setImageDrawable(it) },
            onError = { if (imageKey == next) setPresentation(type, false, posterData != null) }
        ).build())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (glass) return measureGlass(widthMeasureSpec, heightMeasureSpec)
        val widthDp = (MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).toInt()
        val hero = DetailLayout.useHero(type, hasLandscape, widthDp, resources.configuration.fontScale)
        val next = "$hero:$widthDp:$hasPoster:$compact:${leading != null}"
        if (layoutKey != next) {
            layoutKey = next
            landscape.visibility = if (hero) VISIBLE else GONE
            masks.visibility = landscape.visibility
            poster.visibility = if (!hero && hasPoster && leading == null) VISIBLE else GONE
            leading?.visibility = if (!hero) VISIBLE else GONE
            row.setPadding(dp(24), dp(topInsetDp + if (hero) 18 else if(compact) 8 else 16), dp(24), dp(if(compact)8 else 12))
            (metadataView.layoutParams as LinearLayout.LayoutParams).topMargin=dp(if(compact)4 else 7)
            (overview.layoutParams as LinearLayout.LayoutParams).topMargin=dp(if(compact)2 else 6)
            (continuation.layoutParams as LinearLayout.LayoutParams).topMargin=dp(if(compact)6 else 10)
            titleView.textSize = if (hero) 34f else if(compact)24f else 28f
            overview.previewLines(if(compact)1 else 2)
            body.layoutParams = LinearLayout.LayoutParams(if (hero) dp(((widthDp - 48) * .6f).toInt()) else 0, WRAP, if (hero) 0f else 1f)
            minimumHeight = if (hero) dp(topInsetDp + 250) else 0
        }
        actionScroll.visibility = if (actions.childCount > 0 && actions.visibility != GONE) VISIBLE else GONE
        continuation.visibility = if (continuation.childCount > 0) VISIBLE else GONE
        underFacts.visibility = if (underFacts.childCount > 0) VISIBLE else GONE
        // Only text/actions determine height. Measuring a MATCH_PARENT background
        // through FrameLayout first makes its bitmap's intrinsic size grow the page.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, resolveSize(maxOf(minimumHeight, row.measuredHeight), heightMeasureSpec))
        val exactWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        landscape.measure(exactWidth, exactHeight)
        masks.measure(exactWidth, exactHeight)
    }

    /**
     * The prototype's title page: the words at the left from 64dp down, no
     * wider than 560dp, and the backdrop [GLASS_ART_DP] tall however long the
     * words run, so it reaches under the tabs and fades out there.
     */
    private fun measureGlass(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val next = "glass:$width:$book:$squareCover:${leading != null}"
        if (layoutKey != next) {
            layoutKey = next
            if (book) {
                // The prototype's `.bhead`: 8dp under the bar, the cover 112dp
                // wide, the words 18dp beside it, both standing on one line
                // (the pills' ring room lies below them).
                val ring = dp(PillButton.RING_DP.toInt())
                row.gravity = Gravity.BOTTOM
                row.setPadding(dp(GLASS_EDGE_DP), dp(GLASS_BOOK_TOP_DP), dp(GLASS_EDGE_DP), dp(4))
                poster.visibility = if (leading == null) VISIBLE else GONE
                leading?.visibility = VISIBLE
                poster.layoutParams = LinearLayout.LayoutParams(dp(GLASS_COVER_DP),
                    dp(if (squareCover) GLASS_COVER_DP else GLASS_COVER_DP * 3 / 2)).apply {
                    marginEnd = dp(GLASS_BOOK_GAP_DP); bottomMargin = ring
                }
                (leading?.layoutParams as? LinearLayout.LayoutParams)?.let { it.marginEnd = dp(GLASS_BOOK_GAP_DP); it.bottomMargin = ring }
                body.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            } else {
                row.gravity = Gravity.TOP
                poster.visibility = GONE
                leading?.visibility = GONE
                row.setPadding(dp(GLASS_EDGE_DP), dp(GLASS_TOP_DP), dp(GLASS_EDGE_DP), dp(4))
                body.layoutParams = LinearLayout.LayoutParams(minOf(dp(GLASS_WORDS_DP), width - 2 * dp(GLASS_EDGE_DP)), WRAP)
            }
            overview.previewLines(2)
        }
        landscape.visibility = if (hasLandscape && !book) VISIBLE else GONE
        eyebrowView.visibility = if (eyebrowView.text.isNullOrBlank()) GONE else VISIBLE
        underFacts.visibility = if (underFacts.childCount > 0) VISIBLE else GONE
        actionScroll.visibility = if (actions.childCount > 0 && actions.visibility != GONE) VISIBLE else GONE
        continuation.visibility = if (continuation.childCount > 0) VISIBLE else GONE
        row.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(width, resolveSize(row.measuredHeight, heightMeasureSpec))
        landscape.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(GLASS_ART_DP), MeasureSpec.EXACTLY))
        masks.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
    }

    private companion object {
        /** The prototype's Pocket title page (`.dart`, `.dhead`). */
        const val GLASS_ART_DP = 330
        const val GLASS_TOP_DP = 64
        const val GLASS_EDGE_DP = 22
        const val GLASS_WORDS_DP = 560
        const val GLASS_TITLE_SP = 30f
        /** A book's page (`.bhead`): 8dp under the bar, a 112dp cover 18dp from the words, a long bar. */
        const val GLASS_BOOK_TOP_DP = 8
        const val GLASS_COVER_DP = 112
        const val GLASS_COVER_CORNER_DP = 9f
        const val GLASS_BOOK_GAP_DP = 18
        const val GLASS_PROGRESS_DP = 220
        /** The facts in white at 82%, an original title at 60%, the overview at 86%, an eyebrow at 72%. */
        const val GLASS_FACTS = com.pocketds.hub.ui.glass.GlassColors.FACTS
        const val GLASS_SUBTITLE = 0x99FFFFFF.toInt()
        const val GLASS_OVERVIEW = 0xDBFFFFFF.toInt()
        const val GLASS_EYEBROW = com.pocketds.hub.ui.glass.GlassColors.EYEBROW
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

/** Same image, title, progress and focus treatment for online, downloaded and reading continuations. */
class ContinuationCardView(context: Context, colors: PocketColors, private val ringVisible: () -> Boolean, portrait: Boolean = false) : LinearLayout(context) {
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 14f, colors.primaryText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val metadataView = label(context, 11f, colors.mutedText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val progressView = ProgressLine.create(context, colors)
    var onFocused: (() -> Unit)? = null
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(if(portrait)6 else 8), dp(12), dp(if(portrait)6 else 8))
        background = Styler.cardBackground(context, colors, 12f, focusStrokeDp = 2f)
        Styler.makeFocusable(this); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(image, LayoutParams(dp(if (portrait) 32 else 112), dp(if (portrait) 48 else 63)).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(titleView, LayoutParams(MATCH, WRAP))
            addView(metadataView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
            addView(progressView, LayoutParams(MATCH, dp(3)).apply { topMargin = dp(7) })
        }, LayoutParams(0, WRAP, 1f))
        addView(ImageView(context).apply {
            setImageDrawable(MediaActionIconDrawable(context, MediaActionIcon.PLAY, colors.mutedText))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(21), dp(21)).apply { marginStart = dp(12) })
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { view, focused -> if (focused) onFocused?.invoke()
        }
    }
    fun bind(title: String, metadata: String, fraction: Double, completed: Boolean) {
        titleView.text = title; metadataView.text = metadata
        progressView.showFraction(if (completed) 0.0 else fraction)
        contentDescription = "$title, $metadata"
    }
}

/**
 * Artwork has no inset white frame; captions and focus clearance are measured together.
 *
 * On the Glass page it is the prototype's book card: the cover with 11dp
 * corners and the 3dp ring on it, no box round the card, the title on one bold
 * line over a quiet one, and the marks in Glass's colours (a white bar inside
 * the cover, the tick in the accent).
 */
class DetailArtworkCardView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : LinearLayout(context) {
    private var portraitRing: android.graphics.drawable.Drawable? = null
    private val glass = Theme.onGlass(colors)
    /** Glass: the ring on the cover, which takes the card's focused state. */
    private val coverRing = if (glass) Styler.focusOutline(context, colors, ArtworkFrame.GLASS_CORNER_DP, 3f) else null
    private var coverMarks: android.graphics.drawable.Drawable? = null
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 13f, colors.primaryText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val subtitleView = label(context, 11f, colors.mutedText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    init {
        orientation = VERTICAL
        if (glass) {
            setPadding(0, 0, 0, dp(6))
            image.background = ThemeGradientDrawable().apply { cornerRadius = Styler.dp(context, ArtworkFrame.GLASS_CORNER_DP); setColor(this@DetailArtworkCardView.colors.posterPlaceholder) }
            image.isDuplicateParentStateEnabled = true
            image.foreground = coverRing
            titleView.textSize = 12f; titleView.textWeight(700); titleView.maxLines = 1
            subtitleView.textSize = 11f; subtitleView.setTextColor(SettingsCard.GLASS_QUIET); subtitleView.maxLines = 1
        } else {
            setPadding(dp(4), dp(4), dp(4), dp(6))
            background = Styler.cardBackground(context, colors, 8f, Color.TRANSPARENT, 2f)
            image.background = ThemeGradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(this@DetailArtworkCardView.colors.posterPlaceholder) }
        }
        Styler.makeFocusable(this); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        image.clipToOutline = true
        addView(image, LayoutParams(MATCH, dp(156)))
        addView(titleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        addView(subtitleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (glass) 1 else 3) })
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
            finished -> CoverMarks(colors, 0.0, true, resources.displayMetrics.density, glass)
            fraction > 0 -> CoverMarks(colors, fraction, false, resources.displayMetrics.density, glass)
            else -> null
        }
        // Glass: the marks under the ring, in the one foreground the cover has.
        val ring = coverRing?.takeIf { portraitRing == null }
        image.foreground = if (ring == null) coverMarks
            else coverMarks?.let { android.graphics.drawable.LayerDrawable(arrayOf(it, ring)) } ?: ring
    }

    fun artworkHeight(heightDp: Int) {
        image.layoutParams = image.layoutParams.apply { height = dp(heightDp) }
        minimumHeight = dp(DetailLayout.posterCardHeight(heightDp, resources.configuration.fontScale))
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
 * [DetailArtworkCardView.marks]: drawn over the cover, sized from its bounds.
 * [glass]: the prototype's marks, a white bar on a faint track inside the
 * cover and the tick in the accent, as on Glass posters and stills.
 */
private class CoverMarks(
    private val colors: PocketColors,
    private val fraction: Double,
    private val finished: Boolean,
    private val density: Float,
    private val glass: Boolean = false
) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val check = AppIconDrawable(AppIcon.CHECK, if (glass) colors.accentText else SemanticColor.foreground(colors.badgeAvailable))

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (glass && !finished) {
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
        if (finished) {
            val size = 22 * density
            val inset = 6 * density
            val cx = b.right - inset - size / 2
            val cy = b.top + inset + size / 2
            paint.color = if (glass) colors.accent else colors.badgeAvailable
            canvas.drawCircle(cx, cy, size / 2, paint)
            val pad = (5 * density).toInt()
            check.setBounds((cx - size / 2).toInt() + pad, (cy - size / 2).toInt() + pad, (cx + size / 2).toInt() - pad, (cy + size / 2).toInt() - pad)
            check.draw(canvas)
            return
        }
        val height = 3 * density
        paint.color = android.graphics.Color.argb(115, 0, 0, 0)
        canvas.drawRect(b.left.toFloat(), b.bottom - height, b.right.toFloat(), b.bottom.toFloat(), paint)
        paint.color = colors.accent
        canvas.drawRect(b.left.toFloat(), b.bottom - height, b.left + (b.width() * fraction.coerceIn(0.0, 1.0)).toFloat(), b.bottom.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    private companion object {
        /** The track under a Glass bar, as on stills. */
        const val GLASS_TRACK = com.pocketds.hub.ui.glass.GlassProgressBar.TRACK
    }
}
