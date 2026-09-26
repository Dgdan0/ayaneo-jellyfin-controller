package com.pocketds.hub.ui

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.Direction

/** Logical lanes are independent of adapter rows: a row may contain a feature and a shelf. */
data class ShelfFocusLane(val id: String, val row: Int, val featured: Boolean, val keys: List<String>)

interface ShelfFocusRow {
    val featureFocusView: View
    val posterFocusList: RecyclerView
    val shelfHeadingView: View
}

/** Choose the destination before scrolling, including when it has been recycled. */
class ShelfFocusNavigator {
    private data class Position(val key: String, val index: Int)
    private val memory = mutableMapOf<String, Position>()
    private var generation = 0
    private var pending: String? = null

    fun cancel() { generation++; pending = null }

    fun step(list: RecyclerView, lanes: List<ShelfFocusLane>, direction: Direction, top: View): Boolean {
        if (direction != Direction.UP && direction != Direction.DOWN) return false
        val from = list.findFocus()
        val rowView = from?.let { list.findContainingItemView(it) }
        val row = rowView as? ShelfFocusRow
        val rowIndex = rowView?.let(list::getChildAdapterPosition)
        val current = pending?.let { id -> lanes.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: lanes.indexOfFirst { it.row == rowIndex && it.featured == (row?.featureFocusView?.hasFocus() == true) }
        if (current < 0) return false
        val source = lanes[current]
        if (pending == null && row != null) {
            val index = if (source.featured) 0 else row.posterFocusList.findContainingItemView(from)
                ?.let(row.posterFocusList::getChildAdapterPosition) ?: 0
            source.keys.getOrNull(index)?.let { memory[source.id] = Position(it, index) }
        }
        val next = current + if (direction == Direction.DOWN) 1 else -1
        if (next < 0) { cancel(); top.requestFocus(); return true }
        if (next >= lanes.size) return true
        focus(list, lanes[next])
        return true
    }

    fun focus(list: RecyclerView, lane: ShelfFocusLane, preferredKey: String? = null) {
        preferredKey?.let { key -> lane.keys.indexOf(key).takeIf { it >= 0 }?.let { memory[lane.id] = Position(key, it) } }
        val ticket = ++generation
        pending = lane.id
        val saved = memory[lane.id]
        val index = lane.keys.indexOf(saved?.key).takeIf { it >= 0 }
            ?: (saved?.index ?: 0).coerceIn(0, (lane.keys.size - 1).coerceAtLeast(0))
        list.stopScroll()
        if (list.findViewHolderForAdapterPosition(lane.row) == null) list.scrollToPosition(lane.row)
        fun attempt(remaining: Int) {
            if (ticket != generation) return
            if (!list.isAttachedToWindow || !list.isShown || remaining == 0) { cancel(); return }
            val row = list.findViewHolderForAdapterPosition(lane.row)?.itemView as? ShelfFocusRow
            val target = if (lane.featured) row?.featureFocusView else row?.posterFocusList
                ?.findViewHolderForAdapterPosition(index)?.itemView
            if (target != null && target.isShown && target.requestFocus()) {
                pending = null
                return
            }
            if (row != null && !lane.featured) row.posterFocusList.scrollToPosition(index)
            list.postOnAnimation { attempt(remaining - 1) }
        }
        list.post { attempt(30) }
    }
}
