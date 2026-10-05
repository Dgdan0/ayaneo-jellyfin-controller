package com.pocketds.hub.ui

import com.pocketds.hub.playback.ResumeRules
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
 * [glass] is the Glass poster (GLASS_PLAN.md): 11dp corners and a 3dp ring, a
 * count as a white pill and a tick in the accent, how far in as a white bar
 * inside the picture ([GlassProgressBar]), and the day of a coming-up title on
 * a strip of the page's glass ([setDayChip]). A book's cover keeps its words on
 * the page; an audiobook's is square, and a comic's kind sits on a dark pill.
 * Screens opt in as their Glass milestone lands.
 */
class PosterCardView(
    context: Context,
    private val colors: PocketColors,
    /**
     * Poster height in dp. The card is sized from this because a poster is
     * fixed at 2:3, so one number decides the whole card -- and on a 456dp-tall
     * landscape screen that number is what decides whether you can see one row
     * of content or two.
     */
    posterHeightDp: Float = 190f,
    /**
     * Title and subtitle under the poster. Home turns them off: its hero names
     * the focused card in large type, and a row without captions fits under it.
     */
    private val captions: Boolean = true,
    private val glass: Boolean = false
) : LinearLayout(context) {

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
    private val progressBar: ArtworkProgressView
    /** Glass: the progress inside the picture, in place of [progressBar] along its foot. */
    private var glassBar: com.pocketds.hub.ui.glass.GlassProgressBar? = null
    private val compactCard: Boolean
    /** Glass: "Tomorrow" on a coming-up title, along the poster's foot. */
    private var dayChip: TextView? = null

    init {
        orientation = VERTICAL
        background = ColorDrawable(android.graphics.Color.TRANSPARENT)
        Styler.makeFocusable(this)
        isClickable = true
        // The whole card is one focus target. Without this the image, title and
        // badge are three, and focus appears to wander inside a single item.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // Padding and type scale with the card. A 6dp inset and 13sp title look
        // right at 190dp and waste a third of a 140dp card.
        val compact = posterHeightDp < 170f
        setPadding(0, 0, 0, if (captions) Styler.dpInt(context, 6f) else 0)

        val corner = if (glass) ArtworkFrame.GLASS_CORNER_DP else ArtworkFrame.CORNER_DP
        posterWrap = ArtworkFrame(context, 2f / 3f, corner).apply {
            isDuplicateParentStateEnabled = true
            foreground = Styler.focusOutline(context, colors, corner, if (glass) 3f else 2f)
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

        kindTag = TextView(context).apply {
            textSize = 9f
            textWeight(700)
            setTextColor(android.graphics.Color.rgb(243, 245, 248))
            setPadding(Styler.dpInt(context, 6f), Styler.dpInt(context, 1f), Styler.dpInt(context, 6f), Styler.dpInt(context, 1f))
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 999f), android.graphics.Color.argb(204, 10, 13, 18))
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                marginStart = Styler.dpInt(context, 6f)
                bottomMargin = Styler.dpInt(context, 8f)
            }
        }
        posterWrap.addView(kindTag)
        if (glass) kindTag.apply {
            // The prototype's `.kind`: dark, heavy little capitals on 7dp corners.
            textSize = 10f
            textWeight(800)
            letterSpacing = .04f
            includeFontPadding = false
            setPadding(Styler.dpInt(context, 7f), Styler.dpInt(context, 4f), Styler.dpInt(context, 7f), Styler.dpInt(context, 4f))
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 7f), GLASS_KIND)
            (layoutParams as FrameLayout.LayoutParams).bottomMargin = Styler.dpInt(context, KIND_EDGE_DP)
        }

        // A thin bar along the bottom of the poster while something is actually
        // downloading -- readable at a glance without reading any text.
        progressBar = ArtworkProgressView(context, colors.accent)
        if (glass) glassBar = com.pocketds.hub.ui.glass.GlassProgressBar(context).also { bar ->
            val inset = Styler.dpInt(context, com.pocketds.hub.ui.glass.GlassProgressBar.POSTER_INSET_DP)
            posterWrap.addView(bar, FrameLayout.LayoutParams(MATCH, Styler.dpInt(context, com.pocketds.hub.ui.glass.GlassProgressBar.HEIGHT_DP), Gravity.BOTTOM)
                .apply { setMargins(inset, 0, inset, inset) })
        } else posterWrap.addView(progressBar, FrameLayout.LayoutParams(MATCH, Styler.dpInt(context, 4f), Gravity.BOTTOM))
        if (glass) dayChip = TextView(context).apply {
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
        addView(posterWrap, LayoutParams(MATCH, WRAP))

        title = TextView(context).apply {
            // A Hebrew title starts at the card's left edge like every other, not its right.
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            textSize = if (compact) 12f else 13f
            maxLines = 2
            minLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colors.primaryText)
            setPadding(0, Styler.dpInt(context, if (compact) 3f else 6f), 0, 0)
        }
        addView(title)
        if (!captions) title.visibility = GONE

        subtitle = TextView(context).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            textSize = 12f
            maxLines = 1
            setTextColor(colors.mutedText)
            // On a compact card the year alone is worth the line; anything
            // longer just crowds the title it sits under.
            visibility = if (compact) GONE else VISIBLE
        }
        addView(subtitle)
        if (glass && captions) {
            // The prototype's captions: the title on one bold line, the year under it.
            title.textSize = 12f
            title.textWeight(700)
            title.minLines = 1
            title.maxLines = 1
            title.setPadding(0, Styler.dpInt(context, 7f), 0, 0)
            subtitle.textSize = 11f
            subtitle.setTextColor(GLASS_CAPTION)
            subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
            subtitle.setPadding(0, Styler.dpInt(context, 1f), 0, 0)
            subtitle.visibility = VISIBLE
        }
        compactCard = (compact && !glass) || !captions
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
        if (glass) posterWrap.ratio = POSTER_RATIO
        // A recycled card keeps no day from the row it came from.
        dayChip?.visibility = GONE
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
            roundBadge(libraryBadge, if (libraryBadge == "✓") colors.badgeAvailable else colors.accent)
        } else if (showAvailability && availability.label.isNotEmpty()) {
            if (glass) glassAvailability(availability) else {
                badge.visibility = VISIBLE
                badge.text = availability.label
                badge.setBackgroundColor(badgeColour(availability))
                badge.setTextColor(SemanticColor.foreground(badgeColour(availability)))
            }
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
        if (glass) {
            // An audiobook's cover is square, as the prototype's Discover draws it.
            posterWrap.ratio = if (item.contentType == com.pocketds.hub.model.ReadingType.AUDIOBOOK) 1f else POSTER_RATIO
            if (item.inLibrary) glassAvailability(Availability.AVAILABLE) else badge.visibility = GONE
        } else if (item.inLibrary) {
            badge.visibility = VISIBLE
            badge.text = "Tracked"
            badge.setBackgroundColor(colors.badgeAvailable)
            badge.setTextColor(SemanticColor.foreground(colors.badgeAvailable))
        } else {
            badge.visibility = GONE
        }
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
        if (glass) {
            // The words stay on the page under the cover, as every Glass poster's;
            // an audiobook's cover is square.
            posterWrap.ratio = if (work.kind == com.pocketds.hub.model.ReadingType.AUDIOBOOK) 1f else POSTER_RATIO
        } else {
            title.setBackgroundColor(colors.cardSurface)
            subtitle.setBackgroundColor(colors.cardSurface)
            title.setPadding(Styler.dpInt(context,8f),Styler.dpInt(context,8f),Styler.dpInt(context,8f),0)
            subtitle.setPadding(Styler.dpInt(context,8f),Styler.dpInt(context,3f),Styler.dpInt(context,8f),Styler.dpInt(context,8f))
        }
        subtitle.ellipsize=android.text.TextUtils.TruncateAt.END
        showProgress(if (work.progress?.completed == true) 0.0 else work.progress?.percentage ?: 0.0)
        if (work.progress?.completed == true) {
            roundBadge("✓", colors.badgeAvailable)
        } else if (work.entityType == "collection" && work.bookCount > 0) {
            roundBadge(work.bookCount.toString(), colors.accent)
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
            work.progress?.let { append(", ").append(com.pocketds.hub.state.Fmt.readingPercent(it.percentage, it.completed)).append(" percent read") }
        }
    }

    /**
     * Keep a known series member in its correct place even when its file is not
     * local yet. Desaturating the artwork communicates absence without making
     * the title unreadable or relying on colour alone; missing cards are not
     * focus targets because there is no detail page they can open.
     */
    fun setReadingAvailability(available: Boolean) {
        if (available) {
            poster.clearColorFilter()
            poster.imageAlpha = 255
            title.alpha = 1f
            subtitle.alpha = 1f
            isClickable = true
            Styler.makeFocusable(this)
            return
        }
        poster.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        poster.imageAlpha = 105
        title.alpha = 1f
        subtitle.alpha = 1f
        showProgress(0.0)
        badge.visibility = VISIBLE
        badge.text = "Missing"
        badge.background = com.pocketds.hub.ui.ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 8f)
            setColor(this@PosterCardView.colors.stripBackground)
        }
        badge.setTextColor(colors.mutedText)
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    /**
     * How far in: the accent bar along the poster's foot, or Glass's white bar
     * inside it, with a comic's kind pill lifted clear of it.
     */
    private fun showProgress(fraction: Double) {
        progressBar.fraction = fraction
        val bar = glassBar ?: return
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
     * Glass: the day of a coming-up title on a strip of glass along the
     * poster's foot ("Tomorrow"), as the prototype marks them. Null removes it.
     */
    fun setDayChip(text: String?) {
        val chip = dayChip ?: return setCornerTag(text)
        chip.text = text.orEmpty()
        chip.visibility = if (text.isNullOrBlank()) GONE else VISIBLE
    }

    /** A word in an accent pill at the top corner: "Fri" on a coming-up title. Null removes it. */
    fun setCornerTag(text: String?) {
        if (text.isNullOrBlank()) { badge.visibility = GONE; return }
        badge.visibility = VISIBLE
        badge.text = text
        badge.minWidth = 0
        badge.textSize = 10f
        badge.background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 999f)
            setColor(this@PosterCardView.colors.accent)
        }
        badge.setTextColor(colors.accentText)
    }

    /** A count or ✓ in a coloured circle: watched, unwatched episodes, books in a collection. */
    private fun roundBadge(text: String, color: Int) {
        if (glass) return glassBadge(text)
        badge.visibility = VISIBLE
        badge.text = text
        badge.background = ThemeGradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
        }
        badge.minWidth = Styler.dpInt(context, 24f)
        badge.gravity = Gravity.CENTER
        badge.setTextColor(SemanticColor.foreground(color))
    }

    /**
     * Glass: a count (or a star) in a white pill with dark figures, a tick in
     * the accent's circle, as the prototype's posters carry them.
     */
    private fun glassBadge(text: String) {
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
            else ThemeGradientDrawable.rounded(Styler.dp(context, 11f), GLASS_COUNT)
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
     * Glass: where a title stands as a chip at the poster's top left (the
     * prototype's `.av`): In library, Partial, On the way, Requested, in the
     * badge colours of CLAUDE.md, with the glass edge and light.
     */
    private fun glassAvailability(availability: Availability) {
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

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** A Glass poster's second caption line: white at 64%. */
        const val GLASS_CAPTION = GlassColors.QUIET
        /** A Glass poster's count pill: white at 90%. */
        const val GLASS_COUNT = 0xE6FFFFFF.toInt()
        /** A comic's kind pill on Glass (the prototype's `.kind`): black at 62%, 6dp in from the corner. */
        const val GLASS_KIND = 0x9E000000.toInt()
        const val KIND_EDGE_DP = 6f
        const val POSTER_RATIO = 2f / 3f
    }
}
