package com.pocketds.hub.ui

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.Direction

/**
 * Left and right inside a horizontal row, moved by adapter position rather than
 * by asking the framework.
 *
 * `View.focusSearch` is the wrong tool for this and it took a while to see why.
 * `LinearLayoutManager.onFocusSearchFailed` **scrolls** while hunting for a
 * candidate; that scroll detaches the card currently holding focus; and Android
 * then hands focus to the first focusable view in the window. Measured on the
 * device: running along the trending row silently dumped the selection on the
 * "Discover" tab, and once the tabs were made non-focusable it dumped it on the
 * search box instead. No move was ever *accepted* — the guard in `moveFocus`
 * refused every candidate — the selection simply fell out of the row sideways.
 *
 * Stepping by position sidesteps all of it: the next card either exists, in
 * which case it takes focus, or it does not, in which case nothing happens and
 * the cursor waits where it is. That last part is the behaviour you want while
 * the next page is still loading — far better than leaping back to the start of
 * the row.
 */
object StripNav {

    private const val TAG_PENDING = -0x7ffffff3
    private const val TAG_INSTALLED = -0x7ffffff4

    /**
     * @return true if the press belongs to a horizontal row, whether or not
     *   anything actually moved. Returning true for a refused move is the
     *   point: it stops the caller falling back to a framework focus search.
     */
    fun step(from: View, direction: Direction): Boolean {
        if (direction != Direction.LEFT && direction != Direction.RIGHT) return false
        val list = from.parent as? RecyclerView ?: return false
        val manager = list.layoutManager as? LinearLayoutManager ?: return false
        if (manager.orientation != RecyclerView.HORIZONTAL) return false

        val position = list.getChildAdapterPosition(from)
        if (position == RecyclerView.NO_POSITION) return false
        val count = list.adapter?.itemCount ?: 0
        val target = if (direction == Direction.RIGHT) position + 1 else position - 1

        // Off either end. Consumed and ignored: rows are left and entered with
        // up and down.
        if (target < 0 || target >= count) return true

        val holder = list.findViewHolderForAdapterPosition(target)
        if (holder != null) {
            holder.itemView.requestFocus()
            return true
        }

        // Not laid out yet. Scroll it in and take focus the moment it attaches,
        // because a posted lookup can still run before layout finishes.
        install(list)
        list.setTag(TAG_PENDING, target)
        manager.scrollToPosition(target)
        return true
    }

    private fun install(list: RecyclerView) {
        if (list.getTag(TAG_INSTALLED) == true) return
        list.setTag(TAG_INSTALLED, true)
        list.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val pending = list.getTag(TAG_PENDING) as? Int ?: return
                    if (list.getChildAdapterPosition(view) != pending) return
                    list.setTag(TAG_PENDING, null)
                    view.requestFocus()
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
    }
}
