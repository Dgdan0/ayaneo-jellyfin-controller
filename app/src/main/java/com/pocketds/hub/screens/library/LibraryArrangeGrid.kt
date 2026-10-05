package com.pocketds.hub.screens.library

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import kotlin.math.abs

/**
 * Libraries that can be put in order (#15): the tiles of a Glass Library root,
 * three across, or the rows of Settings › Libraries, one under another. Each
 * has a place, its index in the order it is given, and a new order slides
 * every one to its new place. The grip (two columns of dots) says that one can
 * be held and moved, the same mark in both places: on a row it is always at
 * the end, on a tile it shows in the corner while arranging. The lifted one
 * ([lift]) stands still and above the rest, its grip lit.
 *
 * While [arranging], tiles wiggle gently, neighbours swinging opposite ways,
 * each from its own point in the swing; a fixed tile (reading lists), which
 * cannot move, stays still and has no grip.
 *
 * A pointer drags: a row by its grip at once; a tile after a hold, or at once
 * while arranging. The place under the dragged one's middle goes to
 * [listener], which reorders; letting go drops it. The grid only moves
 * pictures; the order, and saving it, belong to its screen and
 * [LibraryOrderEditor].
 *
 * Each one sits in a cell of its own with its grip, and the wiggle, the lift
 * and the sliding are the cell's: the tile's own scale is its focus
 * (FocusDecorator's spring), which would fight a lift drawn on the same view.
 * The lifted one is also activated, so a row can draw itself raised.
 */
