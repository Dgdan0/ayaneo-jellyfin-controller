package com.pocketds.hub.screens.discover

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.dispose
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.CalendarResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.ui.textWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What your monitored films and series bring, a week at a time: the days down
 * the left with each release and whether it has arrived, the one you are on
 * described at the right.
 *
 * [embedded] in Discover, its week switch ([weekSwitch]) sits in Discover's
 * own top row, beside Discover | Upcoming; pushed on its own (Activity's "See
 * all"), it heads the page.
 *
 * It is the prototype's: the week switch a glass capsule, each release a
 * glass row with its state as a chip, the one in the preview lit in the
 * accent, and the preview a glass card whose picture tints the page.
 */
class UpcomingScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean,
    private val embedded: Boolean = false
) : Screen {
    override val title = "Upcoming"
    override val contentDomain = ContentMode.MEDIA
    /** The release in the preview. */
    override val pageArtwork: String? get() = selectedArt

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private var selectedArt: String? = null
    /** Each row's panel and the accent ring it wears while it is the one in the preview. */
    private val rowFaces = HashMap<View, Pair<com.pocketds.hub.ui.glass.GlassPanelDrawable, android.graphics.drawable.Drawable>>()
    private lateinit var agenda: LinearLayout
    private lateinit var details: LinearLayout
    private lateinit var status: TextView
    /** Last week | This week | Next week. L2 and R2 go further either way. */
    lateinit var weekSwitch: BlobSegmentedView
        private set
    private var detailAction: View? = null
    private val rows = mutableListOf<Pair<View, UpcomingPresentation.Group>>()
    private val artwork = mutableListOf<ImageView>()
    private var body: CalendarResponse? = null
    private var week = 0
    private var selectedId = ""
    private val anchor = LocalDate.now()
    private val zone get() = ZoneId.systemDefault()
    private val range get() = UpcomingPresentation.range(anchor, week)
    private val imageLoader by lazy { Artwork.loader(api, checkNotNull(host).viewContext) }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val context = host.viewContext
        weekSwitch = BlobSegmentedView(context, colors, ringVisible).apply {
            heightDp = 34f
            textSp = 12f
            useGlassTrack()
            setOptions((-1..1).map { BlobSegmentedView.Option(it.toString(), UpcomingPresentation.weekLabel(anchor, it)) }, "0")
            onPick = { id -> id.toIntOrNull()?.let(::showWeek) }
            onOptionFocused = { host.refreshHints() }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(dp(24), dp(if (embedded) 2 else 8), dp(24), dp(10))
            if (!embedded) addView(weekSwitch, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(4) })
            status = label("Loading schedule…", 11f, colors.mutedText).apply { setPadding(dp(2), dp(2), 0, dp(6)) }
            addView(status)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                agenda = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    setPadding(dp(4), dp(2), dp(8), dp(12))
                }
                details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                // Room for the day heading above a row, or it scrolls half under the status line.
                addView(FocusScrollView(context, revealAbove = dp(30)).apply {
                    isVerticalScrollBarEnabled = false
                    clipToPadding = false
                    addView(agenda)
                }, LinearLayout.LayoutParams(0, MATCH, 1f))
                addView(FocusScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    clipToPadding = false
                    setPadding(0, dp(2), 0, dp(12))
                    addView(details)
                }, LinearLayout.LayoutParams(dp(DETAIL_DP), MATCH).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() { if (body == null) load() }
    override fun onHide() { job?.cancel(); job = null }
    override fun onDestroyView() { scope.cancel(); artwork.forEach { it.dispose() }; host = null }

    override fun hints() = listOf(
        ButtonHint.activate(if (weekSwitch.hasFocus()) "Show week" else "Open"),
        ButtonHint.back(),
        ButtonHint("L2 / R2", "Week", PadAction.Page(Direction.RIGHT))
    )

    override fun requestInitialFocus(): Boolean =
        (rows.firstOrNull { it.second.id == selectedId }?.first ?: rows.firstOrNull()?.first)?.requestFocus()
            ?: weekSwitch.focus()

    override fun onPad(action: PadAction): Boolean {
        if (action == PadAction.Refresh) { load(); return true }
        if (action is PadAction.Page) {
            showWeek(week + if (action.direction == Direction.LEFT || action.direction == Direction.UP) -1 else 1)
            return true
        }
        if (action is PadAction.Step) {
            if (weekSwitch.hasFocus() && action.direction == Direction.DOWN) { requestInitialFocus(); return true }
            val index = rows.indexOfFirst { it.first.hasFocus() }
            if (index >= 0) when (action.direction) {
                Direction.UP -> { if (index > 0) rows[index - 1].first.requestFocus() else weekSwitch.focus(); return true }
                Direction.DOWN -> { rows.getOrNull(index + 1)?.first?.requestFocus(); return true }
                Direction.RIGHT -> { detailAction?.requestFocus(); return true }
                Direction.LEFT -> return true
            }
            if (action.direction == Direction.LEFT && detailAction?.hasFocus() == true) { requestInitialFocus(); return true }
        }
        return false
    }

    private fun showWeek(target: Int) {
        val next = target.coerceIn(-52, 52)
        if (next == week) return
        week = next
        // Beyond last and next week the switch shows nothing chosen; the status line says which week.
        weekSwitch.select(week.toString().takeIf { week in -1..1 })
        selectedId = ""
        body = null
        load()
        host?.refreshHints()
    }

    private fun load() {
        job?.cancel()
        body = null
        val requested = range
        status.showStatus(StatusText.loading("schedule", refreshing = false), colors)
        agenda.removeAllViews(); details.removeAllViews(); rows.clear(); rowFaces.clear(); detailAction = null
        artwork.forEach { it.dispose() }; artwork.clear()
        job = scope.launch {
            when (val result = api.calendar(requested.start.toString(), requested.endExclusive.toString(), zone.id)) {
                is HubResult.Ok -> { body = result.value; render(result.value) }
                is HubResult.Failed -> {
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = false, canRetry = false), colors)
                    details.addView(PillButton.create(checkNotNull(host).viewContext, colors, "Try again", AppIcon.REFRESH, glass = true).apply {
                        FocusDecorator.attach(this, ringVisible, scale = false)
                        activateOnTap { load() }
                    })
                }
            }
        }
    }

    private fun render(data: CalendarResponse) {
        val count = data.items.size
        status.showStatus(StatusText.loaded(listOfNotNull(
            UpcomingPresentation.rangeLabel(range).takeIf { week !in -1..1 },
            "$count ${if (count == 1) "release" else "releases"}"
        ).joinToString(" · "), unavailable = data.partial.map { it.service }), colors)
        val grouped = UpcomingPresentation.groups(data.items)
        val today = LocalDate.now()
        val now = Instant.now()
        for (day in UpcomingPresentation.days(range)) {
            val groups = grouped.filter { it.first.date == day.toString() }
            agenda.addView(dayHeading(day, today, empty = groups.isEmpty()))
            for (group in groups) {
                val row = releaseRow(group, now)
                agenda.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
                rows.add(row to group)
            }
        }
        val selected = grouped.firstOrNull { it.id == selectedId }
            ?: grouped.firstOrNull { week == 0 && it.first.date >= today.toString() }
            ?: grouped.firstOrNull()
        if (selected != null) {
            select(selected)
            agenda.post { requestInitialFocus() }
        } else {
            details.addView(label("Nothing this week", 15f, colors.primaryText).apply { textWeight(600) })
            details.addView(label("L2 and R2 move a week at a time.", 12f, colors.mutedText))
        }
    }

    /** "Fri 2 Oct" with a Today or Tomorrow tag, and "· Nothing scheduled" on an empty day. */
    private fun dayHeading(day: LocalDate, today: LocalDate, empty: Boolean): View = LinearLayout(checkNotNull(host).viewContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(8), 0, dp(5))
        addView(label(day.format(DAY), 11.5f, if (day == today) colors.accent else colors.primaryText).apply { textWeight(800) })
        val tag = when (day) { today -> "Today"; today.plusDays(1) -> "Tomorrow"; else -> "" }
        if (tag.isNotEmpty()) addView(label(tag, 10f, colors.accentText).apply {
            textWeight(700)
            setPadding(dp(7), dp(1), dp(7), dp(1))
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 99f), colors.accent)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        if (empty) addView(label("·  Nothing scheduled", 11f, QUIET), LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
    }

    private fun releaseRow(group: UpcomingPresentation.Group, now: Instant): View = LinearLayout(checkNotNull(host).viewContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // The prototype's row: glass, and lit in the accent while it is the one in the preview.
        setPadding(dp(5), dp(5), dp(8), dp(5))
        val panel = com.pocketds.hub.ui.glass.GlassPanelDrawable(
            com.pocketds.hub.ui.glass.GlassColors.panel(com.pocketds.hub.ui.glass.GlassPage.palette(context)), Styler.dp(context, 11f))
        val ring = ThemeGradientDrawable.rounded(Styler.dp(context, 11f), android.graphics.Color.TRANSPARENT, dp(2), colors.accent).apply { alpha = 0 }
        background = android.graphics.drawable.LayerDrawable(arrayOf(panel, ring))
        foreground = Styler.focusOutline(context, colors, 11f)
        rowFaces[this] = panel to ring
        com.pocketds.hub.ui.glass.GlassPage.follow(this) { page -> if (ring.alpha == 0) panel.retint(com.pocketds.hub.ui.glass.GlassColors.panel(page)) }
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        addView(poster(group.first.media.poster), LinearLayout.LayoutParams(dp(26), dp(39)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            addView(label(group.first.media.title, 12f, colors.primaryText).apply {
                textWeight(700); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            })
            addView(label(listOf(group.label, timeLabel(group)).joinToString(" · "), 10.5f, QUIET_LINE).apply { isSingleLine = true })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(badge(UpcomingPresentation.state(group, now, zone)))
        FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) { select(group); host?.refreshHints() } }
        activateOnTap { select(group); open(group) }
        contentDescription = listOf(group.first.media.title, group.label, timeLabel(group),
            UpcomingPresentation.state(group, now, zone).label).joinToString(", ")
    }

    /** The release in focus shown in the preview ([preview]). */
    private fun select(group: UpcomingPresentation.Group) {
        selectedId = group.id
        details.removeAllViews()
        val art = group.first.media.backdrop.ifBlank { group.first.media.poster }
        selectedArt = art.takeIf(String::isNotBlank)
        preview(group, art)
    }

    /**
     * The preview as the prototype's card -- the picture across its top with
     * the state on it, then the title in the display face, what it is, when,
     * and Open title in white -- and the row in the preview lit. No overview,
     * as the prototype has none: with one, Open title fell below the hint bar
     * on the Pocket.
     */
    private fun preview(group: UpcomingPresentation.Group, art: String) {
        val context = checkNotNull(host).viewContext
        val first = group.first
        val now = Instant.now()
        rows.forEach { (view, each) ->
            val (panel, ring) = rowFaces[view] ?: return@forEach
            val on = each.id == group.id
            ring.alpha = if (on) 255 else 0
            panel.retint(if (on) com.pocketds.hub.ui.glass.GlassColors.withAlpha(
                com.pocketds.hub.ui.glass.GlassColors.mix(com.pocketds.hub.ui.glass.GlassColors.PANEL_BASE, colors.accent, .22f), 0xC4)
                else com.pocketds.hub.ui.glass.GlassColors.panel(com.pocketds.hub.ui.glass.GlassPage.palette(context)))
        }
        val corner = Styler.dp(context, 15f)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, corner)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
            clipToOutline = true
        }
        card.addView(com.pocketds.hub.ui.ArtworkFrame(context, 16f / 9f, 0f).apply {
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                Artwork.bind(this, imageLoader, api.imageUrl(art).takeIf { art.isNotBlank() }, opaque = true)
                artwork.add(this)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(badge(UpcomingPresentation.state(group, now, zone)), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(10), 0, 0, dp(10))
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
        }
        words.addView(label(first.media.title, 18f, colors.primaryText).apply {
            typeface = Type.display(context, 800); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        words.addView(label(listOf(group.label, first.episodeTitle).filter(String::isNotBlank).joinToString(" · "), 12f, colors.primaryText)
            .apply { setPadding(0, dp(4), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        words.addView(label(LocalDate.parse(first.date).format(LONG_DAY) + " · " + timeLabel(group), 12f, QUIET_LINE)
            .apply { setPadding(0, dp(2), 0, 0) })
        detailAction = PillButton.create(context, colors, "Open title", AppIcon.INFO, primary = true, heightDp = 31f, glass = true).apply {
            isEnabled = first.media.key.isNotBlank()
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { open(group) }
        }
        words.addView(detailAction, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6); marginStart = -dp(PillButton.RING_DP.toInt()) })
        if (first.media.key.isBlank()) words.addView(label("Title details unavailable: missing metadata ID", 11f, QUIET_LINE))
        card.addView(words, LinearLayout.LayoutParams(MATCH, WRAP))
        details.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun open(group: UpcomingPresentation.Group) {
        if (group.first.media.key.isNotBlank()) host?.push(MediaDetailScreen(api, group.first.media.key, group.first.media.title, ringVisible))
        else host?.notify("No metadata ID is available for this title")
    }

    private fun badge(state: UpcomingPresentation.ReleaseState): TextView =
        com.pocketds.hub.ui.DashboardParts.releaseChip(checkNotNull(host).viewContext, state)

    private fun toneOf(state: UpcomingPresentation.ReleaseState) = when (state) {
        UpcomingPresentation.ReleaseState.MISSING -> colors.dangerText
        UpcomingPresentation.ReleaseState.AIRED -> colors.badgePending
        UpcomingPresentation.ReleaseState.IN_LIBRARY -> colors.badgeAvailable
        UpcomingPresentation.ReleaseState.SOON -> colors.mutedText
    }

    private fun timeLabel(group: UpcomingPresentation.Group): String {
        val times = group.items.mapNotNull { runCatching { Instant.parse(it.at).atZone(zone).format(TIME) }.getOrNull() }.distinct()
        return if (times.isEmpty()) "Time not announced" else if (times.size == 1) times.first() else "${times.first()} – ${times.last()}"
    }

    private fun poster(path: String): ImageView = ImageView(checkNotNull(host).viewContext).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = ThemeGradientDrawable.rounded(Styler.dp(context, 6f), colors.posterPlaceholder)
        clipToOutline = true
        Artwork.bind(this, imageLoader, api.imageUrl(path).takeIf { path.isNotBlank() }, opaque = true)
        artwork.add(this)
    }

    private fun label(value: String, size: Float, color: Int) = TextView(checkNotNull(host).viewContext).apply {
        text = value; textSize = size; setTextColor(color)
    }

    private fun dp(value: Int) = Styler.dpInt(checkNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** The preview card, as wide as the prototype's. */
        const val DETAIL_DP = 320
        /** "Nothing scheduled" white at 50%, a row's second line and the preview's quiet lines at 62%. */
        const val QUIET = 0x80FFFFFF.toInt()
        const val QUIET_LINE = 0x9EFFFFFF.toInt()
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    }
}
