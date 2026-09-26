package com.pocketds.hub.ui

import android.content.Context
import android.widget.GridLayout
import com.pocketds.hub.state.LibraryGridSizing

/** Incomplete rows keep the same poster width as full rows. */
class PosterGridLayout(context: Context) : GridLayout(context) {
    init { alignmentMode = ALIGN_BOUNDS; useDefaultMargins = false; clipChildren = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (available > 0) {
            val columns = LibraryGridSizing.columns(available, 0, resources.displayMetrics.density)
            // Reset explicit specs before reducing the column count on a rail resize.
            for (i in 0 until childCount) (getChildAt(i).layoutParams as LayoutParams).apply {
                columnSpec = spec(UNDEFINED); rowSpec = spec(UNDEFINED)
            }
            columnCount = columns
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                val params = child.layoutParams as LayoutParams
                params.width = (available / columns - params.leftMargin - params.rightMargin).coerceAtLeast(1)
                params.columnSpec = spec(i % columns)
                params.rowSpec = spec(i / columns)
                child.layoutParams = params
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