class LibraryArrangeGrid(
    context: Context,
    private val colors: PocketColors,
    private val style: Style
) : ViewGroup(context) {
    enum class Style(val columns: Int, val gapDp: Float, val liftScale: Float, val liftDp: Float) {
        /** A Library root: tiles three across, which wiggle while arranging and are held to drag. */
        TILES(3, 12f, 1.05f, 14f),
        /** Settings › Libraries: rows one under another, dragged by the grip at their end. */
        ROWS(1, 2f, 1.01f, 8f)
    }

    interface Listener {
        /** A pointer picked up the one at [place]. */
        fun onPointerLift(place: Int)
        /** The one under the pointer is over [place] now. */
        fun onPointerOver(place: Int)
        /** The pointer let go of it. */
        fun onPointerDrop()
    }

    var listener: Listener? = null
    val columns: Int = style.columns
    private val gap = Styler.dpInt(context, style.gapDp)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    /** The cells in their places: the ones that can be arranged, then the fixed ones. */
    private val cells = mutableListOf<Cell>()
    private var movable = 0
    private var cellWidth = 0
    private var cellHeight = 0
    private var lifted: Cell? = null

    /** Tiles: while true they wiggle and show their grips; the lifted one stands still. */
    var arranging = false
        set(value) {
            if (field == value) return
            field = value
            cells.forEachIndexed { place, cell -> cell.arrange(value && place < movable, place) }
        }

    /** Where each sliding cell started, as an offset from its place. */
    private val slideFrom = HashMap<Cell, FloatArray>()
    private var slide: ValueAnimator? = null

    private var downX = 0f
    private var downY = 0f
    private var pressed: Cell? = null
    /** A hold lifted a tile before the gesture was ours: the rest of it is taken from the tile. */
    private var held = false
    private var dragging: Cell? = null
    private var grabX = 0f
    private var grabY = 0f
    private var dragLeft = 0f
    private var dragTop = 0f
    private val hold = Runnable { onHold() }

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** The views, keyed by id, in their places; the first [movable] can be arranged. */
    fun setTiles(tiles: List<Pair<String, View>>, movable: Int) {
        removeCallbacks(hold)
        stopSlide()
        cells.forEach { it.still() }
        dragging = null
        held = false
        lifted = null
        removeAllViews()
        cells.clear()
        this.movable = movable.coerceIn(0, tiles.size)
        tiles.forEachIndexed { place, (id, view) -> cells += Cell(id, view, place < this.movable).also { addView(it) } }
        cells.forEachIndexed { place, cell -> cell.arrange(arranging && place < this.movable, place) }
    }

    /** The ids of the ones that can be arranged, in their places. */
    val ids: List<String> get() = cells.take(movable).map { it.id }

    val movableCount: Int get() = movable

    fun tile(place: Int): View? = cells.getOrNull(place)?.tile

    /** The place of the one with focus, or -1. */
    fun focusedPlace(): Int = cells.indexOfFirst { it.hasFocus() }

    /** The arranged ones in the order of [ids], each sliding to its new place. */
    fun order(ids: List<String>, animate: Boolean = true) {
        val arranged = cells.take(movable)
        val byId = arranged.associateBy { it.id }
        val named = ids.distinct().mapNotNull { byId[it] }
        val next = named + arranged.filter { it !in named } + cells.drop(movable)
        if (next == cells) return
        // Where each one is drawn now, so it can slide from there.
        val drawn = cells.associateWith { floatArrayOf(it.left + it.translationX, it.top + it.translationY) }
        cells.clear()
        cells += next
        if (cellWidth == 0) {
            requestLayout()
            return
        }
        val sliding = animate && ValueAnimator.areAnimatorsEnabled()
        cells.forEachIndexed { place, cell ->
            place(cell, place)
            if (cell === dragging) return@forEachIndexed
            val from = drawn.getValue(cell)
            val dx = from[0] - cell.left
            val dy = from[1] - cell.top
            if (sliding && (dx != 0f || dy != 0f)) {
                cell.translationX = dx
                cell.translationY = dy
                slideFrom[cell] = floatArrayOf(dx, dy)
            } else {
                slideFrom.remove(cell)
                cell.translationX = 0f
                cell.translationY = 0f
            }
        }
        startSlide()
    }

    /** The one at [place] is lifted, its grip lit; -1 puts it down. */
    fun lift(place: Int) {
        val next = cells.getOrNull(place)
        if (next === lifted) return
        lifted?.let { it.lift(false, cells.indexOf(it)) }
        lifted = next
        next?.lift(true, place)
    }

    /** The one at [place] is scrolled into sight, after a move took it to a row out of view. */
    fun reveal(place: Int) {
        val cell = cells.getOrNull(place) ?: return
        cell.post { cell.requestRectangleOnScreen(Rect(0, 0, cell.width, cell.height), false) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellWidth = ((width - paddingLeft - paddingRight - gap * (columns - 1)) / columns).coerceAtLeast(0)
        var tallest = 0
        val across = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        cells.forEach {
            it.measure(across, any)
            tallest = maxOf(tallest, it.measuredHeight)
        }
        cellHeight = tallest
        val rows = (cells.size + columns - 1) / columns
        val height = paddingTop + paddingBottom + if (rows == 0) 0 else rows * cellHeight + (rows - 1) * gap
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        cells.forEachIndexed { place, cell -> place(cell, place) }
    }

    private fun place(cell: Cell, place: Int) {
        val left = paddingLeft + place % columns * (cellWidth + gap)
        val top = paddingTop + place / columns * (cellHeight + gap)
        // Exactly the cell's size, so a row's grip stays centred on it.
        cell.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY))
        cell.layout(left, top, left + cellWidth, top + cellHeight)
        if (cell === dragging) {
            cell.translationX = dragLeft - left
            cell.translationY = dragTop - top
        }
    }

    private fun startSlide() {
        stopSlide()
        if (slideFrom.isEmpty()) return
        // Every sliding one goes on from where it is drawn now.
        slideFrom.keys.forEach { cell -> slideFrom[cell] = floatArrayOf(cell.translationX, cell.translationY) }
        slide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SLIDE_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animator ->
                val left = 1f - animator.animatedValue as Float
                slideFrom.forEach { (cell, from) ->
                    if (cell !== dragging) {
                        cell.translationX = from[0] * left
                        cell.translationY = from[1] * left
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (slide !== animation) return
                    slide = null
                    slideFrom.clear()
                }
            })
            start()
        }
    }

    private fun stopSlide() {
        val running = slide
        slide = null
        running?.cancel()
    }

    // ------------------------------------------------------------- pointer

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                held = false
                pressed = cellAt(event.x, event.y)
                val place = pressed?.let(cells::indexOf) ?: -1
                if (style == Style.ROWS) {
                    // A row moves by its grip; anywhere else on it is the row's own (focus, a scroll).
                    if (place in 0 until movable && pressed?.onGrip(event.x, event.y) == true) {
                        startDrag(place)
                        return true
                    }
                    return false
                }
                if (arranging) {
                    // While arranging a touch on a tile is there to move it, never to open it.
                    if (place in 0 until movable) {
                        startDrag(place)
                        return true
                    }
                    // A fixed tile is not opened either; the page may still scroll.
                    return pressed != null
                }
                if (place in 0 until movable && listener != null) {
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // The hold lifted the tile: from here the gesture is the drag's, and the tile gets a cancel, not a click.
                if (held) return true
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) removeCallbacks(hold)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (held) {
                    // Held and let go without moving: put down where it was, and the tile is not opened.
                    endDrag()
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dragging == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> follow(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun onHold() {
        val place = pressed?.let(cells::indexOf) ?: return
        if (place !in 0 until movable) return
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startDrag(place)
    }

    private fun startDrag(place: Int) {
        val cell = cells[place]
        // The page must not take the drag for a scroll.
        parent?.requestDisallowInterceptTouchEvent(true)
        dragging = cell
        slideFrom.remove(cell)
        dragLeft = cell.left + cell.translationX
        dragTop = cell.top + cell.translationY
        grabX = downX - dragLeft
        grabY = downY - dragTop
        listener?.onPointerLift(place)
    }

    private fun follow(x: Float, y: Float) {
        val cell = dragging ?: return
        dragLeft = (x - grabX).coerceIn(0f, (width - cellWidth).coerceAtLeast(0).toFloat())
        dragTop = (y - grabY).coerceIn(0f, (height - cellHeight).coerceAtLeast(0).toFloat())
        cell.translationX = dragLeft - cell.left
        cell.translationY = dragTop - cell.top
        val over = LibraryOrder.slotAt(dragLeft + cellWidth / 2f - paddingLeft, dragTop + cellHeight / 2f - paddingTop,
            cellWidth.toFloat(), cellHeight.toFloat(), gap.toFloat(), columns, movable)
        if (over != cells.indexOf(cell)) listener?.onPointerOver(over)
    }

    private fun endDrag() {
        held = false
        val cell = dragging ?: return
        dragging = null
        // From under the finger into its place.
        if (cell.translationX != 0f || cell.translationY != 0f) {
            slideFrom[cell] = floatArrayOf(cell.translationX, cell.translationY)
            startSlide()
        }
        listener?.onPointerDrop()
    }

    private fun cellAt(x: Float, y: Float): Cell? =
        cells.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        super.onDetachedFromWindow()
    }

    /** One place in the grid, holding its view and, for one that can move, the grip. */
    private inner class Cell(val id: String, val tile: View, canMove: Boolean) : FrameLayout(context) {
        private var swing: ObjectAnimator? = null
        private val grip: TextView? = if (canMove) gripMark() else null

        init {
            clipChildren = false
            clipToPadding = false
            addView(tile, LayoutParams(LayoutParams.MATCH_PARENT, if (style == Style.ROWS) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT))
            grip?.let { mark ->
                val inset = Styler.dpInt(context, if (style == Style.ROWS) ROW_GRIP_END_DP else TILE_GRIP_INSET_DP)
                addView(mark, if (style == Style.ROWS) {
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = inset }
                } else {
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { topMargin = inset; marginStart = inset }
                })
                // A row's grip always shows; a tile's only while arranging.
                mark.visibility = if (style == Style.ROWS) View.VISIBLE else View.GONE
            }
        }

        /** Whether ([x], [y]), in the grid's coordinates, is on the grip, with a little room round it. */
        fun onGrip(x: Float, y: Float): Boolean {
            val mark = grip?.takeIf { it.visibility == View.VISIBLE } ?: return false
            val room = Styler.dp(context, GRIP_ROOM_DP)
            val gx = x - left
            val gy = y - top
            return gx >= mark.left - room && gx <= mark.right + room && gy >= mark.top - room && gy <= mark.bottom + room
        }

        /** Tiles: arranging shows the grip and starts the wiggle. */
        fun arrange(on: Boolean, place: Int) {
            if (style == Style.TILES) grip?.visibility = if (on) View.VISIBLE else View.GONE
            wiggle(on && this !== lifted && style == Style.TILES, place)
        }

        private fun wiggle(on: Boolean, place: Int) {
            swing?.cancel()
            swing = null
            if (!on || !ValueAnimator.areAnimatorsEnabled()) {
                if (rotation != 0f) swing = ObjectAnimator.ofFloat(this, View.ROTATION, rotation, 0f).apply {
                    duration = SETTLE_MS
                    start()
                }
                return
            }
            // Neighbours swing opposite ways, and each starts at its own point in
            // the swing, so the tiles never move in step.
            val degrees = WIGGLE_DEGREES * if (place % 2 == 0) 1f else -1f
            swing = ObjectAnimator.ofFloat(this, View.ROTATION, -degrees, degrees).apply {
                duration = WIGGLE_MS + place % 3 * WIGGLE_SPREAD_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
                currentPlayTime = place * STAGGER_MS % duration
            }
        }

        fun lift(on: Boolean, place: Int) {
            wiggle(!on && arranging && style == Style.TILES, place)
            grip?.let { lightGrip(it, on) }
            tile.isActivated = on
            animate().scaleX(if (on) style.liftScale else 1f).scaleY(if (on) style.liftScale else 1f)
                .translationZ(if (on) Styler.dp(context, style.liftDp) else 0f)
                .setDuration(LIFT_MS).start()
        }

        /** Every motion stopped, at rest in its place. */
        fun still() {
            swing?.cancel()
            swing = null
            animate().cancel()
            rotation = 0f
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
            translationZ = 0f
        }
    }

    /** The grip: a round glass mark with two columns of dots, lit while its one is lifted. */
    private fun gripMark(): TextView = TextView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        // Above its tile: a focused tile is lifted 8dp (FocusDecorator) and hid the grip under it.
        translationZ = Styler.dp(context, GRIP_LIFT_DP)
        PillButton.control(this, colors, AppIcon.GRIP, round = true)
    }

    private fun lightGrip(mark: TextView, on: Boolean) = PillButton.setPrimary(mark, colors, on)

    companion object {
        /** A gentle wiggle: a degree either way, each swing a little over a tenth of a second. */
        const val WIGGLE_DEGREES = 1f
        const val WIGGLE_MS = 130L
        const val WIGGLE_SPREAD_MS = 14L
        const val STAGGER_MS = 47L
        const val SETTLE_MS = 120L
        const val SLIDE_MS = 220L
        const val LIFT_MS = 160L
        /** Where the grip sits: inside a tile's top corner, at a row's end. */
        const val TILE_GRIP_INSET_DP = 8f
        const val ROW_GRIP_END_DP = 4f
        /** Over a focused tile's own lift. */
        const val GRIP_LIFT_DP = 10f
        /** Room round a row's grip that still takes hold of it. */
        const val GRIP_ROOM_DP = 6f
        /** How much room a row leaves at its end for the grip: its ring and the space beside it. */
        const val ROW_GRIP_ROOM_DP = PillButton.CONTROL_DP + 2 * PillButton.RING_DP + 8f
    }
}
