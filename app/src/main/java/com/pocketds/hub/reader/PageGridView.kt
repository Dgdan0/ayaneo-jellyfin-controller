package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * The Pages grid (#16, C4): every page of the issue as the hub's thumbnail,
 * seven across, over the reader. The D-pad moves the cursor (L2 and R2 a
 * screenful), Ⓐ opens the page under it and Ⓑ closes; a tap opens a page, and
 * a row at the foot says so. The cursor is drawn here rather than by Android's
 * focus, which a recycled list hands to whatever is nearest. Only the cells on
 * screen ask for thumbnails.
 */
class PageGridView(
    context: Context,
    private val colors: PocketColors,
    private val loader: ImageLoader,
    private val ringVisible: () -> Boolean
) : FrameLayout(context) {
    var onPick: (Int) -> Unit = {}
    var onClosed: () -> Unit = {}
    val isOpen: Boolean get() = visibility == VISIBLE
    /** The page under the cursor. */
    var selected = 0
        private set

    private var count = 0
    private var current = 0
    /** A thumbnail's width: the grid's width shared by its columns, less their gaps. */
    private var cellPx = 0
    private var thumb: (Int) -> String = { "" }
    private val glass = Theme.isGlass(context)
    private val title = TextView(context).apply {
        Type.apply(this, Type.Role.HEADING, 19f)
        setTextColor(Color.WHITE)
    }
    private val subtitle = TextView(context).apply {
        textSize = 12f
        setTextColor(ReaderBars.SOFT_TEXT)
    }
    private val grid = RecyclerView(context).apply {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // Clipped at its top, so the row above the first shown leaves nothing under the heading.
        clipToPadding = true
        setPadding(0, dp(4), 0, dp(16))
        itemAnimator = null
    }
    private val layout = GridLayoutManager(context, 7)
    private val adapter = Cells()

    init {
        visibility = GONE
        isClickable = true
        if (glass) background = GlassPanelDrawable(GlassColors.sheet(com.pocketds.hub.ui.glass.GlassPage.palette(context)), 0f)
        else setBackgroundColor(0xF20F1115.toInt())
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(16), 0)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(title)
        words.addView(subtitle)
        header.addView(words, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(OverlayButtons.round(context, colors.focusRing, AppIcon.CLOSE, "Close pages") { hide() },
            LinearLayout.LayoutParams(dp(44), dp(44)))
        column.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        grid.layoutManager = layout
        grid.adapter = adapter
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        // What the keys do here: the grid covers the reader's own row.
        addView(ReaderKeys.row(context, colors) { onPad(it) }.apply { setHints(PageGrid.HINTS) },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(ReaderKeys.ROW_DP), Gravity.BOTTOM))
        (column.layoutParams as LayoutParams).bottomMargin = dp(ReaderKeys.ROW_DP)
    }

    /** Opens on [currentPage] of [pageCount], each page's thumbnail from [thumbnail]. */
    fun show(heading: String, detail: String, pageCount: Int, currentPage: Int, thumbnail: (Int) -> String) {
        title.text = heading
        subtitle.text = detail
        count = pageCount.coerceAtLeast(0)
        current = currentPage.coerceIn(0, (count - 1).coerceAtLeast(0))
        selected = current
        thumb = thumbnail
        // Measured from the reader, which is laid out, rather than from the grid, which is not yet.
        val usable = ((parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - dp(36)
        layout.spanCount = PageGrid.columns((usable / resources.displayMetrics.density).toInt())
        cellPx = (usable / layout.spanCount - dp(10)).coerceAtLeast(dp(40))
        adapter.notifyDataSetChanged()
        visibility = VISIBLE
        bringToFront()
        grid.post { reveal(selected) }
    }

    fun hide() {
        if (!isOpen) return
        visibility = GONE
        onClosed()
    }

    /** Every key is the grid's while it is open. */
    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        when (action) {
            is PadAction.Step -> select(PageGrid.move(selected, action.direction, layout.spanCount, count))
            is PadAction.Page -> select(PageGrid.page(selected, if (action.direction == Direction.UP) -1 else 1,
                layout.spanCount, visibleRows(), count))
            PadAction.Activate -> pick(selected)
            PadAction.Back, PadAction.Refresh -> hide()
            else -> Unit
        }
        return true
    }

    private fun select(index: Int) {
        if (index == selected) return
        val before = selected
        selected = index
        adapter.notifyItemChanged(before)
        adapter.notifyItemChanged(index)
        reveal(index)
    }

    private fun reveal(index: Int) {
        val first = layout.findFirstCompletelyVisibleItemPosition()
        val last = layout.findLastCompletelyVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || index < first || index > last) {
            // Its row at the top, so the rows below it show what comes next.
            layout.scrollToPositionWithOffset(index - index % layout.spanCount, 0)
        }
    }

    private fun visibleRows(): Int {
        val cell = grid.getChildAt(0)?.height?.takeIf { it > 0 } ?: return 2
        return (grid.height / cell).coerceAtLeast(1)
    }

    private fun pick(index: Int) {
        if (index !in 0 until count) return
        visibility = GONE
        onPick(index)
    }

    private inner class Cells : RecyclerView.Adapter<Cell>() {
        override fun getItemCount() = count

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell = Cell(parent)

        override fun onBindViewHolder(holder: Cell, position: Int) = holder.bind(position)
    }

    private inner class Cell(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(5), dp(5), dp(5), dp(4))
    }) {
        private val frame = FrameLayout(parent.context)
        private val image = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val number = TextView(parent.context).apply {
            textSize = 11.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }

        init {
            val root = itemView as LinearLayout
            frame.background = ThemeGradientDrawable.rounded(Styler.dp(parent.context, CORNER_DP), 0xFF15181E.toInt())
            frame.clipToOutline = true
            frame.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, Styler.dp(view.context, CORNER_DP))
            }
            frame.addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            root.addView(frame)
            root.addView(number, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            itemView.isClickable = true
        }

        fun bind(position: Int) {
            frame.layoutParams = LinearLayout.LayoutParams(cellPx, (cellPx * PageGrid.THUMB_ASPECT).toInt())
            number.text = if (position == current) "${position + 1} · Reading" else "${position + 1}"
            number.setTextColor(if (position == current) colors.accent else ReaderBars.SOFT_TEXT)
            val chosen = position == selected
            // The cursor: the Glass ring, white and standing a little outside the page.
            frame.foreground = if (chosen && ringVisible()) ThemeGradientDrawable.rounded(Styler.dp(frame.context, CORNER_DP),
                Color.TRANSPARENT, dp(3), Color.WHITE) else null
            itemView.contentDescription = "Page ${position + 1}" + if (position == current) ", the page you are on" else ""
            itemView.setOnClickListener { pick(position) }
            Artwork.bind(image, loader, thumb(position), placeholderColor = 0xFF1B1F27.toInt())
        }
    }

    private fun dp(value: Int): Int = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val CORNER_DP = 7f
    }
}
