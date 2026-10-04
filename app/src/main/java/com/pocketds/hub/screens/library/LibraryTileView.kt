package com.pocketds.hub.screens.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubEndpoints
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import com.pocketds.hub.ui.textWeight

/**
 * A library on a Glass Library root (the prototype's `.lib`), Movies and TV or
 * Books: its own picture, soft and dimmed, with up to three of its posters or
 * covers fanned across the top right ([LibraryTiles.slots]) and a glass label
 * along the foot naming it. 16:10, as wide as the grid makes it. Without a fan
 * the picture shows as it is; a tile with neither (reading lists) shows its
 * icon where the fan would be.
 *
 * Everything in the tile sits in [face], clipped to its rounded corners, so
 * nothing crosses the edge. The focus ring is drawn by the tile itself after
 * the face, a gap outside it, so the posters and the label never cover it: as
 * the face's own foreground it lay under the lifted posters and the label.
 */
class LibraryTileView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : FrameLayout(context) {
    private val face = Face(context, colors)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Styler.dp(context, RING_DP)
        color = colors.focusRing
    }
    private val ringRect = RectF()

    init {
        clipChildren = false
        clipToPadding = false
        setWillNotDraw(false)
        Styler.makeFocusable(this)
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** A Movies and TV library from the hub, its `fan` across the picture. */
    fun bind(view: LibraryView, api: HubApi) {
        face.bind(view.image, LibraryTiles.fan(view), LibraryTiles.kindLabel(view.kind), view.name, null, api)
        contentDescription = "${view.name}, ${LibraryTiles.kindLabel(view.kind)}" +
            if (view.total > 0) ", ${view.total} ${if (view.total == 1) "title" else "titles"}" else ""
    }

    /** A Books library: its cover as a fan of one; reading lists, which have none, show their icon. */
    fun bind(library: ReadingLibrary, api: HubApi) {
        val kind = LibraryTiles.readingKindLabel(library.kind)
        val icon = when {
            library.artwork.isNotBlank() -> null
            library.kind == "comic" || library.kind == "manga" -> AppIcon.COMIC
            library.kind == "reading_list" -> AppIcon.CONTENTS
            else -> AppIcon.BOOK
        }
        // A wide or icon picture is not a cover to fan.
        val cover = library.artwork.takeIf { it.isNotBlank() && library.artworkStyle == "poster" }
        face.bind(library.artwork, listOfNotNull(cover), kind, library.title, icon, api)
        contentDescription = "${library.title}, $kind"
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isFocused || !ringVisible()) return
        // Outside the face by the gap, so the ring stands round the tile as it does round every pill.
        val inset = -(Styler.dp(context, RING_GAP_DP) + ringPaint.strokeWidth / 2f)
        ringRect.set(face.left.toFloat(), face.top.toFloat(), face.right.toFloat(), face.bottom.toFloat())
        ringRect.inset(inset, inset)
        val corner = Styler.dp(context, Face.CORNER_DP) - inset
        canvas.drawRoundRect(ringRect, corner, corner, ringPaint)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private companion object {
        /** The prototype's 3dp ring, standing a little off the tile. */
        const val RING_DP = 3f
        const val RING_GAP_DP = 3f
    }

    /**
     * The tile itself, laid out by hand: the posters' place and lean are
     * fractions of the tile, so the fan keeps its shape at any width.
     */
    private class Face(context: Context, colors: PocketColors) : ViewGroup(context) {
        private val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        /** Behind a fan the picture is blurred once, as the page behind every screen is: a small copy alone showed its pixels. */
        private val soften: android.graphics.RenderEffect? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val radius = (BLUR_SIGMA_DP * resources.displayMetrics.density - 0.5f) / 0.57735f
                android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
            } else null
        private val posters = List(MAX_FAN) { poster() }
        /** Where the fan would be on a tile that has no pictures: its kind's icon. */
        private val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = .5f
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val kind = TextView(context).apply {
            textSize = 9f
            textWeight(800)
            letterSpacing = .12f
            setTextColor(KIND_INK)
            isSingleLine = true
        }
        private val name = TextView(context).apply {
            textSize = 15f
            typeface = Type.display(context, 800)
            setTextColor(android.graphics.Color.WHITE)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        private val label = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            GlassPanelDrawable.attach(this, Styler.dp(context, LABEL_CORNER_DP))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(kind)
            addView(name, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            // Above the posters, which lift to cast their shadows.
            translationZ = Styler.dp(context, POSTER_LIFT_DP + 1f)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        private var fanned = 0

        init {
            val corner = Styler.dp(context, CORNER_DP)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
            // The fan, its shadows and the label stay inside the tile's corners.
            clipToOutline = true
            addView(picture)
            posters.forEach(::addView)
            addView(icon)
            addView(label)
        }

        fun bind(image: String, fan: List<String>, kindLabel: String, title: String, iconOf: AppIcon?, api: HubApi) {
            fanned = fan.size
            // Capitals in the text itself: a single-line view drops an all-caps transformation.
            kind.text = kindLabel.uppercase()
            name.text = title
            icon.visibility = if (iconOf != null) VISIBLE else GONE
            iconOf?.let { icon.setImageDrawable(AppIconDrawable(it, android.graphics.Color.WHITE)) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) picture.setRenderEffect(if (fan.isEmpty()) null else soften)
            if (fan.isEmpty()) {
                // The library's picture is all there is: shown as it is.
                picture.colorFilter = null
                Artwork.bindHub(picture, api, image, opaque = true)
            } else {
                // Behind a fan, only its colours: the smallest copy, filtered across
                // the tile, as the page behind every screen is drawn.
                picture.colorFilter = SOFT
                Artwork.bindHub(picture, api, HubEndpoints.smallest(image.ifBlank { fan.first() }), opaque = true) {
                    size(SOFT_DECODE_PX, SOFT_DECODE_PX)
                    crossfade(false)
                }
            }
            posters.forEachIndexed { index, frame ->
                val image = frame.getChildAt(0) as ImageView
                val path = fan.getOrNull(index)
                frame.visibility = if (path == null) GONE else VISIBLE
                if (path == null) Artwork.bind(image, Artwork.loader(api, context), null)
                else Artwork.bindHub(image, api, path, opaque = true)
            }
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = width * 10 / 16
            picture.measure(exactly(width), exactly(height))
            val posterHeight = (height * STACK_HEIGHT).toInt()
            val posterWidth = posterHeight * 2 / 3
            posters.forEach { it.measure(exactly(posterWidth), exactly(posterHeight)) }
            icon.measure(exactly(posterHeight * 2 / 3), exactly(posterHeight * 2 / 3))
            label.measure(exactly(width - 2 * dp(LABEL_INSET_DP)), MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
            setMeasuredDimension(width, height)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            val height = b - t
            picture.layout(0, 0, width, height)
            val stackHeight = posters[0].measuredHeight
            val stackWidth = stackHeight * STACK_ASPECT
            val stackRight = width - width * STACK_END
            val stackTop = (height * STACK_TOP).toInt()
            LibraryTiles.slots(fanned).forEachIndexed { index, slot ->
                val poster = posters[index]
                val right = (stackRight - slot.fromEnd * stackWidth).toInt()
                poster.layout(right - poster.measuredWidth, stackTop, right, stackTop + stackHeight)
                poster.pivotX = poster.measuredWidth / 2f
                poster.pivotY = stackHeight / 2f
                poster.rotation = slot.degrees
            }
            val iconRight = stackRight.toInt() - (stackWidth.toInt() - icon.measuredWidth) / 2
            icon.layout(iconRight - icon.measuredWidth, stackTop + dp(4), iconRight, stackTop + dp(4) + icon.measuredHeight)
            val inset = dp(LABEL_INSET_DP)
            label.layout(inset, height - inset - label.measuredHeight, width - inset, height - inset)
        }

        /** One poster of the fan: rounded, lifted so it casts a shadow on the one behind. */
        private fun poster(): FrameLayout = FrameLayout(context).apply {
            val corner = Styler.dp(context, POSTER_CORNER_DP)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
            clipToOutline = true
            elevation = Styler.dp(context, POSTER_LIFT_DP)
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = android.graphics.Color.BLACK
                outlineAmbientShadowColor = android.graphics.Color.BLACK
            }
            setBackgroundColor(POSTER_GROUND)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
        private fun dp(value: Number) = Styler.dpInt(context, value.toFloat())

        companion object {
            const val MAX_FAN = 3
            /** The prototype's Pocket tile and label corners. */
            const val CORNER_DP = 16f
            const val LABEL_CORNER_DP = 11f
            const val LABEL_INSET_DP = 8f
            const val POSTER_CORNER_DP = 9f
            const val POSTER_LIFT_DP = 6f
            /** The stack: 66% of the tile's height, 1.3 times as wide, 9% down and 5% in from the right. */
            const val STACK_HEIGHT = .66f
            const val STACK_ASPECT = 1.3f
            const val STACK_TOP = .09f
            const val STACK_END = .05f
            /** How small the picture behind a fan is decoded, and how far it is blurred: only its colours stay. */
            const val SOFT_DECODE_PX = 64
            const val BLUR_SIGMA_DP = 14f
            const val KIND_INK = 0xB8FFFFFF.toInt()
            const val POSTER_GROUND = 0xFF222730.toInt()
            /** The prototype's `saturate(1.2) brightness(.8)` on the picture behind a fan. */
            val SOFT = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(1.2f)
                postConcat(ColorMatrix().apply { setScale(.8f, .8f, .8f, 1f) })
            })
        }
    }
}
