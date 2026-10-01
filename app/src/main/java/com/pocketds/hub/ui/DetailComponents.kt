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
}

/** One header for media, books and downloaded items. It grows with text instead of clipping it. */
class DetailHeaderView(context: Context, private val colors: PocketColors, ringVisible: () -> Boolean) : FrameLayout(context) {
    val landscape = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val poster = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 27f, colors.primaryText).apply { typeRole(Type.Role.HERO); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val subtitleView = label(context, 12f, colors.mutedText).apply { visibility = GONE }
    val metadataView = label(context, 12f, colors.mutedText).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    val formatStatus = ReadingFormatStatusView(context, colors).apply { visibility=GONE }
    val stateView = label(context, 11f, colors.accent).apply { visibility = GONE }
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    val overview = DetailOverviewView(context, colors, ringVisible)
    val continuation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    private val masks = FrameLayout(context)
    private val actionScroll = FocusHorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false; setPadding(dp(3), dp(4), dp(3), dp(4))
        addView(actions, ViewGroup.LayoutParams(WRAP, WRAP))
    }
    var compact = false
    /**
     * Room above the words for a see-through tab bar, on a page that draws
     * under it ([com.pocketds.hub.nav.Screen.drawsUnderTopBar]).
     */
    var topInsetDp = 0
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
        // Solid behind the words, the art clear on the right, and the page
        // colour again at the bottom where the tabs begin.
        masks.addView(View(context).apply { background = ScrimDrawable(colors, ScrimDrawable.Edge.LEFT,
            listOf(0f to 1f, .38f to .9f, .75f to .2f, 1f to .05f)) }, LayoutParams(MATCH, MATCH))
        masks.addView(View(context).apply { background = ScrimDrawable(colors, ScrimDrawable.Edge.BOTTOM,
            listOf(0f to 1f, .12f to 1f, .5f to 0f)) }, LayoutParams(MATCH, MATCH))
        addView(masks, LayoutParams(MATCH, MATCH))
        row.addView(poster, LinearLayout.LayoutParams(dp(92), dp(138)).apply { marginEnd = dp(20) })
        body.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(subtitleView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        body.addView(metadataView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        body.addView(formatStatus, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin=dp(4) })
        body.addView(stateView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        // The overview is read before acting on it, so it sits above the buttons.
        body.addView(overview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        body.addView(actionScroll, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        body.addView(continuation, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        row.addView(body, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(row, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    fun setPresentation(type: String, hasLandscape: Boolean, hasPoster: Boolean) {
        this.type = type; this.hasLandscape = hasLandscape; this.hasPoster = hasPoster
        requestLayout()
    }

    fun bindArtwork(type: String, landscapeData: Any?, posterData: Any?, loader: ImageLoader) {
        val next = listOf(type, landscapeData, posterData)
        if (next == imageKey) return
        imageKey = next
        setPresentation(type, landscapeData != null, posterData != null)
        DetailStyler.image(poster, posterData, loader)
        loader.enqueue(ImageRequest.Builder(context).data(landscapeData).target(
            onStart = { landscape.setImageDrawable(null) },
            onSuccess = { if (imageKey == next) landscape.setImageDrawable(it) },
            onError = { if (imageKey == next) setPresentation(type, false, posterData != null) }
        ).build())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthDp = (MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density).toInt()
        val hero = DetailLayout.useHero(type, hasLandscape, widthDp, resources.configuration.fontScale)
        val next = "$hero:$widthDp:$hasPoster:$compact"
        if (layoutKey != next) {
            layoutKey = next
            landscape.visibility = if (hero) VISIBLE else GONE
            masks.visibility = landscape.visibility
            poster.visibility = if (!hero && hasPoster) VISIBLE else GONE
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

/** Artwork has no inset white frame; captions and focus clearance are measured together. */
class DetailArtworkCardView(context: Context, private val colors: PocketColors, ringVisible: () -> Boolean) : LinearLayout(context) {
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = label(context, 13f, colors.primaryText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val subtitleView = label(context, 11f, colors.mutedText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    init {
        orientation = VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(6))
        background = Styler.cardBackground(context, colors, 8f, Color.TRANSPARENT, 2f)
        Styler.makeFocusable(this); descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        image.background = ThemeGradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(this@DetailArtworkCardView.colors.posterPlaceholder) }
        image.clipToOutline = true
        addView(image, LayoutParams(MATCH, dp(156)))
        addView(titleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        addView(subtitleView, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        minimumHeight = dp(DetailLayout.posterCardHeight(156, resources.configuration.fontScale))
        FocusDecorator.attach(this, ringVisible)
    }
    /** Always its natural height, like [EpisodeCardView]: a short row squashed the title to a sliver. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

    fun artworkHeight(heightDp: Int) {
        image.layoutParams = image.layoutParams.apply { height = dp(heightDp) }
        minimumHeight = dp(DetailLayout.posterCardHeight(heightDp, resources.configuration.fontScale))
    }
    fun available(available: Boolean) {
        image.alpha = if (available) 1f else .42f
        image.colorFilter = if (available) null else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
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
