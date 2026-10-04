package com.pocketds.hub.screens.library

import com.pocketds.hub.input.Direction
import com.pocketds.hub.state.ContentMode
import kotlin.math.floor

/**
 * The order libraries are shown in (#15). The hub decides it, per Jellyfin
 * profile and side, and every device shows the same: the person's own order
 * when they arranged one, else A to Z. The app never sorts a list of
 * libraries itself; it moves one and sends the whole order back
 * (`PUT /v1/library/order`), and an empty one goes back to A to Z. Pure, so
 * tested.
 */
object LibraryOrder {
    const val NAME = "name"
    const val CUSTOM = "custom"

    /** The side as the hub names it. */
    fun side(mode: ContentMode): String = if (mode == ContentMode.BOOKS) "books" else "media"

    fun isCustom(order: String): Boolean = order == CUSTOM

    /** [ids] with the one at [from] taken out and put back at [to]; the rest keep their order. */
    fun <T> move(ids: List<T>, from: Int, to: Int): List<T> {
        if (from !in ids.indices) return ids
        val target = to.coerceIn(0, ids.lastIndex)
        if (target == from) return ids
        val next = ids.toMutableList()
        val item = next.removeAt(from)
        next.add(target, item)
        return next
    }

    /**
     * Where a lifted tile at [index] goes on a press: one place along (the end
     * of a row carries on to the next), or a whole row up or down. Never past
     * the ends; down over a short last row lands on its last place.
     */
    fun step(index: Int, count: Int, columns: Int, direction: Direction): Int {
        if (count <= 0) return index
        val target = when (direction) {
            Direction.LEFT -> index - 1
            Direction.RIGHT -> index + 1
            Direction.UP -> if (index - columns >= 0) index - columns else index
            Direction.DOWN -> when {
                index + columns < count -> index + columns
                // A row below, shorter than this one: its last place.
                (index / columns) < (count - 1) / columns -> count - 1
                else -> index
            }
        }
        return target.coerceIn(0, count - 1)
    }

    /**
     * The place under ([x], [y]) in a grid of [columns] tiles [width] by
     * [height] with [gap] between them, for a tile being dragged. Outside the
     * grid it is the nearest place there is.
     */
    fun slotAt(x: Float, y: Float, width: Float, height: Float, gap: Float, columns: Int, count: Int): Int {
        if (count <= 0) return 0
        val rows = (count + columns - 1) / columns
        val column = floor((x + gap / 2) / (width + gap)).toInt().coerceIn(0, columns - 1)
        val row = floor((y + gap / 2) / (height + gap)).toInt().coerceIn(0, rows - 1)
        return (row * columns + column).coerceIn(0, count - 1)
    }
}

/**
 * One arranging of a Library root: which library is lifted, the order shown
 * and the order the hub last saved, so a failed save goes back to it. Pure.
 */
class LibraryArrangeSession(ids: List<String>) {
    var ids: List<String> = ids
        private set
    private var saved: List<String> = ids
    /** Where the lifted library is now, or -1. */
    var lifted: Int = -1
        private set
    private var liftedFrom: List<String> = ids

    val isLifted: Boolean get() = lifted >= 0

    fun pickUp(index: Int): Boolean {
        if (index !in ids.indices) return false
        lifted = index
        liftedFrom = ids
        return true
    }

    /** Moves the lifted library one press [direction]; its new place. */
    fun step(direction: Direction, columns: Int): Int {
        if (!isLifted) return -1
        val target = LibraryOrder.step(lifted, ids.size, columns, direction)
        if (target != lifted) {
            ids = LibraryOrder.move(ids, lifted, target)
            lifted = target
        }
        return lifted
    }

    /** A drag over another place: the lifted library goes there. True when it moved. */
    fun moveLiftedTo(index: Int): Boolean {
        if (!isLifted) return false
        val target = index.coerceIn(0, ids.lastIndex)
        if (target == lifted) return false
        ids = LibraryOrder.move(ids, lifted, target)
        lifted = target
        return true
    }

    /** Puts the lifted library down: the order to save, or null when nothing changed. */
    fun drop(): List<String>? {
        if (!isLifted) return null
        lifted = -1
        return ids.takeIf { it != liftedFrom && it != saved }
    }

    /** The hub has this order now. */
    fun saved(order: List<String>) {
        saved = order
    }

    /** A save failed: back to the order the hub has. */
    fun rollback(): List<String> {
        lifted = -1
        ids = saved
        return ids
    }

    /** The hub's order replaces the session's (a reload, back to A to Z). */
    fun reset(order: List<String>) {
        lifted = -1
        ids = order
        saved = order
        liftedFrom = order
    }
}
