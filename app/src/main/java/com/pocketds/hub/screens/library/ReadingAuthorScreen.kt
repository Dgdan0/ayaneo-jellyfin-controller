package com.pocketds.hub.screens.library

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingAuthor
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.InitialsDrawable
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.showStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * One author: a row per series, each in reading order, then their books
 * outside a series. Brandon Sanderson is a Mistborn row and a Stormlight row;
 * Blake Crouch, with no series, is one row of books. A series title opens the
 * series page; a book opens the book.
 *
 * On Glass it is the prototype's author page (`.pg-author`): a round portrait
 * in a white ring, AUTHOR over the name, the shelf, then each series as a glass
 * pill over its row of covers.
 */
class ReadingAuthorScreen(
    private val api: HubApi,
    private val libraryId: String,
    private val author: ReadingAuthor,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = author.name
    override val contentDomain = ContentMode.BOOKS
    override val focusOnShow = true
    /** Glass: the name beside the portrait is the page's heading. */
    override val showsOwnTitle: Boolean get() = glass
    /** Glass: the page takes the colours of the author's first series, at the book being read. */
    override val pageArtwork: String?
        get() = items?.let { values ->
            values.firstOrNull { it.entityType == "collection" }?.let { it.continueAt?.artwork?.takeIf(String::isNotBlank) ?: it.artwork }
                ?: values.firstOrNull()?.artwork
        }?.takeIf(String::isNotBlank)
    private var glass = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var counts: TextView
    private lateinit var shelves: LinearLayout
    private lateinit var portrait: ImageView
    private var portraitArtwork = author.artwork
    private var shelfLine = AuthorLabels.shelf(author.seriesCount, author.bookCount, author.total)
    private var items: List<ReadingWork>? = null
    private val focusables = linkedMapOf<String, View>()
    private var lastFocusKey = ""

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        glass = Theme.onGlass(colors)
        val context = host.viewContext
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
            setPadding(0, dp(if (glass) 8 else 12), 0, dp(24))
        }
        val face = dp(if (glass) GLASS_FACE_DP else 76)
        column.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(dp(if (glass) GLASS_EDGE_DP else 24), 0, dp(if (glass) GLASS_EDGE_DP else 24), dp(4))
            // Round, as on the Authors grid: a person, not a cover.
            addView(FrameLayout(context).apply {
                clipToOutline = true
                background = com.pocketds.hub.ui.ThemeGradientDrawable.oval(colors.posterPlaceholder)
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                portrait = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(portrait, FrameLayout.LayoutParams(MATCH, MATCH))
                // Glass: the prototype's white ring round the face (`.authface`), and its shadow.
                if (glass) {
                    foreground = com.pocketds.hub.ui.ThemeGradientDrawable.oval(android.graphics.Color.TRANSPARENT, dp(4), GLASS_FACE_RING)
                    elevation = Styler.dp(context, 12f)
                }
                bindPortrait()
            }, LinearLayout.LayoutParams(face, face))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), 0, 0, 0)
                if (glass) addView(TextView(context).apply {
                    text = "Author"
                    isAllCaps = true
                    com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.EYEBROW, 10.5f)
                    setTextColor(com.pocketds.hub.ui.glass.GlassColors.EYEBROW)
                    setPadding(0, 0, 0, dp(6))
                })
                addView(TextView(context).apply {
                    text = author.name
                    com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HERO, if (glass) 30f else 28f)
                    if (glass) typeface = com.pocketds.hub.ui.Type.display(context, 800)
                    setTextColor(colors.primaryText)
                })
                counts = TextView(context).apply {
                    text = AuthorLabels.shelf(author.seriesCount, author.bookCount, author.total)
                    textSize = if (glass) 12f else 12.5f
                    setTextColor(if (glass) com.pocketds.hub.ui.glass.GlassColors.FACTS else colors.mutedText)
                    setPadding(0, dp(if (glass) 6 else 5), 0, 0)
                }
                addView(counts)
            })
        })
        status = TextView(context).apply {
            textSize = 11f; setTextColor(colors.mutedText)
            setPadding(dp(if (glass) GLASS_EDGE_DP else 24), dp(4), dp(if (glass) GLASS_EDGE_DP else 24), 0)
        }
        column.addView(status)
        shelves = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        column.addView(shelves)
        return FrameLayout(context).apply {
            setBackgroundColor(colors.background)
            // Room for the series title above a focused row of books.
            addView(FocusScrollView(context, revealAbove = dp(48)).apply {
                clipToPadding = false; clipChildren = false
                addView(column)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onShow() { if (items == null) load() else requestInitialFocus() }
    override fun onHide() { scope.coroutineContext.cancelChildren() }
    override fun onDestroyView() { scope.cancel(); host = null }

    override fun hints() = listOf(ButtonHint.activate("Open"), ButtonHint.back(), ButtonHint.refresh())

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Refresh -> { load(); true }
        else -> false
    }

    override fun requestInitialFocus(): Boolean =
        (focusables[lastFocusKey] ?: focusables.values.firstOrNull())?.requestFocus() == true

    /** The author asked for alone carries each series' books; every page of the shelf is read. */
    private fun load() {
        status.showStatus(StatusText.loading(author.name, refreshing = items != null), colors)
        scope.launch {
            val collected = mutableListOf<ReadingWork>()
            var page = 1
            var pages = 1
            while (page <= pages) {
                when (val result = api.readingAuthors(libraryId, page, "asc", author.id)) {
                    is HubResult.Ok -> {
                        val shelf = result.value.authors.firstOrNull()
                        collected += shelf?.items.orEmpty()
                        pages = shelf?.totalPages ?: 1
                        shelf?.let {
                            shelfLine = AuthorLabels.shelf(it.seriesCount, it.bookCount, it.total)
                            counts.text = shelfLine
                            // Opened from a book's author link, only the name was known.
                            if (portraitArtwork.isBlank() && it.artwork.isNotBlank()) { portraitArtwork = it.artwork; bindPortrait() }
                        }
                    }
                    is HubResult.Failed -> {
                        status.showStatus(StatusText.failed(result.message, result.kind, hasData = items != null), colors)
                        return@launch
                    }
                }
                page++
            }
            items = collected.distinctBy(ReadingWork::id)
            status.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val context = requireNotNull(host).viewContext
        shelves.removeAllViews(); focusables.clear()
        val values = items.orEmpty()
        val series = values.filter { it.entityType == "collection" }
        val books = values.filter { it.entityType != "collection" }
        // Under the name: the shelf, then how far through it you are.
        val everyBook = series.flatMap { it.sections.firstOrNull()?.items.orEmpty().map { item -> item.progress } } +
            books.map { it.progress }
        counts.text = listOfNotNull(shelfLine.ifEmpty { null }, AuthorLabels.reading(everyBook)).joinToString(" · ")
        for (collection in series) {
            val heading = seriesHeading(collection)
            shelves.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // Glass: the pill itself lines up with the page's edge; its ring has the room left of it.
                if (glass) setMargins(dp(GLASS_EDGE_DP - com.pocketds.hub.ui.PillButton.RING_DP.toInt()), dp(12), dp(GLASS_EDGE_DP), 0)
                else setMargins(dp(20), dp(16), dp(20), 0)
            })
            // "On #6 · 0 of 6 finished", as the series page says it.
            ReadingBookFacts.seriesProgress(collection)?.let { line ->
                shelves.addView(TextView(context).apply {
                    text = line; textSize = if (glass) 11.5f else 12f
                    setTextColor(if (glass) com.pocketds.hub.ui.glass.GlassColors.QUIET else colors.mutedText)
                    if (glass) setPadding(dp(GLASS_EDGE_DP), dp(2), dp(GLASS_EDGE_DP), 0) else setPadding(dp(32), dp(2), dp(24), 0)
                })
            }
            val members = collection.sections.firstOrNull()?.items?.takeIf { it.isNotEmpty() }
                ?: listOf(SeriesBookStrip.itemOf(collection))
            shelves.addView(SeriesBookStrip.create(context, colors, ringVisible, api, members) { card, item -> bindBook(card, item) })
        }
        if (books.isNotEmpty()) {
            val title = if (series.isEmpty()) "Books" else "Other books"
            shelves.addView(if (glass) com.pocketds.hub.ui.glass.GlassHeading.create(context, title, books.size.toString()).apply {
                setPadding(dp(GLASS_EDGE_DP), dp(14), dp(GLASS_EDGE_DP), 0)
            } else TextView(context).apply {
                text = title
                com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING); setTextColor(colors.primaryText); setPadding(dp(24), dp(18), dp(24), 0)
            })
            shelves.addView(SeriesBookStrip.create(context, colors, ringVisible, api, books.map(SeriesBookStrip::itemOf)) { card, item -> bindBook(card, item) })
        }
        if (values.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "No books by ${author.name} in this library"
        }
        shelves.post { if (shelves.isShown) requestInitialFocus() }
        host?.refreshHints()
        host?.pageArtworkChanged()
    }

    /**
     * "Mistborn · 3 books  ›": opens the series page. On Glass the prototype's
     * series pill (`.pill.glass`).
     */
    private fun seriesHeading(collection: ReadingWork): TextView {
        val count = collection.bookCount.takeIf { it > 0 }?.let { if (it == 1) " · 1 book" else " · $it books" }.orEmpty()
        val label = "${collection.title}$count  ›"
        val context = requireNotNull(host).viewContext
        return (if (glass) com.pocketds.hub.ui.PillButton.create(context, colors, label, com.pocketds.hub.ui.AppIcon.SERIES,
            heightDp = 30f, glass = true)
        else TextView(context).apply {
            text = label
            com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING); setTextColor(colors.primaryText)
            minHeight = dp(40); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = Styler.selectionBackground(context, colors, selected = false, cornerDp = 999f)
            Styler.makeFocusable(this)
        }).apply {
            contentDescription = "Open series ${collection.title}"
            remember("series:${collection.id}", this, ring = true)
            activateOnTap { host?.push(ReadingWorkScreen(api, collection.id, collection.title, ringVisible)) }
        }
    }

    private fun bindBook(card: View, item: ReadingSectionItem) {
        if (ReadingWorkPresentation.canOpen(item)) {
            remember("book:${item.workId}", card)
            card.activateOnTap { host?.push(ReadingWorkScreen(api, item.workId, item.title, ringVisible)) }
        } else {
            remember("missing:${item.number}:${item.title}", card)
            card.activateOnTap { host?.push(MissingReadingItemScreen(api, item, ringVisible)) }
        }
    }

    private fun bindPortrait() {
        val context = requireNotNull(host).viewContext
        Artwork.bind(portrait, Artwork.loader(api, context), portraitArtwork.takeIf { it.isNotBlank() }?.let(api::imageUrl),
            onMissing = { portrait.setImageDrawable(InitialsDrawable(author.name, colors)) }, opaque = true)
    }

    /** Book cards draw their own ring; the series title needs one. */
    private fun remember(key: String, view: View, ring: Boolean = false) {
        focusables[key] = view
        if (ring) FocusDecorator.attach(view, ringVisible, scale = false)
        FocusDecorator.listen(view, ringVisible) { _, focused -> if (focused) { lastFocusKey = key; host?.refreshHints() } }
    }

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Glass, the prototype's Pocket author page: a 90dp face in a white ring at 85%, 22dp edges. */
        const val GLASS_FACE_DP = 90
        const val GLASS_FACE_RING = 0xD9FFFFFF.toInt()
        const val GLASS_EDGE_DP = 22
    }
}
