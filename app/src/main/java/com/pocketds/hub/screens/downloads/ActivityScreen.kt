package com.pocketds.hub.screens.downloads

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.BandwidthChange
import com.pocketds.hub.model.BandwidthState
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.HostDisk
import com.pocketds.hub.model.ServiceHealth
import com.pocketds.hub.model.ServiceNames
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.discover.MediaDetailScreen
import com.pocketds.hub.screens.discover.UpcomingPresentation
import com.pocketds.hub.screens.discover.UpcomingScreen
import com.pocketds.hub.screens.manage.ServerMonitorScreen
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.PollCadence
import com.pocketds.hub.state.PollOutcome
import com.pocketds.hub.state.Poller
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusPlace
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.ProgressLine.showFraction
import com.pocketds.hub.ui.SettingsCard
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Activity tab: one glance at the server.
 *
 * Three columns of cards: what is downloading (with the qBittorrent speed
 * mode) and what needs attention; what is coming up; and every service, each
 * opening its own dashboard in the browser, above the disks. Every card is a
 * short selection, and the full screens -- all transfers, the calendar, the
 * server monitor -- are one press away.
 *
 * Books keep their own transfer list: in Books mode this tab shows it in place
 * of the dashboard.
 *
 * It is the prototype's dashboard (#11): "Activity" over a line saying how
 * things stand, the cards glass and the one that needs attention edged in
 * amber.
 */
class ActivityScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen, ContentModeScreen {

    override val title = "Activity"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private val books = DownloadsScreen(api, ringVisible)
    private lateinit var booksView: View
    private lateinit var root: FrameLayout
    private lateinit var dashboard: FocusScrollView
    private lateinit var columns: List<LinearLayout>

    private lateinit var downloading: SettingsCard
    private lateinit var transferRows: LinearLayout
    private lateinit var speed: BlobSegmentedView
    private lateinit var attentionCard: SettingsCard
    private lateinit var attentionRows: LinearLayout
    /** The line under the heading ([ActivityDashboard.headline]). */
    private lateinit var headline: TextView
    private lateinit var upcoming: SettingsCard
    private lateinit var agendaRows: LinearLayout
    private lateinit var services: SettingsCard
    private lateinit var serviceRows: LinearLayout
    private lateinit var storage: SettingsCard
    private lateinit var diskRows: LinearLayout

