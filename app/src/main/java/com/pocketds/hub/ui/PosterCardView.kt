package com.pocketds.hub.ui

import com.pocketds.hub.playback.ResumeRules
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.model.Availability
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * One title in a grid: poster, badge, title, subtitle.
 *
 * The placeholder is a flat colour rather than a spinner. Twenty-five spinning
 * progress bars is twenty-five running animators and a screen that reads as
 * broken; a flat fill lets the grid appear instantly as a grid and fill in.
 *
 * It is the Glass poster (GLASS_PLAN.md): 11dp corners and a 3dp ring, a
 * count as a white pill and a tick in the accent, how far in as a white bar
 * inside the picture ([GlassProgressBar]), and the day of a coming-up title on
 * a strip of the page's glass ([setDayChip]). A book's cover keeps its words on
 * the page. A book's cover says its formats (#54): tall for an ebook, square for an audiobook, tall with a small
 * round mark ([FormatMark]) for both; a square one sits at the foot of the tall one's place, so a row's covers line up
 * along their bottom edge and its captions stay on one line. A comic's kind sits on a dark pill.
 */
class PosterCardView(
    context: Context,
    private val colors: PocketColors,
    /**
     * Title and subtitle under the poster. Home turns them off: its hero names
     * the focused card in large type, and a row without captions fits under it.
     * The card's width decides the rest, a poster being fixed at 2:3.
     */
    private val captions: Boolean = true
) : LinearLayout(context) {

    /** A tall cover's place, which a square cover sits at the foot of. */
    private val coverSlot: CoverSlot
    private val posterWrap: ArtworkFrame
    private val poster: ImageView
    private val missingArt: TextView
    /** Which bind a late load failure belongs to, so it cannot label a recycled card. */
    private var bindToken = 0
    private val badge: TextView
    /** "Comic" or "Manga" on the cover's lower corner, as the design marks them. */
    private val kindTag: TextView
    private val title: TextView
    private val subtitle: TextView
    /** How far in: a white bar inside the picture. */
    private val progressBar: com.pocketds.hub.ui.glass.GlassProgressBar
    /** A card without captions shows no subtitle whatever it is bound to. */
    private val compactCard = !captions
    /** "Tomorrow" on a coming-up title, along the poster's foot. */
    private val dayChip: TextView

    init {
        orientation = VERTICAL
        background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        Styler.makeFocusable(this)
        isClickable = true
        // The whole card is one focus target. Without this the image, title and
        // badge are three, and focus appears to wander inside a single item.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        setPadding(0, 0, 0, if (captions) Styler.dpInt(context, 6f) else 0)

        val corner = ArtworkFrame.GLASS_CORNER_DP
        posterWrap = ArtworkFrame(context, POSTER_RATIO, corner).apply {
            isDuplicateParentStateEnabled = true
            foreground = Styler.focusOutline(context, colors, corner, 3f)
        }
        poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(colors.posterPlaceholder)
        }
        posterWrap.addView(poster)

        // Shown only when the server has no artwork, or it failed to load: an
        // empty grey box (How I Met Your Mother has no poster in Jellyfin)
        // read as "still loading" forever.
        missingArt = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colors.mutedText)
            val pad = Styler.dpInt(context, 10f)
            setPadding(pad, pad, pad, pad)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        posterWrap.addView(missingArt, FrameLayout.LayoutParams(MATCH, MATCH))

        badge = TextView(context).apply {
            textSize = 10f
            setTextColor(colors.accentText)
            val h = Styler.dpInt(context, 6f)
            val v = Styler.dpInt(context, 3f)
            setPadding(h, v, h, v)
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = Styler.dpInt(context, 4f)
                marginEnd = Styler.dpInt(context, 4f)
            }
        }
        posterWrap.addView(badge)

        // The prototype's `.kind`: dark, heavy little capitals on 7dp corners.
        kindTag = TextView(context).apply {
            textSize = 10f
            textWeight(800)
            letterSpacing = .04f
            includeFontPadding = false
            setTextColor(android.graphics.Color.rgb(243, 245, 248))
            setPadding(Styler.dpInt(context, 7f), Styler.dpInt(context, 4f), Styler.dpInt(context, 7f), Styler.dpInt(context, 4f))
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 7f), KIND_FILL)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = Styler.dpInt(context, 6f)
                bottomMargin = Styler.dpInt(context, KIND_EDGE_DP)
            }
        }
        posterWrap.addView(kindTag)

        // How far in, readable at a glance without reading any text.
        progressBar = com.pocketds.hub.ui.glass.GlassProgressBar(context).also { bar ->
            val inset = Styler.dpInt(context, com.pocketds.hub.ui.glass.GlassProgressBar.POSTER_INSET_DP)
            posterWrap.addView(bar, FrameLayout.LayoutParams(MATCH, Styler.dpInt(context, com.pocketds.hub.ui.glass.GlassProgressBar.HEIGHT_DP), Gravity.BOTTOM)
                .apply { setMargins(inset, 0, inset, inset) })
        }
        dayChip = TextView(context).apply {
            textSize = 11f
            textWeight(700)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setPadding(Styler.dpInt(context, 7f), Styler.dpInt(context, 5f), Styler.dpInt(context, 7f), Styler.dpInt(context, 5f))
            // Nearly solid, as a sheet is: it sits on the poster's own lettering,
            // and a see-through strip let "SLOW HORSES" run through "Wed".
            GlassPanelDrawable.attach(this, Styler.dp(context, 9f), GlassColors::sheet)
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { chip ->
            val edge = Styler.dpInt(context, 6f)
            posterWrap.addView(chip, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(edge, 0, edge, edge) })
        }
        // The place follows the card's state as the cover in it does (the card is the one focus target).
        coverSlot = CoverSlot(context, POSTER_RATIO).apply { isDuplicateParentStateEnabled = true }
        coverSlot.addView(posterWrap, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        addView(coverSlot, LayoutParams(MATCH, WRAP))

        // The prototype's captions: the title on one bold line, the year under it.
        title = TextView(context).apply {
            // A Hebrew title starts at the card's left edge like every other, not its right.
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            textSize = 12f
            textWeight(700)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colors.primaryText)
            setPadding(0, Styler.dpInt(context, 7f), 0, 0)
            visibility = if (captions) VISIBLE else GONE
        }
        addView(title)

        subtitle = TextView(context).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(CAPTION)
            setPadding(0, Styler.dpInt(context, 1f), 0, 0)
            visibility = if (captions) VISIBLE else GONE
        }
        addView(subtitle)
    }

    fun bind(hit: SearchHit, imageLoader: ImageLoader, imageUrl: (String) -> String) =
        bind(hit, imageLoader, imageUrl, showAvailability = true)

    fun bind(
        hit: SearchHit,
        imageLoader: ImageLoader,
        imageUrl: (String) -> String,
        showAvailability: Boolean
    ) {
        kindTag.visibility = GONE
        posterWrap.ratio = POSTER_RATIO
        setFormatMark(com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark.NONE)
        // A recycled card keeps no day from the row it came from.
        dayChip.visibility = GONE
        title.text = hit.media.title
        contentDescription = listOf(hit.media.title, hit.subtitle).filter { it.isNotBlank() }.joinToString(", ")
        subtitle.text = hit.subtitle
        if (compactCard) subtitle.visibility = GONE

        val availability = Availability.fromWire(hit.availability)
        val libraryBadge = when {
            ResumeRules.showsWatched(hit.played, hit.progress) -> "✓"
            hit.unplayedCount > 0 -> hit.unplayedCount.toString()
            hit.favorite -> "★"
            else -> ""
        }
        if (!showAvailability && libraryBadge.isNotEmpty()) {
            roundBadge(libraryBadge)
        } else if (showAvailability && availability.label.isNotEmpty()) {
            availabilityChip(availability)
        } else {
            badge.visibility = GONE
        }

        showProgress(hit.progress)

        loadPoster(hit.media.poster, imageLoader, imageUrl)
    }

    fun bindReading(item: ReadingItem, imageLoader: ImageLoader, imageUrl: (String) -> String) {
        kindTag.visibility = GONE
        title.text = item.title
        contentDescription = listOf(item.title, item.subtitle).filter { it.isNotBlank() }.joinToString(", ")
        subtitle.text = item.subtitle
        subtitle.visibility = if (compactCard) GONE else VISIBLE
        showProgress(0.0)
        // An audiobook's cover is square, as the prototype's Discover draws it: a result is one format, so it has no mark.
        val facts = com.pocketds.hub.screens.library.ReadingBookFacts
        posterWrap.ratio = coverRatio(facts.coverShape(item.contentType, listOf(item.contentType)))
        setFormatMark(com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark.NONE)
        if (item.inLibrary) availabilityChip(Availability.AVAILABLE) else badge.visibility = GONE
        loadPoster(item.cover, imageLoader, imageUrl)
    }

    /** [showKind]: the Comic/Manga pill, where comics sit among books (Books home), not in a comics library. */
    fun bindReadingWork(work: ReadingWork, imageLoader: ImageLoader, imageUrl: (String) -> String, showKind: Boolean = false) {
        title.text = work.title
        val kind = com.pocketds.hub.screens.library.ReadingBookFacts.kindTag(work.kind)
        kindTag.text = kind
        kindTag.visibility = if (kind != null && showKind) VISIBLE else GONE
        subtitle.text = if (kind != null) com.pocketds.hub.screens.library.ReadingBookFacts.comicLine(work.progress) else work.cardSubtitle
        subtitle.visibility = VISIBLE
        // The words stay on the page under the cover; what the cover says of the formats is the facts' (#54).
        val facts = com.pocketds.hub.screens.library.ReadingBookFacts
        val mark = facts.formatMark(work)
        posterWrap.ratio = coverRatio(facts.coverShape(work))
        setFormatMark(mark)
        subtitle.ellipsize=android.text.TextUtils.TruncateAt.END
        showProgress(if (work.progress?.completed == true) 0.0 else work.progress?.percentage ?: 0.0)
        if (work.progress?.completed == true) {
            roundBadge("✓")
        } else if (work.entityType == "collection" && work.bookCount > 0) {
            roundBadge(work.bookCount.toString())
        } else {
            badge.visibility = GONE
        }
        loadPoster(work.artwork, imageLoader, imageUrl)
        contentDescription = buildString {
            append(work.title)
            if (work.entityType == "collection" && work.bookCount > 0) {
                append(", ").append(work.bookCount).append(if (work.bookCount == 1) " book" else " books")
            }
            if (work.subtitle.isNotBlank()) append(", ").append(work.subtitle)
            FormatMark.words(mark)?.let { append(", ").append(it) }
            work.progress?.let { append(", ").append(com.pocketds.hub.state.Fmt.readingPercent(it.percentage, it.completed)).append(" percent read") }
        }
    }

    private fun coverRatio(shape: com.pocketds.hub.screens.library.ReadingBookFacts.CoverShape) =
        if (shape == com.pocketds.hub.screens.library.ReadingBookFacts.CoverShape.SQUARE) 1f else POSTER_RATIO

    /** The small round mark on the cover's top left corner, or nothing: over the picture, under the ring and the other marks. */
    private fun setFormatMark(mark: com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark) {
        poster.foreground = FormatMark.drawable(context, mark)
    }

    /** How far in: the white bar inside the picture, with a comic's kind pill lifted clear of it. */
    private fun showProgress(fraction: Double) {
        val bar = progressBar
        bar.fraction = fraction
        (kindTag.layoutParams as? FrameLayout.LayoutParams)?.let {
            val lift = if (bar.visibility == VISIBLE) com.pocketds.hub.ui.glass.GlassProgressBar.HEIGHT_DP + KIND_EDGE_DP else 0f
            val bottom = Styler.dpInt(context, KIND_EDGE_DP + lift)
            if (it.bottomMargin != bottom) { it.bottomMargin = bottom; kindTag.layoutParams = it }
        }
    }

    private fun loadPoster(path: String, imageLoader: ImageLoader, imageUrl: (String) -> String) {
        val token = ++bindToken
        missingArt.visibility = GONE
        missingArt.text = title.text
        Artwork.bind(poster, imageLoader, if (path.isBlank()) null else imageUrl(path), opaque = true,
            onMissing = { if (token == bindToken) missingArt.visibility = VISIBLE })
    }

    /**
     * The day of a coming-up title on a strip of glass along the poster's foot
     * ("Tomorrow"), as the prototype marks them. Null removes it.
     */
    fun setDayChip(text: String?) {
        dayChip.text = text.orEmpty()
        dayChip.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    /**
     * A count (or a star) in a white pill with dark figures, a tick in the
     * accent's circle, as the prototype's posters carry them: watched,
     * unwatched episodes, books in a collection.
     */
    private fun roundBadge(text: String) {
        val tick = text == "✓"
        badge.visibility = VISIBLE
        badge.text = text
        badge.textSize = 11f
        badge.textWeight(700)
        badge.gravity = Gravity.CENTER
        badge.includeFontPadding = false
        val side = Styler.dpInt(context, if (tick) 0f else 7f)
        badge.setPadding(side, 0, side, 0)
        badge.minWidth = Styler.dpInt(context, if (tick) 22f else 24f)
        badge.minHeight = Styler.dpInt(context, 22f)
        badge.background = if (tick) ThemeGradientDrawable.oval(colors.accent)
            else ThemeGradientDrawable.rounded(Styler.dp(context, 11f), COUNT_FILL)
        badge.setTextColor(if (tick) colors.accentText else GlassColors.INK)
        (badge.layoutParams as? FrameLayout.LayoutParams)?.let {
            val edge = Styler.dpInt(context, 6f)
            it.gravity = Gravity.TOP or Gravity.END
            it.topMargin = edge
            it.marginEnd = edge
            it.marginStart = 0
            badge.layoutParams = it
        }
    }

    /**
     * Where a title stands as a chip at the poster's top left (the prototype's
     * `.av`): In library, Partial, On the way, Requested, in the badge colours
     * of CLAUDE.md, with the glass edge and light.
     */
    private fun availabilityChip(availability: Availability) {
        val fill = badgeColour(availability)
        badge.visibility = VISIBLE
        badge.text = availability.label
        badge.textSize = 10.5f
        badge.textWeight(800)
        badge.includeFontPadding = false
        badge.minWidth = 0
        badge.minHeight = 0
        badge.setPadding(Styler.dpInt(context, 8f), Styler.dpInt(context, 5f), Styler.dpInt(context, 8f), Styler.dpInt(context, 5f))
        badge.background = GlassPanelDrawable(GlassColors.withAlpha(fill, 0xF0), Styler.dp(context, 999f))
        badge.setTextColor(SemanticColor.foreground(fill))
        (badge.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.TOP or Gravity.START
            val edge = Styler.dpInt(context, 6f)
            it.topMargin = edge
            it.marginStart = edge
            it.marginEnd = 0
            badge.layoutParams = it
        }
    }

    private fun badgeColour(availability: Availability): Int = when (availability) {
        Availability.AVAILABLE -> colors.badgeAvailable
        // Pink, matching what Jellyfin and the *arr apps use for "monitored but
        // incomplete". Lumping it in with green said "you have this", which for
        // a series missing half its episodes is not true.
        Availability.PARTIAL -> colors.badgePartial
        Availability.DOWNLOADING, Availability.PROCESSING, Availability.REQUESTED ->
            colors.badgePending
        Availability.BLOCKED, Availability.DELETED -> colors.badgeFailed
        else -> colors.mutedText
    }

    /** The place of a tall cover [ratio] wide for its height, whatever the cover in it. */
    private class CoverSlot(context: Context, private val ratio: Float) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((width / ratio).toInt(), MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** A poster's second caption line: white at 64%. */
        const val CAPTION = GlassColors.QUIET
        /** A poster's count pill: white at 90%. */
        const val COUNT_FILL = 0xE6FFFFFF.toInt()
        /** A comic's kind pill (the prototype's `.kind`): black at 62%, 6dp in from the corner. */
        const val KIND_FILL = 0x9E000000.toInt()
        const val KIND_EDGE_DP = 6f
        const val POSTER_RATIO = 2f / 3f
    }
}
