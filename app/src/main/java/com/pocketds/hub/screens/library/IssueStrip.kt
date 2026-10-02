package com.pocketds.hub.screens.library

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.activateOnTap

/**
 * A comic volume's issues (or a manga volume's chapters) as covers, in a strip
 * that makes cards only as they scroll into view: Fantastic Four's 1961 volume
 * is 147 issues, which as rows was a page of "7 · 7" boxes and as an eager
 * strip would ask for 147 covers at once.
 *
 * Each card is the series page's book card -- cover, "Issue 7", "36 pages ·
 * Not started", a bar or a check -- and the strip opens at the issue you are
 * on, else the first one not finished.
 */
object IssueStrip {
    private const val ARTWORK_DP = 120
    private const val CARD_DP = 88

    fun create(
        context: Context,
        colors: PocketColors,
        ringVisible: () -> Boolean,
        api: HubApi,
        items: List<ReadingSectionItem>,
        kind: String,
        /** The series cover, for an issue the hub sent no cover of its own (an older hub). */
        seriesArtwork: String,
        onOpen: (ReadingSectionItem) -> Unit
    ): RecyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        isFocusable = false
        clipToPadding = false
        clipChildren = false
        setItemViewCacheSize(10)
        val clearance = DetailLayout.focusClearance(DetailLayout.posterCardHeight(ARTWORK_DP, resources.configuration.fontScale)).coerceAtLeast(10)
        setPadding(dp(context, 24), dp(context, clearance), dp(context, 24), dp(context, clearance))
        adapter = Adapter(colors, ringVisible, api, items, kind, seriesArtwork, onOpen)
        val start = items.indexOfFirst { val p = it.progress; p != null && !p.completed && p.percentage > 0 }
            .takeIf { it >= 0 } ?: items.indexOfFirst { it.progress?.completed != true }.coerceAtLeast(0)
        if (start > 0) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(start, dp(context, 24))
    }

    private class Holder(val card: DetailArtworkCardView) : RecyclerView.ViewHolder(card)

    private class Adapter(
        private val colors: PocketColors,
        private val ringVisible: () -> Boolean,
        private val api: HubApi,
        private val items: List<ReadingSectionItem>,
        private val kind: String,
        private val seriesArtwork: String,
        private val onOpen: (ReadingSectionItem) -> Unit
    ) : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            return Holder(DetailArtworkCardView(context, colors, ringVisible).apply {
                artworkHeight(ARTWORK_DP)
                titleView.minLines = 1
                layoutParams = RecyclerView.LayoutParams(dp(context, CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(context, 14) }
            })
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val card = holder.card
            card.titleView.text = ReadingBookFacts.issueTitle(item, kind)
            card.subtitleView.text = ReadingBookFacts.issueLine(item)
            card.marks(item.progress?.percentage ?: 0.0, item.progress?.completed == true)
            card.contentDescription = "${card.titleView.text}, ${card.subtitleView.text}"
            val artwork = item.artwork.ifBlank { seriesArtwork }
            DetailStyler.image(card.image, artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl), Artwork.loader(api, card.context))
            card.activateOnTap { onOpen(item) }
        }
    }

    private fun dp(context: Context, value: Int) = Styler.dpInt(context, value.toFloat())
}