    private var mode = ContentMode.MEDIA
    private var visible = false
    private val poller = Poller(PollCadence.TRANSFERS)
    private var activity: ActivityResponse? = null
    private var activityError = ""
    private var health: HealthResponse? = null
    private var disks: List<HostDisk> = emptyList()
    private var bandwidth: BandwidthState? = null
    private var slowLoadedAt = 0L
    private val zone: ZoneId = ZoneId.systemDefault()
    private val imageLoader by lazy { Artwork.loader(api, checkNotNull(host).viewContext) }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)
        mode = ContentModeSettings.get(context)
        root = FrameLayout(context).apply { setBackgroundColor(colors.background) }

        downloading = card("Downloading")
        transferRows = column()
        downloading.body(transferRows, 8f, fill = true)
        speed = BlobSegmentedView(context, colors, ringVisible).apply {
            heightDp = 30f
            textSp = 11f
            useGlassTrack()
            setOptions(listOf(BlobSegmentedView.Option(NORMAL, "Normal speed"), BlobSegmentedView.Option(QUIET, "Quiet")), NORMAL)
            onPick = ::chooseSpeed
            visibility = View.GONE
        }
        downloading.body(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(speed)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(link("All transfers ›") { host.push(DownloadsScreen(api, ringVisible)) })
        }, 10f, fill = true)

        attentionRows = column()
        attentionCard = SettingsCard(context, colors).apply {
            title("Needs attention")
            attention(true)
            body(attentionRows, 2f, fill = true)
            visibility = View.GONE
        }

        upcoming = card("Upcoming")
        upcoming.headerAction(link("See all ›") { host.push(UpcomingScreen(api, ringVisible)) })
        agendaRows = column()
        upcoming.body(agendaRows, 6f, fill = true)

        services = card("Services")
        serviceRows = column()
        services.body(serviceRows, 6f, fill = true)

        storage = card("Storage")
        diskRows = column()
        storage.body(diskRows, 6f, fill = true)
        storage.apply {
            // The ring is drawn over the card's glass while it has focus.
            foreground = Styler.focusOutline(context, colors, SettingsCard.GLASS_CORNER_DP, 2f)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, false)
            contentDescription = "Storage. Opens the server monitor"
            activateOnTap { host.push(ServerMonitorScreen(api, ringVisible)) }
            visibility = View.GONE
        }

        columns = listOf(
            column(downloading, attentionCard),
            column(upcoming),
            column(services, storage)
        )
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            // The prototype's Pocket dashboard, 22dp in from the sides and 10dp between columns.
            setPadding(dp(22), dp(8), dp(22), dp(20))
            columns.forEachIndexed { i, c ->
                addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
        }
        dashboard = FocusScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                addView(TextView(context).apply {
                    text = "Activity"
                    textSize = 21f
                    typeface = Type.display(context, 800)
                    setTextColor(colors.primaryText)
                    includeFontPadding = false
                    setPadding(dp(22), dp(6), dp(22), 0)
                })
                headline = TextView(context).apply {
                    textSize = 12f
                    setTextColor(SettingsCard.GLASS_QUIET)
                    setPadding(dp(22), dp(6), dp(22), 0)
                }
                addView(headline)
                addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        root.addView(dashboard, FrameLayout.LayoutParams(MATCH, MATCH))

        booksView = books.onCreateView(host, root)
        root.addView(booksView, FrameLayout.LayoutParams(MATCH, MATCH))
        applyMode()
        renderAll()
        return root
    }

    // ---- lifecycle -----------------------------------------------------------

    override fun onShow() {
        visible = true
        val stored = host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) { mode = stored; applyMode() }
        if (mode == ContentMode.BOOKS) { books.onShow(); return }
        poller.start(scope, { visible && mode == ContentMode.MEDIA }) { fetchActivity() }
        loadSlow()
    }

    override fun onHide() {
        visible = false
        books.onHide()
        poller.stop()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        books.onDestroyView()
        scope.cancel()
        host = null
    }

    override fun selectContentMode(mode: ContentMode) {
        if (mode == this.mode) return
        this.mode = mode
        host?.viewContext?.let { ContentModeSettings.set(it, mode) }
        applyMode()
        if (mode == ContentMode.BOOKS) {
            poller.stop()
            if (visible) books.onShow()
        } else {
            books.onHide()
            if (visible) onShow()
        }
        host?.refreshHints()
    }

    private fun applyMode() {
        dashboard.visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        booksView.visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
    }

    // ---- loading -------------------------------------------------------------

    private suspend fun fetchActivity(): PollOutcome {
        // Services, disks and the calendar change slowly; they ride along every half minute.
        if (System.currentTimeMillis() - slowLoadedAt > SLOW_MS) loadSlow()
        return when (val result = api.activity()) {
            is HubResult.Ok -> {
                activity = result.value
                activityError = result.value.partial.firstOrNull { it.service == "qbittorrent" }?.message.orEmpty()
                rebuilt { renderTransfers(); renderAttention() }
                PollOutcome(ok = true, active = result.value.anyActive)
            }
            is HubResult.Failed -> {
                activityError = result.message
                rebuilt { renderTransfers() }
                PollOutcome(ok = false)
            }
        }
    }

    private fun loadSlow() {
        slowLoadedAt = System.currentTimeMillis()
        scope.launch {
            (api.health() as? HubResult.Ok)?.value?.let { health = it; rebuilt { renderServices(); renderAttention() } }
        }
        scope.launch {
            (api.serverMonitor() as? HubResult.Ok)?.value?.let { disks = it.host.disks; rebuilt { renderStorage(); renderAttention() } }
        }
        scope.launch {
            bandwidth = (api.bandwidth() as? HubResult.Ok)?.value
            renderSpeed()
        }
        scope.launch {
            val today = LocalDate.now(zone)
            // The hub serves at most 31 days: a fortnight back for anything missed, a little over two weeks ahead.
            when (val result = api.calendar(today.minusDays(14).toString(), today.plusDays(17).toString(), zone.id)) {
                is HubResult.Ok -> rebuilt { renderAgenda(result.value.items) }
                is HubResult.Failed -> if (agendaRows.childCount == 0) agendaRows.addView(quiet(result.message))
            }
        }
    }

    private fun refresh() {
        loadSlow()
        if (poller.isRunning) poller.pollNow()
    }

    // ---- rendering -----------------------------------------------------------

    private fun renderAll() = rebuilt {
        renderTransfers(); renderAttention(); renderServices(); renderStorage(); renderSpeed()
    }

    /** Rows drawn again, while away too (a poll on coming back): the place Back returns to moves with them (#23). */
    private fun rebuilt(draw: () -> Unit) = if (::root.isInitialized) FocusPlace.across(root, draw) else draw()

    /** Glass: what the cards below say, in one line under the heading. */
    private fun renderHeadline() {
        headline.text = ActivityDashboard.headline(activity, ActivityDashboard.attention(activity, health, disks).size, health)
    }

    private fun renderTransfers() {
        val focused = focusedTag(transferRows)
        transferRows.removeAllViews()
        val body = activity
        val s = body?.summary
        downloading.trailing(if (s != null && (s.downSpeedBytes > 0 || s.upSpeedBytes > 0))
            "↓ ${Fmt.speed(s.downSpeedBytes)} · ↑ ${Fmt.speed(s.upSpeedBytes)}" else "")
        val rows = body?.let { ActivityDashboard.transfers(it) }.orEmpty()
        when {
            body == null && activityError.isBlank() -> transferRows.addView(quiet("Asking the hub…"))
            rows.isEmpty() && activityError.isNotBlank() -> transferRows.addView(quiet(activityError))
            rows.isEmpty() -> transferRows.addView(quiet(if ((s?.seeding ?: 0) > 0) "Nothing downloading · ${s?.seeding} seeding" else "Nothing downloading"))
        }
        rows.forEach { item ->
            transferRows.addView(focusRow("transfer:${item.id}", "${item.headline}, ${ActivityDashboard.transferLine(item)}") {
                host?.push(DownloadsScreen(api, ringVisible))
            }.apply {
                orientation = LinearLayout.VERTICAL
                addView(text(item.headline, 12f, colors.primaryText, 600).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                addView(ProgressLine.create(context, colors).apply { showFraction(item.progress); visibility = View.VISIBLE },
                    LinearLayout.LayoutParams(MATCH, dp(5)).apply { topMargin = dp(4); bottomMargin = dp(3) })
                addView(text(ActivityDashboard.transferLine(item), 10.5f, colors.mutedText))
            })
        }
        restoreFocus(transferRows, focused)
    }

    private fun renderSpeed() {
        val value = bandwidth
        speed.visibility = if (value != null && value.canControl && value.modeSwitchSupported) View.VISIBLE else View.GONE
        if (value != null) speed.select(if (value.mode == "alternative") QUIET else NORMAL, animate = false)
    }

    private fun chooseSpeed(id: String) {
        val wanted = if (id == QUIET) "alternative" else "normal"
        if (bandwidth?.mode == wanted) return
        speed.select(id)
        scope.launch {
            when (val result = api.setBandwidth(BandwidthChange(mode = wanted))) {
                is HubResult.Ok -> { bandwidth = result.value; renderSpeed(); host?.notify(if (id == QUIET) "Quiet: qBittorrent's alternative limits" else "Normal speed") }
                is HubResult.Failed -> { renderSpeed(); host?.notify(result.message) }
            }
        }
    }

    private fun renderAttention() {
        val focused = focusedTag(attentionRows)
        attentionRows.removeAllViews()
        val entries = ActivityDashboard.attention(activity, health, disks)
        attentionCard.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        renderHeadline()
        entries.forEach { entry ->
            attentionRows.addView(text(entry.title, 12f, colors.primaryText, 600).apply { setPadding(0, dp(8), 0, 0) })
            attentionRows.addView(text(entry.detail, 11f, colors.mutedText).apply { setLineSpacing(0f, 1.15f); setPadding(0, dp(2), 0, 0) })
            if (entry.transferId.isNotBlank()) {
                attentionRows.addView(PillButton.create(checkNotNull(host).viewContext, colors, "Why is this stuck?", heightDp = 30f).apply {
                    tag = "why:${entry.transferId}"
                    FocusDecorator.attach(this, ringVisible, false)
                    activateOnTap { host?.push(DownloadsScreen(api, ringVisible, targetTransferId = entry.transferId)) }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            }
        }
        restoreFocus(attentionRows, focused)
    }

    private fun renderAgenda(items: List<com.pocketds.hub.model.CalendarItem>) {
        val focused = focusedTag(agendaRows)
        agendaRows.removeAllViews()
        val entries = ActivityDashboard.agenda(items, Instant.now(), zone)
        if (entries.isEmpty()) agendaRows.addView(quiet("Nothing scheduled in the next two weeks"))
        entries.forEachIndexed { i, entry ->
            val today = entry.heading.startsWith("Today")
            // One heading per day, however many titles share it.
            if (i == 0 || entries[i - 1].heading != entry.heading) agendaRows.addView(text(entry.heading, 9.5f, when {
                entry.state == UpcomingPresentation.ReleaseState.MISSING -> colors.dangerText
                today -> colors.accent
                else -> colors.mutedText
            }, 700).apply { isAllCaps = true; letterSpacing = 0.1f; setPadding(dp(6), dp(8), 0, dp(3)) })
            agendaRows.addView(focusRow("agenda:${entry.id}", "${entry.heading}, ${entry.media.title}, ${entry.line}, ${entry.state.label}") {
                if (entry.media.key.isNotBlank()) host?.push(MediaDetailScreen(api, entry.media.key, entry.media.title, ringVisible))
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                    background = ThemeGradientDrawable.rounded(Styler.dp(context, 5f), colors.posterPlaceholder)
                    Artwork.bind(this, imageLoader, api.imageUrl(entry.media.poster).takeIf { entry.media.poster.isNotBlank() }, opaque = true)
                }, LinearLayout.LayoutParams(dp(26), dp(38)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(9), 0, dp(6), 0)
                    addView(text(entry.media.title, 12f, colors.primaryText, 600).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                    addView(text(entry.line, 11f, colors.mutedText))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(badge(entry.state))
            })
        }
        restoreFocus(agendaRows, focused)
    }

    private fun renderServices() {
        val focused = focusedTag(serviceRows)
        serviceRows.removeAllViews()
        val value = health
        if (value == null) { serviceRows.addView(quiet("Asking the hub…")); return }
        services.trailing(ActivityDashboard.servicesSummary(value),
            if (value.services.all { it.state == "up" || it.state == "disabled" }) colors.badgeAvailable else colors.dangerText)
        value.services.filter { it.state != "disabled" }.sortedBy { ServiceNames.rank(it.name) }.forEach { service ->
            val name = ServiceNames.display(service.name)
            val meta = ActivityDashboard.serviceMeta(service)
            serviceRows.addView(focusRow("service:${service.name}", "$name, $meta. Opens in the browser") { openDashboard(service) }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(6), dp(5))
                addView(com.pocketds.hub.ui.DashboardParts.dot(context, com.pocketds.hub.ui.DashboardParts.stateColor(colors, service.state)))
                addView(text(name, 12f, colors.primaryText, 600), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(text(meta, 11f, if (service.state == "up") colors.mutedText else colors.dangerText))
                addView(ImageView(context).apply { setImageDrawable(AppIconDrawable(AppIcon.OPEN, colors.mutedText)) },
                    LinearLayout.LayoutParams(dp(11), dp(11)).apply { marginStart = dp(7) })
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        restoreFocus(serviceRows, focused)
    }

    /**
     * The service's own web page, in the browser. An address naming the media
     * PC's loopback would look for the service on the Pocket, so it is named
     * as the problem instead of opening a blank page.
     */
    private fun openDashboard(service: ServiceHealth) {
        val name = ServiceNames.display(service.name)
        val url = service.dashboardUrl
        if (!ActivityDashboard.reachableFromPocket(url)) {
            host?.notify("$name has no address the Pocket can reach · set services.${service.name}.web_url in hub.yaml")
            return
        }
        try {
            host?.viewContext?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            host?.notify("No browser is available to open $name")
        } catch (_: SecurityException) {
            host?.notify("Android blocked the $name address")
        }
    }

    private fun renderStorage() {
        diskRows.removeAllViews()
        storage.visibility = if (disks.isEmpty()) View.GONE else View.VISIBLE
        disks.forEach { disk ->
            diskRows.addView(com.pocketds.hub.ui.DashboardParts.disk(checkNotNull(host).viewContext, colors, disk), LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---- input ---------------------------------------------------------------

    override fun hints(): List<ButtonHint> {
        if (mode == ContentMode.BOOKS) return books.hints()
        val tag = root.findFocus()?.let(::rowTag).orEmpty()
        val activate = when {
            tag.startsWith("service:") -> "Open in browser"
            tag.startsWith("transfer:") -> "All transfers"
            tag.startsWith("agenda:") -> "Details"
            tag.startsWith("why:") -> "Why is this stuck?"
            tag.startsWith("link:") -> tag.removePrefix("link:").removeSuffix(" ›")
            storage.hasFocus() -> "Server monitor"
            speed.hasFocus() -> "Choose"
            else -> "Open"
        }
        return listOf(ButtonHint.activate(activate), ButtonHint.secondary("Speed limits"), ButtonHint.refresh())
    }

    override fun requestInitialFocus(): Boolean {
        if (::root.isInitialized && FocusPlace.focus(root)) return true
        if (mode == ContentMode.BOOKS) return books.requestInitialFocus()
        return columns.firstNotNullOfOrNull { focusables(it).firstOrNull() }?.requestFocus() ?: false
    }

    override fun onPad(action: PadAction): Boolean {
        if (mode == ContentMode.BOOKS) return books.onPad(action)
        return when (action) {
            is PadAction.Step -> step(action.direction)
            PadAction.Secondary -> { host?.push(BandwidthScreen(api, ringVisible)); true }
            PadAction.Refresh -> { refresh(); true }
            else -> false
        }
    }

    /**
     * Up and down walk the column you are in; left and right go to the nearest
     * thing at the same height in the next column that has anything. Up from
     * the top of a column is left to the app, which takes it to the tabs.
     */
    private fun step(direction: Direction): Boolean {
        val current = root.findFocus() ?: return false
        val index = columns.indexOfFirst { isInside(current, it) }
        if (index < 0) return false
        val here = focusables(columns[index])
        return when (direction) {
            // To the row above or below, never along this one, and to the nearest thing on it.
            Direction.UP, Direction.DOWN -> {
                val sign = if (direction == Direction.DOWN) 1 else -1
                val cy = centreY(current)
                val cx = centreX(current)
                val next = here.filter { (centreY(it) - cy) * sign > current.height / 2 }
                    .minWithOrNull(compareBy({ kotlin.math.abs(centreY(it) - cy) }, { kotlin.math.abs(centreX(it) - cx) }))
                when {
                    next != null -> next.requestFocus()
                    // Up from the top of a column goes to the tabs. Left to the app's own search,
                    // it found "See all" in the next column, higher than Normal speed.
                    direction == Direction.UP -> host?.focusTabs() ?: false
                    else -> true
                }
            }
            Direction.LEFT, Direction.RIGHT -> {
                val delta = if (direction == Direction.LEFT) -1 else 1
                // First along the row you are on (Normal speed | Quiet, All transfers), then across columns.
                val cx = centreX(current)
                val along = here.filter { it !== current && kotlin.math.abs(centreY(it) - centreY(current)) <= current.height / 2 &&
                    (centreX(it) - cx) * delta > 0 }.minByOrNull { kotlin.math.abs(centreX(it) - cx) }
                if (along != null) { along.requestFocus(); return true }
                val target = generateSequence(index + delta) { it + delta }.takeWhile { it in columns.indices }
                    .map { focusables(columns[it]) }.firstOrNull { it.isNotEmpty() }
                target?.minByOrNull { kotlin.math.abs(centreY(it) - centreY(current)) }?.requestFocus()
                true
            }
        }
    }

    private fun focusables(column: ViewGroup): List<View> =
        column.getFocusables(View.FOCUS_DOWN).filter { it.isShown }.sortedWith(compareBy({ centreY(it) }, { centreX(it) }))

    private fun centreX(view: View): Int {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return at[0] + view.width / 2
    }

    private fun centreY(view: View): Int {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return at[1] + view.height / 2
    }

    private fun isInside(view: View, parent: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    // ---- building blocks -----------------------------------------------------

    /** A card with its own bold heading, as the prototype's are. */
    private fun card(title: String): SettingsCard = SettingsCard(checkNotNull(host).viewContext, colors).apply {
        title(title)
    }

    private fun column(vararg children: View): LinearLayout = LinearLayout(checkNotNull(host).viewContext).apply {
        orientation = LinearLayout.VERTICAL
        // A focused card draws its ring just outside itself.
        clipChildren = false
        children.forEachIndexed { i, child ->
            addView(child, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = dp(10) })
        }
    }

    /** A focusable row with the ring and a quiet fill while focused. */
    private fun focusRow(id: String, description: String, onActivate: () -> Unit): LinearLayout =
        LinearLayout(checkNotNull(host).viewContext).apply {
            tag = id
            setPadding(dp(6), dp(5), dp(6), dp(6))
            background = Styler.selectionBackground(context, colors, selected = false, cornerDp = 8f)
            contentDescription = description
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, _ -> host?.refreshHints() }
            activateOnTap { onActivate() }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) }
        }

    private fun link(label: String, onActivate: () -> Unit): TextView = TextView(checkNotNull(host).viewContext).apply {
        text = label
        textSize = 11.5f
        textWeight(700)
        setTextColor(colors.accent)
        setPadding(dp(8), dp(5), dp(6), dp(5))
        tag = "link:$label"
        background = Styler.selectionBackground(context, colors, selected = false, cornerDp = 99f)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, false)
        activateOnTap { onActivate() }
    }

    private fun badge(state: UpcomingPresentation.ReleaseState): TextView =
        com.pocketds.hub.ui.DashboardParts.releaseChip(checkNotNull(host).viewContext, state)

    private fun text(value: String, size: Float, color: Int, weight: Int = 400): TextView = TextView(checkNotNull(host).viewContext).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (weight != 400) textWeight(weight)
    }

    private fun quiet(value: String) = text(value, 11.5f, colors.mutedText).apply { setPadding(dp(6), dp(6), dp(6), dp(4)) }

    private fun rowTag(view: View): String? {
        var v: View? = view
        while (v != null) {
            (v.tag as? String)?.let { return it }
            v = v.parent as? View
        }
        return null
    }

    private fun focusedTag(rows: ViewGroup): String? = rows.findFocus()?.let(::rowTag)

    /** A rebuilt card keeps the selection on the row it was on, or the nearest one left. */
    private fun restoreFocus(rows: ViewGroup, tag: String?) {
        if (tag == null) return
        val again = rows.findViewWithTag<View>(tag) ?: focusables(rows).firstOrNull()
        (again ?: columns.firstNotNullOfOrNull { focusables(it).firstOrNull() })?.requestFocus()
    }

    private fun dp(value: Int) = Styler.dpInt(checkNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val NORMAL = "normal"
        const val QUIET = "quiet"
        const val SLOW_MS = 30_000L
    }
}
