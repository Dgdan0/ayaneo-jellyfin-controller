package com.pocketds.hub.reader

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Job

/**
 * The comic reader's page, on one of a few surfaces (#16, C3). The one in
 * [front] shows; the others hold the pages either side, decoded and placed,
 * so a turn swaps to a page already drawn instead of blanking while it
 * decodes ([PageSlots] says which pages). A surface behind is laid out and
 * drawn but unseen (alpha 0): SubsamplingScaleImageView only decodes a page it
 * draws, and a view that is merely invisible is never drawn.
 *
 * Touch goes to the front surface only, whatever lies over it.
 */
class PageSurface(context: Context, count: Int, make: (Context) -> SubsamplingScaleImageView) : FrameLayout(context) {
    class Slot(val view: SubsamplingScaleImageView) {
        /** The page it holds or is decoding. */
        var key: PageKey? = null
        /** Decoded and placed: a swap to it shows at once. */
        var ready = false
        var job: Job? = null

        fun clear() {
            job?.cancel()
            job = null
            key = null
            ready = false
            view.recycle()
        }
    }

    val slots: List<Slot> = List(count.coerceAtLeast(1)) { Slot(make(context)) }
    var front: Slot = slots.first()
        private set

    init {
        slots.forEach { slot ->
            addView(slot.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            slot.view.alpha = if (slot === front) 1f else 0f
        }
    }

    fun slotFor(key: PageKey): Slot? = slots.firstOrNull { it.key == key }

    fun slotOf(view: SubsamplingScaleImageView): Slot? = slots.firstOrNull { it.view === view }

    /** Brings [slot] to the front at once; the one before stays decoded behind it. */
    fun show(slot: Slot) {
        if (slot === front) return
        val previous = front
        front = slot
        slot.view.alpha = 1f
        previous.view.alpha = 0f
        // A gesture that began on the page before ends there, not halfway on this one.
        val now = android.os.SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { previous.view.dispatchTouchEvent(it) }.recycle()
    }

    /** Decodes [path] into [slot] as [key]; the slot is ready once its view says so. */
    fun load(slot: Slot, key: PageKey, path: String) {
        slot.key = key
        slot.ready = false
        slot.view.setImage(ImageSource.uri(path))
    }

    /** A surface the reader may decode into: never the one in front; an empty one, else one holding nothing in [keep]. */
    fun spare(keep: Collection<PageKey>): Slot? =
        slots.firstOrNull { it !== front && it.key == null }
            ?: slots.firstOrNull { it !== front && it.key !in keep }
            ?: slots.firstOrNull { it !== front }

    fun recycleAll() = slots.forEach { it.clear() }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = front.view.dispatchTouchEvent(event)
}
