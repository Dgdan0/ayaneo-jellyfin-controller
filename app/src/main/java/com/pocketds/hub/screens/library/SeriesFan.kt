package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingSeriesBook
import com.pocketds.hub.model.ReadingWork

/**
 * The Books library's Series view as fans of covers (#54): the owner's rule, decided from mockups with real covers.
 * Pure, so each part of it is tested: which books stand in the fan, which is lit, how each slot leans and darkens,
 * the caption, the bar under it and where a tap goes. The views ([com.pocketds.hub.ui.CoverFanView] and
 * SeriesFanCardView) only draw the [Plan].
 *
 * - **Five fixed slots**, the angles symmetrical about the middle; a series of fewer books has that many slots.
 * - The book you are on takes the middle slot when it has two books before and two after it. Near the start (#1,
 *   #2) or the end the fan keeps its shape and shows the first or last five, and your book is lit in its own slot
 *   at that slot's angle: a gold edge, a little bigger, raised and on top. The others are layered by distance
 *   from it and darken slightly with it.
 * - Not started: nothing lit, books 1 to 5, the first in front. Finished: nothing lit, the same five, and the
 *   finished tick on the front book.
 * - Books you do not have are in the fan, dimmed, and in the bar as outlines; the hub lists only the main numbered
 *   books that are out as missing.
 * - Every slot is tall; an audiobook-only book's square sits at the slot's bottom. No format marks on the fan.
 */
object SeriesFan {
    /** The most books a fan shows. */
    const val SLOTS = 5
    /** A series longer than this has one continuous bar with a white mark, not a part for each book. */
    const val CONTINUOUS_AFTER = 25
    /** How far each slot leans from the one before it, in degrees: -16, -8, 0, 8, 16 for five. */
    const val ANGLE_STEP = 8f
    /** How far apart slots stand, as a fraction of a cover's width. */
    const val STEP_FRACTION = 0.42f
    /** How much a slot darkens for each slot between it and the front, and the most it darkens. */
    const val SHADE_STEP = 0.14f
    const val MAX_SHADE = 0.5f
    /** The lit book is a little bigger and raised, as a fraction of a cover's size. */
    const val LIT_SCALE = 1.1f
    const val LIT_RAISE = 0.06f
    /** The most the fan opens with focus: its angles and the room between slots grow by this, when there is room. */
    const val MAX_OPENING = 1.22f
    /**
     * How far past its card a fan may open: a small margin, so an opened fan stays about as wide as the card it is
     * in (not into the next card's cell, nor, in the first and last columns, out to the screen's edge). The card
     * is lifted a little with focus, and the fan with it, so this is of the card as it is seen.
     */
    const val SPREAD_MARGIN_DP = 3f

    enum class Part { READ, ON, TO_READ, MISSING }

    /** One slot of the fan: a book, and how it stands. */
    data class Slot(
        val book: ReadingSeriesBook,
        /** The book's place among the series' books (0 for the first). */
        val index: Int,
        /** The book you are on. */
        val lit: Boolean,
        /** The slot on top: the lit one, else the first. */
        val front: Boolean,
        val angleDeg: Float,
        /** Slots from the middle: -2 to 2 for five. */
        val offset: Float,
        /** Higher stands in front. */
        val layer: Int,
        /** How much it is darkened, 0 for the lit book. */
        val shade: Float,
        /** A book you do not have. */
        val dimmed: Boolean,
        /** An audiobook-only book: a square cover at the slot's bottom. */
        val square: Boolean
    )

    sealed interface Bar {
        /** A part for each book. */
        data class Segments(val parts: List<Part>) : Bar
        /** One line: [readFraction] gold, a white [mark] where you are on (null with nothing on). */
        data class Continuous(val readFraction: Float, val mark: Float?) : Bar
    }

    /** What a tap does. */
    sealed interface Target {
        /** The series page, scrolled to the book numbered [number] (the lit one), or from its start. */
        data class OpenSeries(val number: String?) : Target
        /** The request page of a book you do not have. */
        data class Request(val book: ReadingSeriesBook) : Target
    }

    data class Plan(
        val slots: List<Slot>,
        val caption: String,
        val bar: Bar,
        /** Some book is read or on. */
        val started: Boolean,
        /** Every book you have is read: nothing is lit, the bar is gold and the front book carries the tick. */
        val finished: Boolean,
        val target: Target
    )

    /** Whether a library item is a series with books to fan. */
    fun hasFan(item: ReadingWork): Boolean = item.entityType == "collection" && item.seriesBooks.isNotEmpty()

