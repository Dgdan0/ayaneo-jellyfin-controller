package com.pocketds.hub.ui.glass

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import com.pocketds.hub.ui.textWeight

/**
 * A row's heading on Glass (the prototype's `.row h3`): Figtree bold at 14,
 * white, with a quiet count after it in smaller type ("Also reading 2",
 * "Volume 1961 147 issues"). The caller sets the padding that lines it up.
 */
object GlassHeading {
    /** The count after a heading (`h3 small`): white at 58%. */
    const val COUNT = 0x94FFFFFF.toInt()

    fun text(title: String, count: String? = null): CharSequence = SpannableStringBuilder(title).apply {
        if (count.isNullOrBlank()) return@apply
        append("  ")
        val from = length
        append(count)
        setSpan(ForegroundColorSpan(COUNT), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(AbsoluteSizeSpan(12, true), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun create(context: Context, title: String, count: String? = null): TextView = TextView(context).apply {
        text = text(title, count)
        textSize = 14f
        textWeight(700)
        setTextColor(Color.WHITE)
    }
}
