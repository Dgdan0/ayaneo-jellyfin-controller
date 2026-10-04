package com.pocketds.hub.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import com.pocketds.hub.model.SearchHit

/**
 * A short row of posters inside a page, such as a detail page's More like this.
 * Left and right move by position (StripNav) like every other card row.
 */
class PosterStripView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val posterHeightDp: Float = 141f,
    /** Glass posters (PosterCardView's), as a title page's More like this has them. */
    private val glass: Boolean = false
) : RecyclerView(context) {
    var onOpen: ((SearchHit) -> Unit)? = null
    var onFocused: ((SearchHit) -> Unit)? = null
    private var hits: List<SearchHit> = emptyList()
    private var loader: ImageLoader? = null
    private var imageUrl: (String) -> String = { it }

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
        isFocusable = false
        clipToPadding = false
        clipChildren = false
        setItemViewCacheSize(8)
        if (glass) setPadding(dp(16), dp(10), dp(16), dp(10)) else setPadding(dp(18), dp(10), dp(18), dp(10))
        adapter = Cards()
    }

    fun bind(next: List<SearchHit>, loader: ImageLoader, imageUrl: (String) -> String) {
        this.loader = loader
        this.imageUrl = imageUrl
        if (next == hits) return
        hits = next
        adapter?.notifyDataSetChanged()
    }

    fun focusFirst(): Boolean {
        if (hits.isEmpty()) return false
        scrollToPosition(0)
        post { findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
        return true
    }

    private inner class Cards : Adapter<ViewHolder>() {
        override fun getItemCount() = hits.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val card = PosterCardView(parent.context, colors, posterHeightDp, glass = glass).apply {
                layoutParams = LayoutParams(dp((posterHeightDp * 2 / 3).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(6), dp(4), dp(6), dp(4)) }
                FocusDecorator.attach(this, ringVisible)
            }
            return object : ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val hit = hits[position]
            val card = holder.itemView as PosterCardView
            loader?.let { card.bind(hit, it, imageUrl, showAvailability = false) }
            card.activateOnTap { onOpen?.invoke(hit) }
            FocusDecorator.listen(card, ringVisible) { _, focused -> if (focused) onFocused?.invoke(hit) }
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
}
