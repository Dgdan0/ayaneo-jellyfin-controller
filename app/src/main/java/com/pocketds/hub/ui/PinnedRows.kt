package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A vertical list of rows whose focused row always sits at the top, heading
 * and all, the way a TV home screen moves.
 *
 * RecyclerView's own reveal scrolls only as far as the focused card needs, so
 * each row came to rest somewhere different: the first under the hero, the
 * second lower down with a faded strip of the first still showing above it.
 * The space above the rows seemed to grow and shrink as you went down.
 *
 * Call [pinFocusedRows] on the list. It turns off clipping to padding so the
 * room it adds under the last row can still show the row below peeking in;
 * put the list in a container that clips if rows must not draw above it.
 */
class PinnedRowsLayoutManager(context: Context) : LinearLayoutManager(context) {
    /**
     * A row with more than one part, such as Discover's first row with its
     * featured card above the label: which line of it rests at the top for
     * focus at [focused] (in the row's own coordinates). 0 is the row's top.
     */
    interface Anchor {
        fun pinOffset(focused: Rect): Int
    }

    /**
     * RecyclerView asks this whenever focus lands inside one of its rows
     * ([RecyclerView.requestChildFocus]); [child] is the row, not the card.
     */
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        val dy = getDecoratedTop(child) + ((child as? Anchor)?.pinOffset(rect) ?: 0) - paddingTop
        if (dy == 0) return false
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }
}

/**
 * Makes this list keep its focused row at the top, and leaves enough room
 * under the last row for it to get there too. [shortestRowDp] is the room
 * assumed before the last row has been laid out; once it has, the room is
 * exactly what that row needs, so a touch scroll cannot empty the list.
 */
fun RecyclerView.pinFocusedRows(shortestRowDp: Float) {
    layoutManager = PinnedRowsLayoutManager(context)
    clipToPadding = false
    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val last = (adapter?.itemCount ?: 0) - 1
        val lastRow = if (last >= 0) findViewHolderForAdapterPosition(last)?.itemView else null
        val rowHeight = lastRow?.height?.takeIf { it > 0 } ?: Styler.dpInt(context, shortestRowDp)
        val room = (height - paddingTop - rowHeight).coerceAtLeast(0)
        if (room != paddingBottom) post { setPadding(paddingLeft, paddingTop, paddingRight, room) }
    }
}
