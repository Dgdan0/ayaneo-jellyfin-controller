package com.pocketds.hub.screens.library

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.DetailArtworkCardView
import com.pocketds.hub.ui.DetailLayout
import com.pocketds.hub.ui.DetailStyler
import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/** What a book in a series row says under its cover. Pure, so it is tested. */
object SeriesBookLabels {
    /**
     * "Ebook + audio", "Audio", "Read along", or null for a plain ebook: the
     * usual case says nothing, so the unusual one stands out.
     */
    fun formats(formats: List<String>): String? {
        val audio = "audiobook" in formats
        val text = "ebook" in formats
        return when {
            "readaloud" in formats -> "Read along"
            audio && text -> "Ebook + audio"
            audio -> "Audio"
            else -> null
        }
    }

    /** "#2 · 40% · Audio", "#3 · Missing", "Completed". */
    fun subtitle(number: String, available: Boolean, progress: ReadingProgress?, formats: List<String>): String =
        buildList {
            if (number.isNotBlank()) add("#$number")
            when {
                !available -> add("Missing")
                progress?.completed == true -> add("Completed")
                (progress?.percentage ?: 0.0) > 0 -> add(com.pocketds.hub.state.Fmt.readingPercentLabel(progress?.percentage ?: 0.0))
            }
            if (available) formats(formats)?.let(::add)
        }.joinToString(" · ")
}

/**
 * One series' books in reading order, as cards. The series page and the author
 * page both show a series this way; the caller decides what a card does.
 *
 * They are the prototype's book cards: 82 x 123dp covers 12dp apart from the
 * page's 22dp edge, an audiobook's square and a book with sound marked (#54).
 */
object SeriesBookStrip {
    private const val ARTWORK_DP = 123
    private const val CARD_DP = 82
    private const val GAP_DP = 12
    private const val EDGE_DP = 22

    fun create(
        context: Context,
        colors: PocketColors,
        ringVisible: () -> Boolean,
        api: HubApi,
        items: List<ReadingSectionItem>,
        bind: (card: DetailArtworkCardView, item: ReadingSectionItem) -> Unit
    ): View = FocusHorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
        val artwork = ARTWORK_DP
        val clearance = DetailLayout.focusClearance(DetailLayout.posterCardHeight(artwork, resources.configuration.fontScale)).coerceAtLeast(10)
        val edge = EDGE_DP
        setPadding(dp(context, edge), dp(context, clearance), dp(context, edge), dp(context, clearance))
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false
            items.forEach { item ->
                addView(DetailArtworkCardView(context, colors, ringVisible).apply {
                    // What the cover says of the formats (#54): an audiobook's is square, at the foot of the tall one's place.
                    artworkHeight(if (ReadingBookFacts.coverShape(item) == ReadingBookFacts.CoverShape.SQUARE) CARD_DP else artwork, slotDp = artwork)
                    formatMark(ReadingBookFacts.formatMark(item))
                    layoutParams = LinearLayout.LayoutParams(dp(context, CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginEnd = dp(context, GAP_DP) }
                    titleView.text = item.title
                    subtitleView.text = SeriesBookLabels.subtitle(item.number, item.isAvailable, item.progress, item.formats)
                    available(item.isAvailable)
                    marks(item.progress?.percentage ?: 0.0, item.progress?.completed == true)
                    contentDescription = "${item.title}, ${subtitleView.text}"
                    DetailStyler.image(image, item.artwork.takeIf { it.isNotBlank() }?.let(api::imageUrl), Artwork.loader(api, context))
                    bind(this, item)
                })
            }
        })
    }

    /** A book outside any series, as the same kind of card. */
    fun itemOf(work: ReadingWork) = ReadingSectionItem(
        workId = work.id, title = work.title, kind = work.kind, artwork = work.artwork,
        authors = work.authors, progress = work.progress, availability = "available", formats = work.availability
    )

    private fun dp(context: Context, value: Int) = Styler.dpInt(context, value.toFloat())
}
