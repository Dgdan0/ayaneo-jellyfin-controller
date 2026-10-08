package com.pocketds.hub.reader

import android.content.Context
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Calendar

/**
 * Kindle's corners over a book's page (#42): the time at the top right, what
 * [PageInfoChoice.corner] says at the bottom left, how far through the book at
 * the bottom right. Quiet text in the page's colour ([PageInfo.ink]) in strips
 * the page keeps clear of the text; the words are [PageInfo]'s.
 *
 * It lies over the page and moves with it, so the reader's preview shows the
 * corners live while the appearance sheet changes them. Taps pass through to
 * the page except on the bottom left, which asks for the next choice
 * ([onCycle]), as it does on Kindle. The reader hides the whole view while its
 * bars are up ([ReaderPagePreviewController]); a comic does not have one.
 */
class PageInfoView(context: Context) : FrameLayout(context) {
    /** A tap on the bottom left. */
    var onCycle: () -> Unit = {}

    private val clock = corner(Gravity.TOP or Gravity.END)
    private val left = corner(Gravity.BOTTOM or Gravity.START).apply {
        isClickable = true
        setOnClickListener { onCycle() }
    }
    private val right = corner(Gravity.BOTTOM or Gravity.END)

    private var choice = PageInfoChoice()
    private var place = PagePlace()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, PageInfo.millisToNextMinute(System.currentTimeMillis()))
        }
    }
    private var ticking = false

    private fun corner(edge: Int) = TextView(context).apply {
        textSize = 11f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or (if (edge and Gravity.END == Gravity.END) Gravity.END else Gravity.START)
        visibility = GONE
        // Not a focus stop: the pad reaches the corners by a key, never by focus.
        isFocusable = false
        this@PageInfoView.addView(this, LayoutParams(LayoutParams.WRAP_CONTENT, 0, edge))
    }

    /** What to show: the choice, where the reader is, the ink, the corners' inset from the sides and the strip's height, in pixels. */
    fun show(choice: PageInfoChoice, place: PagePlace, ink: Int, sideInsetPx: Int, stripPx: Int) {
        this.choice = choice
        this.place = place
        listOf(clock, left, right).forEach { view ->
            view.setTextColor(ink)
            view.setPadding(sideInsetPx, 0, sideInsetPx, 0)
            val params = view.layoutParams as LayoutParams
            if (params.height != stripPx) {
                params.height = stripPx
                view.requestLayout()
            }
        }
        refresh()
    }

    private fun refresh() {
        val time = if (choice.clock) Calendar.getInstance().let {
            PageInfo.clock(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), DateFormat.is24HourFormat(context))
        } else ""
        set(clock, time, "Time")
        // The bottom left keeps its place, empty, while there is a strip for it: a tap there still asks for the next.
        set(left, PageInfo.bottomLeft(choice.corner, place), choice.corner.choice, hint = "tap for the next choice", keepPlace = choice.bottomStrip)
        set(right, PageInfo.bottomRight(choice, place), "Through the book")
    }

    private fun set(view: TextView, text: String, name: String, hint: String = "", keepPlace: Boolean = false) {
        if (view.text.toString() != text) view.text = text
        view.visibility = if (text.isNotEmpty() || keepPlace) VISIBLE else GONE
        view.contentDescription = if (text.isEmpty()) name else listOf(text, hint).filter(String::isNotEmpty).joinToString(", ")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The tap target is a good part of the strip, not only the words in it.
        val params = left.layoutParams as LayoutParams
        val width = (w * LEFT_SHARE).toInt()
        if (params.width != width) {
            params.width = width
            left.requestLayout()
        }
    }

    /** Keeps the clock right while the page is on screen. */
    fun start() {
        if (ticking) return
        ticking = true
        post(tick)
    }

    fun stop() {
        ticking = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The bottom left's tap target, of the page's width. */
        const val LEFT_SHARE = 0.45f
    }
}
