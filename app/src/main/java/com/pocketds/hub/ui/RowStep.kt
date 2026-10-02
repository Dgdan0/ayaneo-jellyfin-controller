package com.pocketds.hub.ui

import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Up and Down through a scrolling page built of rows: the next row that has
 * something to land on, at the item nearest the one you left -- even when that
 * row is scrolled out of sight.
 *
 * The framework's search looks for the nearest focusable in the pressed
 * direction. On Books home an empty "Want to Read" row lay between Recently
 * added and Your series, and Your series was scrolled away above, so the
 * nearest thing up was the tab bar: Up left the page instead of going to the
 * row above.
 */
object RowStep {
    /**
     * [rows] holds one child per row. Within the focused row a search confined
     * to it goes first (a list stacked inside a row); then the rows beyond.
     * False when there is no row further that way, so the caller can let the
     * press leave the page (Up from the first row reaches the tabs).
     */
    fun move(rows: ViewGroup, focused: View?, up: Boolean): Boolean {
        val from = focused ?: return false
        val index = rowIndex(rows, from) ?: return false
        val direction = if (up) View.FOCUS_UP else View.FOCUS_DOWN
        (rows.getChildAt(index) as? ViewGroup)?.let { row ->
            FocusFinder.getInstance().findNextFocus(row, from, direction)?.let { return it.requestFocus(direction) }
        }
        val x = centreX(rows, from)
        var i = index + if (up) -1 else 1
        while (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i)
            if (row.visibility == View.VISIBLE) {
                val candidates = ArrayList<View>().also { row.addFocusables(it, direction) }.filter { it.visibility == View.VISIBLE }
                candidates.minByOrNull { abs(centreX(rows, it) - x) }?.let { return it.requestFocus(direction) }
            }
            i += if (up) -1 else 1
        }
        return false
    }

    private fun rowIndex(rows: ViewGroup, view: View): Int? {
        var child: View = view
        var parent: android.view.ViewParent? = view.parent
        while (parent is View && parent !== rows) {
            child = parent
            parent = child.parent
        }
        return if (parent === rows) rows.indexOfChild(child) else null
    }

    /** Where a view's middle sits across [rows], allowing for strips scrolled sideways. */
    private fun centreX(rows: ViewGroup, view: View): Int {
        val rect = Rect()
        view.getDrawingRect(rect)
        rows.offsetDescendantRectToMyCoords(view, rect)
        return rect.centerX()
    }
}
