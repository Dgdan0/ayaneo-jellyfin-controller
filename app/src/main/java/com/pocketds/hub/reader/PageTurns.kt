package com.pocketds.hub.reader

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * Turning many pages quickly (#64): every swipe, press or tap turns exactly one page, however fast they come.
 *
 * Two things lost turns, both measured on the Pocket's emulator with real touches injected at a touch screen's rate:
 *
 *  - **A finger that comes down while a turn is still animating caught the page.** Readium's paginated page is a copy
 *    of Android's ViewPager, and a ViewPager lets the finger "catch" a page that is settling: it stops where it is, and
 *    the next swipe turns from there. In the first half of its animation that is still the old page, so the new swipe's
 *    turn is the turn already running, and one of the two is lost (1 to 3 swipes of 10 at 80 to 150 ms apart).
 *    [finishRunning] ends the running turn at once, as the finger lands, so the next swipe turns from where the last one
 *    arrived. The pad's presses and the margin's taps were never lost: they ask the pager for "the next page", which is
 *    counted from where the last turn is going, not from where it is.
 *  - **A swipe that begins where Readium's page is not never reached it.** The page is inset at the sides, and above and
 *    below it are the strips the corners live in: a touch there is the reader's own, and only a tap in the side inset
 *    was ever handled. [PageSwipe] says what such a finger did, so a swipe from there turns the page too.
 */
object PageTurns {
    /**
     * Ends any page turn still animating, in the pages under [root] ([View.dispatchTouchEvent] of the reader's container
     * calls this as a finger lands). It is the pager's own `completeScroll(false)`, private in both Readium's page and
     * AndroidX's ViewPager, so it is reached by reflection, and does nothing where it cannot be: the finger then catches the
     * page as it always did.
     */
    fun finishRunning(root: View) {
        if (root is ViewGroup) for (i in 0 until root.childCount) finishRunning(root.getChildAt(i))
        val complete = completeScroll(root.javaClass) ?: return
        try { complete.invoke(root, false) } catch (_: Exception) { }
    }

    /** The page the reader is on in its file, counted from 0 ([item]), and how many pages the file has ([pages]). */
    data class Place(val item: Int, val pages: Int)

    /**
     * Where Readium's paginated page, the one most on screen under [root], is: the pager's current item and its page count, which
     * a turn changes the moment it is decided (the animation after it is only the show). Null when there is no such page, or the
     * pager cannot be read, in which case nothing here corrects a turn.
     */
    fun place(root: View): Place? {
        var best: View? = null
        var bestArea = 0
        val rect = android.graphics.Rect()
        fun look(view: View) {
            if (view.javaClass.name == PAGE && view.isShown && view.getGlobalVisibleRect(rect) && rect.width() * rect.height() > bestArea) {
                best = view; bestArea = rect.width() * rect.height()
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) look(view.getChildAt(i))
        }
        look(root)
        val page = best ?: return null
        return try {
            val item = page.javaClass.getMethod("getMCurItem\$readium_navigator_release").invoke(page) as? Int ?: return null
            val pages = page.javaClass.getMethod("getNumPages\$readium_navigator_release").invoke(page) as? Int ?: return null
            Place(item, pages)
        } catch (_: Exception) { null }
    }

    /**
     * Puts the pager on [item] (`setCurrentItem(item, smooth)`, public on Readium's page): decided by the reader, not left to the
     * pager's own idea of where a swipe went. False where there is no such page or it cannot be reached.
     */
    fun setItem(root: View, item: Int, smooth: Boolean): Boolean {
        var best: View? = null
        var bestArea = 0
        val rect = android.graphics.Rect()
        fun look(view: View) {
            if (view.javaClass.name == PAGE && view.isShown && view.getGlobalVisibleRect(rect) && rect.width() * rect.height() > bestArea) {
                best = view; bestArea = rect.width() * rect.height()
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) look(view.getChildAt(i))
        }
        look(root)
        val page = best ?: return false
        return try {
            page.javaClass.getMethod("setCurrentItem", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(page, item, smooth)
            true
        } catch (_: Exception) { false }
    }

    private val known = HashMap<Class<*>, Method?>()

    /** The private `completeScroll(boolean)` of a Readium page or an AndroidX ViewPager, found once per class; null for any other view. */
    private fun completeScroll(type: Class<*>): Method? = synchronized(known) {
        known.getOrPut(type) {
            var owner: Class<*>? = type
            while (owner != null && owner.name != PAGE && owner.name != PAGER) owner = owner.superclass
            owner?.let { runCatching { it.getDeclaredMethod("completeScroll", Boolean::class.javaPrimitiveType).apply { isAccessible = true } }.getOrNull() }
        }
    }

    private const val PAGE = "org.readium.r2.navigator.R2WebView"
    private const val PAGER = "androidx.viewpager.widget.ViewPager"
}

/** What a finger that began where Readium's page is not did (#64): pure, so a JVM test pins it. */
object PageSwipe {
    sealed interface Outcome {
        /** A quick touch that stayed where it was. */
        data object Tap : Outcome
        /** A swipe: [leftwards] when the finger went to the left, which turns on in a book read from the left. */
        data class Swipe(val leftwards: Boolean) : Outcome
        /** Anything else: a drag that is not a turn, a long press, a finger that wandered. */
        data object Nothing : Outcome
    }

    /** A swipe goes at least this fraction of the reader's width, and never less than twice the touch slop. */
    const val MIN_REACH = 0.08f

    /** And it is more across than up or down by this much, so a scroll that drifts sideways is not a turn. */
    const val SIDEWAYS = 1.5f

    /**
     * What the finger did, from where it began to where it lifted: [dx] and [dy] in pixels, [durationMs] how long it was down,
     * [widthPx] the reader's width, [slopPx] the touch slop and [longPressMs] the long press timeout.
     */
    fun classify(dx: Float, dy: Float, durationMs: Long, widthPx: Float, slopPx: Float, longPressMs: Long): Outcome {
        val across = abs(dx)
        val along = abs(dy)
        if (across <= slopPx && along <= slopPx) return if (durationMs < longPressMs) Outcome.Tap else Outcome.Nothing
        val reach = maxOf(2f * slopPx, MIN_REACH * widthPx)
        return if (across >= reach && across >= SIDEWAYS * along) Outcome.Swipe(leftwards = dx < 0) else Outcome.Nothing
    }

    /**
     * A flick: the kind of swipe Readium's pager turns the page for by itself (it asks for 25 dp across and 200 dp a second). One
     * that is that, and that Readium did not turn, was dropped, and is turned for it ([PageTurns.place] says whether it was). A
     * slower drag, which it snaps back by its own rule (less than half a page), is left alone. [density] is the screen's, in px per dp.
     *
     * A touch that stayed down as long as a long press ([longPressMs]) is not one, however far and fast it went at the end: it is a
     * selection being extended (a long press on a word and a drag along the line, as one touch, which the page's own web view
     * takes), and turning the page under it threw the words away (#62). Measured on the emulator: a drag of 300 ms after a 700 ms
     * hold counted as a flick of over 800 dp a second, from the touch's start.
     */
    fun isFlick(dx: Float, dy: Float, durationMs: Long, density: Float, longPressMs: Long): Boolean {
        if (durationMs >= longPressMs) return false
        val across = abs(dx)
        if (across < FLICK_DP * density || across < SIDEWAYS * abs(dy)) return false
        return across / (maxOf(1L, durationMs) / 1000f) >= FLICK_SPEED_DP * density
    }

    /** What a flick is, in dp: across at least this far, at this speed (dp a second), a little over what Readium's pager asks. */
    const val FLICK_DP = 32f
    const val FLICK_SPEED_DP = 300f

    /** Whether a swipe turns the page on (true) or back: leftwards turns on in a book read from the left, and back in one read from the right. */
    fun forward(leftwards: Boolean, rightToLeft: Boolean): Boolean = leftwards != rightToLeft
}

/**
 * The turns of a burst of flicks, counted by the reader and not by Readium's pager (#64). Measured: under quick swipes the pager
 * loses a turn now and then, in two ways. It may ignore a flick (the page drags and springs back), or it may take one and then
 * undo it a moment later (its current item goes back one page while the page is idle); and the next swipe, counted from the
 * pager's item, is then a page short. The reader keeps the account instead: the page a burst began on, and the net turns
 * made since. After each flick, and as the next finger lands, the pager is put on the page the account says
 * ([PageTurns.setItem]); a burst is over when no flick has come for [idleMs], or something else moves the page.
 *
 * Pure (the time is passed in), so a JVM test pins it.
 */
class TurnLedger(private val idleMs: Long = IDLE_MS) {
    private var base = 0
    private var net = 0
    private var lastAt = Long.MIN_VALUE

    private fun running(nowMs: Long) = lastAt != Long.MIN_VALUE && nowMs - lastAt < idleMs

    /** The page the pager should be on as a finger lands in the middle of a burst, or null when none is running. */
    fun expected(nowMs: Long): Int? = if (running(nowMs)) base + net else null

    /**
     * A flick of [delta] (+1 on a page, -1 back) ended, with the pager [from] where it was as the finger came down (and put on the
     * page the account says, if a burst was running). The page it should be on now; null where the flick goes past either end of the
     * file, which the pager of files takes (and which ends the burst).
     */
    fun flick(from: PageTurns.Place, delta: Int, nowMs: Long): Int? {
        if (!running(nowMs)) { base = from.item; net = 0 }
        val target = base + net + delta
        if (target !in 0 until from.pages) { end(); return null }
        net += delta
        lastAt = nowMs
        return target
    }

    /** The burst is over: a drag that is not a flick, a pad press, a tap, a jump. */
    fun end() { lastAt = Long.MIN_VALUE }

    /** Whether [target], the page a flick was to land on, is still the page the account says: no flick has come since, and not long ago. */
    fun stillWanted(target: Int, nowMs: Long): Boolean = lastAt != Long.MIN_VALUE && base + net == target && nowMs - lastAt < SETTLE_MS

    companion object {
        /** A burst goes on while flicks are less than this far apart. */
        const val IDLE_MS = 600L
        /** How long the page is kept where the account puts it after a flick, against a late undoing by the pager. */
        const val SETTLE_MS = 1_200L

        /** When, after a flick, the pager is looked at again (ms): the undoing seen came 100 to 200 ms after the finger lifted. */
        val KEEP_AT_MS = longArrayOf(90L, 180L, 320L, 600L)
    }
}