    fun plan(series: ReadingWork): Plan? {
        val books = series.seriesBooks
        if (books.isEmpty()) return null
        val lit = books.indexOfFirst { it.state == "on" }
        val owned = books.filter { it.owned }
        val finished = owned.isNotEmpty() && owned.all { it.state == "read" }
        val started = books.any { it.state == "on" || it.state == "read" }
        val size = minOf(SLOTS, books.size)
        val start = if (lit < 0) 0 else (lit - 2).coerceIn(0, books.size - size)
        val front = if (lit < 0) 0 else lit - start
        val centre = (size - 1) / 2f
        val slots = (0 until size).map { slot ->
            val index = start + slot
            val book = books[index]
            val distance = kotlin.math.abs(slot - front)
            Slot(
                book = book, index = index, lit = index == lit, front = slot == front,
                angleDeg = (slot - centre) * ANGLE_STEP, offset = slot - centre,
                // Nearer the front is higher; of two at one distance the later book is.
                layer = (size - distance) * 2 + if (slot > front) 1 else 0,
                shade = if (distance == 0) 0f else minOf(MAX_SHADE, distance * SHADE_STEP),
                dimmed = !book.owned, square = book.kind == "audiobook"
            )
        }
        val parts = books.map { book ->
            when {
                !book.owned -> Part.MISSING
                book.state == "on" -> Part.ON
                book.state == "read" -> Part.READ
                else -> Part.TO_READ
            }
        }
        val bar = if (books.size <= CONTINUOUS_AFTER) Bar.Segments(parts) else Bar.Continuous(
            readFraction = (if (finished) books.size else books.count { it.state == "read" }).toFloat() / books.size,
            mark = if (lit >= 0) (lit + 0.5f) / books.size else null
        )
        val frontBook = slots[front].book
        val target = if (!frontBook.owned) Target.Request(frontBook) else Target.OpenSeries(books.getOrNull(lit)?.number?.takeIf(String::isNotBlank))
        return Plan(slots, caption(books.size, books.getOrNull(lit)?.number.orEmpty(), finished), bar, started, finished, target)
    }

    /** "6 books · on #1", "1 book", "8 books · finished"; no count (a series that did not say) is just where you are. */
    fun caption(count: Int, onNumber: String, finished: Boolean): String = listOfNotNull(
        "$count ${if (count == 1) "book" else "books"}".takeIf { count > 0 },
        when {
            finished -> "finished"
            onNumber.isNotBlank() -> "on #$onNumber"
            else -> null
        }
    ).joinToString(" · ")

    /**
     * How far the fan opens: 1 at rest, and with focus as much as [MAX_OPENING] allows without [plan]'s covers
     * reaching further from the fan's middle than [halfWidthDp] (the card's half width and its margin). A fan
     * that is already as wide as its card does not open at all.
     */
    fun opening(focused: Boolean, plan: Plan, coverDp: Float, halfWidthDp: Float): Float {
        if (!focused) return 1f
        val steps = ((MAX_OPENING - 1f) * 100).toInt()
        return (steps downTo 0).map { 1f + it / 100f }.firstOrNull { reachDp(plan, coverDp, it) <= halfWidthDp } ?: 1f
    }

    /**
     * How far from the fan's middle [plan]'s covers [coverDp] across reach, opened by [opening]: the corners of each
     * cover after it leans about its foot (and is made bigger, when it is the lit one), measured from where it stands.
     */
    fun reachDp(plan: Plan, coverDp: Float, opening: Float): Float = plan.slots.maxOf { slot ->
        val height = coverHeightDp(coverDp, slot.square)
        val scale = if (slot.lit) LIT_SCALE else 1f
        val lean = Math.toRadians((slot.angleDeg * opening).toDouble())
        val cos = kotlin.math.cos(lean)
        val sin = kotlin.math.sin(lean)
        val foot = slot.offset * STEP_FRACTION * coverDp * opening
        // The pivot is the middle of the cover's bottom edge; y runs down, so the top corners are at -height.
        listOf(-coverDp / 2f to 0f, coverDp / 2f to 0f, -coverDp / 2f to -height, coverDp / 2f to -height).maxOf { (x, y) ->
            kotlin.math.abs(foot + scale * (x * cos - y * sin)).toFloat()
        }
    }

    /**
     * How far a leaning lit cover's top corner rises over where it would stand upright, in dp, at the outermost slot
     * and the fan fully opened: the fan's box leaves this room, or the lit book of a series you are at the start or
     * the end of is cut off at the top.
     */
    fun leanRiseDp(coverDp: Float): Float {
        val height = coverHeightDp(coverDp, square = false)
        val lean = Math.toRadians((((SLOTS - 1) / 2f) * ANGLE_STEP * MAX_OPENING).toDouble())
        return (LIT_SCALE * (height * kotlin.math.cos(lean) + coverDp / 2f * kotlin.math.sin(lean) - height)).toFloat().coerceAtLeast(0f)
    }

    /**
     * Where a fan's one known cover [coverDp] across stands in its box: upright, in the middle both ways, and
     * never at the angle of the first slot of a longer fan. Left then top, in dp.
     */
    fun centred(boxWidthDp: Float, boxHeightDp: Float, coverDp: Float): Pair<Float, Float> =
        (boxWidthDp - coverDp) / 2f to (boxHeightDp - coverHeightDp(coverDp, square = false)) / 2f

    /** A tall cover [widthDp] across is this high; a square one as high as it is wide. */
    fun coverHeightDp(widthDp: Float, square: Boolean): Float = if (square) widthDp else widthDp * 1.5f

    /** The room across a fan of [slots] covers [coverDp] wide needs, at rest. */
    fun widthDp(coverDp: Float, slots: Int): Float = coverDp + (slots - 1).coerceAtLeast(0) * coverDp * STEP_FRACTION
}
