package com.pocketds.hub.screens.notifications

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.R
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.NotificationSection
import com.pocketds.hub.model.NotificationsResponse
import com.pocketds.hub.model.ServiceNotice
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.NotificationReadStore
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.ui.DashboardParts
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.SemanticColor
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.state.PollCadence
import com.pocketds.hub.state.PollOutcome
import com.pocketds.hub.state.Poller
import com.pocketds.hub.ui.showStatus

/** Recent automation activity and current health, kept separate by service. */
class NotificationsScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean,
    private val onUnreadChanged: (Int) -> Unit
) : Screen, ContentModeScreen {

    override val title = "Notifications"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var deviceAlerts: TextView
    private val columns = linkedMapOf<String, ServiceColumnView>()
    private lateinit var mediaColumns: LinearLayout
    private lateinit var bookColumns: LinearLayout
    private var mode = ContentMode.MEDIA
    private val poller = Poller(PollCadence.NOTIFICATIONS)
    private var visible = false
    private var selectedID = ""
    private var hasContent = false
    private lateinit var readStore: NotificationReadStore
    private var unreadIds: Set<String> = emptySet()
    private var latestResponse: NotificationsResponse? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        mode = ContentModeSettings.get(host.viewContext)
        readStore = NotificationReadStore(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)

            // A heading with the summary under it, and this Pocket's own alerts as a pill beside it.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                setPadding(dp(24), dp(10), dp(24) - dp(PillButton.RING_DP.toInt()), dp(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "Notifications"
                        typeRole(Type.Role.SCREEN)
                        setTextColor(colors.primaryText)
                    })
                    status = TextView(context).apply {
                        text = "Loading activity…"
                        textSize = 12f
                        setTextColor(colors.mutedText)
                        setPadding(0, dp(4), 0, 0)
                    }
                    addView(status)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                deviceAlerts = PillButton.create(context, colors, "On this Pocket", heightDp = 36f).apply {
                    FocusDecorator.attach(this, ringVisible, false)
                    activateOnTap { host.push(LocalAlertsScreen(api, ringVisible)) }
                }
                addView(deviceAlerts)
            }, LinearLayout.LayoutParams(MATCH, WRAP))

            mediaColumns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
                setPadding(dp(18), 0, dp(18), dp(14))
                MEDIA_SERVICES.forEach { service ->
                    val column = ServiceColumnView(service)
                    columns[service] = column
                    addView(column, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        setMargins(dp(5), 0, dp(5), 0)
                    })
                }
            }
            bookColumns = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(18), 0, dp(18), dp(14))
                BOOK_SERVICES.forEach { service ->
                    val column = ServiceColumnView(service)
                    columns[service] = column
                    addView(column, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        setMargins(dp(5), 0, dp(5), 0)
                    })
                }
                visibility = if (mode == ContentMode.BOOKS) View.VISIBLE else View.GONE
            }
            addView(mediaColumns, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bookColumns, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() {
        val local = com.pocketds.hub.settings.LocalAlerts.unread(host.viewContext)
        deviceAlerts.text = if (local > 0) "On this Pocket · $local new" else "On this Pocket"
        deviceAlerts.contentDescription = "Downloads and subtitles on this Pocket, $local new"
        showMode(ContentModeSettings.get(host.viewContext))
        visible = true
        startPolling(showLoading = !hasContent)
        if (hasContent) requestInitialFocus()
    }

    override fun onHide() {
        visible = false
        poller.stop()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        columns.clear()
    }

    override fun hints(): List<ButtonHint> = listOfNotNull(
        selectedNotice()?.let { ButtonHint.activate("Show message") },
        ButtonHint.primary("Mark all seen").takeIf { unreadIds.isNotEmpty() },
        ButtonHint.refresh()
    )

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> selectedNotice()?.let {
            host.notify(if (it.detail.isEmpty()) it.title else "${it.title} · ${it.detail}")
            true
        } ?: false
        PadAction.Refresh -> {
            startPolling(showLoading = false)
            true
        }
        PadAction.Primary -> {
            markAllSeen()
            true
        }
        is PadAction.Step -> action.direction == Direction.DOWN &&
            visibleColumns().any { it.isLastItemFocused() }
        else -> false
    }

    override fun requestInitialFocus(): Boolean {
        if (selectedID.isNotEmpty()) {
            visibleColumns().firstOrNull { it.contains(selectedID) }?.let {
                return it.focus(selectedID)
            }
        }
        return visibleColumns().firstOrNull { it.hasItems() }?.focus("") == true || deviceAlerts.requestFocus()
    }

    private fun startPolling(showLoading: Boolean) {
        if (showLoading) {
            status.showStatus(StatusText.loading("activity", refreshing = false), colors)
        } else if (hasContent) {
            status.showStatus(StatusText.loading("activity", refreshing = true), colors)
        }
        poller.start(scope, { visible }) { fetchOnce() }
    }

    private suspend fun fetchOnce(): PollOutcome =
        when (val result = api.notifications(NotificationSettings.limits(host.viewContext))) {
            is HubResult.Ok -> {
                render(result.value)
                PollOutcome(ok = true)
            }
            is HubResult.Failed -> {
                status.showStatus(StatusText.failed(result.message, result.kind, hasData = hasContent), colors)
                PollOutcome(ok = false)
            }
        }

    private fun render(response: NotificationsResponse) {
        hasContent = true
        latestResponse = response
        val byService = response.sections.associateBy { it.service }
        (MEDIA_SERVICES + BOOK_SERVICES).forEach { service ->
            columns[service]?.bind(
                byService[service] ?: NotificationSection(service = service, state = "disabled")
            )
        }
        // The same answer the header badge gets. This used to re-read the
        // stored seen list instead, which is trimmed at a thousand entries, so
        // the badge and this screen could disagree and the count flipped
        // between them.
        unreadIds = readStore.observe(response.sections)
        columns.values.forEach { it.updateUnreadCount() }
        onUnreadChanged(unreadIds.size + com.pocketds.hub.settings.LocalAlerts.unread(host.viewContext))
        updateStatus(response)
        if (visibleColumns().none { it.contains(selectedID) }) selectedID = ""
        requestInitialFocus()
        host.refreshHints()
    }

    private fun markSeen(id: String, card: NoticeCardView) {
        if (id !in unreadIds) return
        readStore.markSeen(id)
        unreadIds = unreadIds - id
        card.setUnread(false)
        columns.values.forEach { it.updateUnreadCount() }
        onUnreadChanged(unreadIds.size + com.pocketds.hub.settings.LocalAlerts.unread(host.viewContext))
        latestResponse?.let(::updateStatus)
        host.refreshHints()
    }

    private fun markAllSeen() {
        if (unreadIds.isEmpty()) {
            host.notify("All notifications are already seen")
            return
        }
        val ids = latestResponse?.sections.orEmpty().flatMap { it.items }.map { it.id }
        readStore.markAllSeen(ids)
        unreadIds = emptySet()
        columns.values.forEach { it.refreshUnread() }
        onUnreadChanged(com.pocketds.hub.settings.LocalAlerts.unread(host.viewContext))
        latestResponse?.let(::updateStatus)
        host.notify("All notifications marked as seen")
        host.refreshHints()
    }

    private fun updateStatus(response: NotificationsResponse) {
        val summary = when {
            response.sections.all { it.items.isEmpty() } && response.partial.isEmpty() -> "No recent activity or service warnings."
            unreadIds.isNotEmpty() -> "${unreadIds.size} unread notification${if (unreadIds.size == 1) "" else "s"}"
            response.attentionCount > 0 -> "${response.attentionCount} current service issue${if (response.attentionCount == 1) "" else "s"} · all seen"
            response.partial.isNotEmpty() || response.cache.stale -> "Recent activity"
            else -> "Recent activity · all services responding"
        }
        status.showStatus(StatusText.loaded(summary, response.cache, response.partial.map { it.service }), colors)
    }

    private fun selectedNotice(): ServiceNotice? =
        visibleColumns().firstNotNullOfOrNull { it.focusedNotice() }

    private fun visibleColumns(): List<ServiceColumnView> =
        (if (mode == ContentMode.MEDIA) MEDIA_SERVICES else BOOK_SERVICES).mapNotNull(columns::get)

    private fun showMode(selected: ContentMode) {
        if (mode == selected) return
        mode = selected
        mediaColumns.visibility = if (selected == ContentMode.MEDIA) View.VISIBLE else View.GONE
        bookColumns.visibility = if (selected == ContentMode.BOOKS) View.VISIBLE else View.GONE
        selectedID = ""
        host.refreshHints()
    }

    override fun selectContentMode(mode: ContentMode) = showMode(mode)

    private inner class ServiceColumnView(private val service: String) : LinearLayout(host.viewContext) {
        private val count: TextView
        private val state: TextView
        private lateinit var stateDot: View
        private val empty: TextView
        private val list: RecyclerView
        private val adapter = NoticeAdapter()

        init {
            orientation = VERTICAL
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 16f), this@NotificationsScreen.colors.cardSurface)
            setPadding(dp(8), dp(10), dp(8), dp(6))

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    if (service in BOOK_SERVICES) setImageDrawable(AppIconDrawable(if (service == "kavita") AppIcon.COMIC else AppIcon.BOOK, colors.primaryText))
                    else com.pocketds.hub.ui.ServiceLogo.bind(this,serviceLogo(service))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(9) })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = displayName(service)
                        typeRole(Type.Role.HEADING, 15f)
                        setTextColor(colors.primaryText)
                    })
                    addView(LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(3), 0, 0)
                        stateDot = DashboardParts.dot(context, colors.mutedText)
                        addView(stateDot)
                        state = TextView(context).apply {
                            textSize = 11f
                            setTextColor(colors.mutedText)
                        }
                        addView(state)
                    })
                }, LayoutParams(0, WRAP, 1f))
                count = TextView(context).apply {
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(colors.accentText)
                    background = pill(colors.accent)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                }
                addView(count)
            }, LayoutParams(MATCH, dp(42)).apply { setMargins(dp(5), 0, dp(5), dp(5)) })

            val body = android.widget.FrameLayout(context)
            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@ServiceColumnView.adapter
                itemAnimator = null
                clipToPadding = false
                setPadding(0, dp(3), 0, dp(12))
            }
            body.addView(list, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            empty = TextView(context).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(colors.mutedText)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            body.addView(empty, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            addView(body, LayoutParams(MATCH, 0, 1f))
        }

        fun bind(section: NotificationSection) {
            // A partial refresh keeps the last good history. The state line says
            // which service failed, so retaining it cannot be mistaken for live data.
            if (section.state == "up" || adapter.itemCount == 0 || section.state == "disabled") {
                adapter.submit(section.items)
            }
            state.text = stateLabel(section.state)
            state.setTextColor(
                when (section.state) {
                    "up" -> colors.mutedText
                    "disabled" -> colors.mutedText
                    "degraded" -> colors.badgePending
                    else -> colors.dangerText
                }
            )
            stateDot.background = com.pocketds.hub.ui.ThemeGradientDrawable.oval(DashboardParts.stateColor(colors, section.state))
            updateUnreadCount()
            empty.text = when (section.state) {
                "disabled" -> "Not configured"
                "unavailable" -> "Service unavailable\nSelect retries"
                "degraded" -> "Some activity unavailable"
                else -> "No recent activity"
            }
            empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
            list.visibility = if (adapter.itemCount == 0) View.GONE else View.VISIBLE
        }

        fun hasItems(): Boolean = adapter.itemCount > 0
        fun contains(id: String): Boolean = adapter.indexOf(id) >= 0
        fun itemIds(): List<String> = adapter.ids()
        fun focusedNotice(): ServiceNotice? = list.findFocus()?.getTag(TAG_NOTICE) as? ServiceNotice
        fun isLastItemFocused(): Boolean {
            val focused = list.findFocus() ?: return false
            return list.getChildAdapterPosition(focused) == adapter.itemCount - 1
        }

        fun updateUnreadCount() {
            val unread = adapter.unreadCount()
            count.text = unread.toString()
            count.background = pill(colors.badgeFailed)
            count.setTextColor(SemanticColor.foreground(colors.badgeFailed))
            count.visibility = if (unread > 0) View.VISIBLE else View.GONE
            count.contentDescription = "$unread unread ${displayName(service)} notifications"
        }

        fun refreshUnread() {
            adapter.notifyDataSetChanged()
            updateUnreadCount()
        }

        fun focus(id: String): Boolean {
            val position = if (id.isEmpty()) 0 else adapter.indexOf(id).coerceAtLeast(0)
            if (position !in 0 until adapter.itemCount) return false
            list.scrollToPosition(position)
            list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            return true
        }

        private inner class NoticeAdapter : RecyclerView.Adapter<NoticeHolder>() {
            private val items = mutableListOf<ServiceNotice>()

            init { setHasStableIds(true) }

            fun submit(values: List<ServiceNotice>) {
                if (items == values) return
                items.clear()
                items.addAll(values)
                notifyDataSetChanged()
            }

            fun indexOf(id: String): Int = items.indexOfFirst { it.id == id }
            fun ids(): List<String> = items.map { it.id }
            fun unreadCount(): Int = items.count { it.id in unreadIds }
            override fun getItemCount(): Int = items.size
            override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoticeHolder {
                val card = NoticeCardView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                        setMargins(dp(2), dp(3), dp(2), dp(3))
                    }
                    FocusDecorator.attach(this, ringVisible, scale = false)
                }
                return NoticeHolder(card)
            }

            override fun onBindViewHolder(holder: NoticeHolder, position: Int) {
                val notice = items[position]
                val card = holder.itemView as NoticeCardView
                card.bind(notice, notice.id in unreadIds)
                card.activateOnTap {
                    markSeen(notice.id, card)
                    host.notify(if (notice.detail.isEmpty()) notice.title else "${notice.title} · ${notice.detail}")
                }
                FocusDecorator.listen(card, ringVisible) { _, focused ->
                    if (focused) {
                        selectedID = notice.id
                        markSeen(notice.id, card)
                        host.refreshHints()
                    }
                }
            }
        }
    }

    private inner class NoticeCardView(context: android.content.Context) : LinearLayout(context) {
        private val dot: View
        private val headline: TextView
        private val detail: TextView
        private val meta: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            minimumHeight = dp(64)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            dot = DashboardParts.dot(context, colors.mutedText)
            addView(dot)
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                headline = TextView(context).apply {
                    textSize = 13f
                    textWeight(600)
                    setTextColor(colors.primaryText)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(headline)
                detail = TextView(context).apply {
                    textSize = 11.5f
                    setTextColor(colors.mutedText)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(detail)
                meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(colors.mutedText)
                    maxLines = 1
                    setPadding(0, dp(3), 0, 0)
                }
                addView(meta)
            }, LayoutParams(0, WRAP, 1f))
        }

        fun bind(notice: ServiceNotice, unread: Boolean) {
            setTag(TAG_NOTICE, notice)
            setUnread(unread)
            dot.background = ThemeGradientDrawable.oval(severityColor(notice.severity))
            headline.text = if (notice.kind == "health") humanizeHealthTitle(notice.title) else notice.title
            detail.text = notice.detail
            detail.visibility = if (notice.detail.isEmpty()) View.GONE else View.VISIBLE
            meta.text = if (notice.active) "Needs attention now" else noticeTime(notice)
            contentDescription = buildString {
                append(displayName(notice.service)).append(", ").append(headline.text)
                if (notice.detail.isNotEmpty()) append(", ").append(notice.detail)
                if (meta.text.isNotEmpty()) append(", ").append(meta.text)
            }
        }

        fun setUnread(unread: Boolean) {
            background = Styler.selectionBackground(
                context, colors, selected = false,
                baseFill = if (unread) colors.unreadSurface else android.graphics.Color.TRANSPARENT,
                cornerDp = 10f
            )
        }
    }

    private fun noticeTime(notice: ServiceNotice): String {
        if (notice.timeLabel.isNotEmpty()) return notice.timeLabel
        val instant = runCatching { Instant.parse(notice.occurredAt) }.getOrNull() ?: return ""
        val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "Just now"
            seconds < 3_600 -> "${seconds / 60}m ago"
            seconds < 86_400 -> "${seconds / 3_600}h ago"
            seconds < 604_800 -> "${seconds / 86_400}d ago"
            else -> "${seconds / 604_800}w ago"
        }
    }

    private fun humanizeHealthTitle(value: String): String =
        value.replace(HEALTH_WORD_BOUNDARY, " ")

    private fun severityColor(severity: String): Int = when (severity) {
        "success" -> colors.badgeAvailable
        "warning" -> colors.badgePending
        "error" -> colors.badgeFailed
        else -> colors.accent
    }

    private fun stateLabel(state: String): String = when (state) {
        "up" -> "Up to date"
        "degraded" -> "Partial"
        "unavailable" -> "Unavailable"
        "disabled" -> "Not configured"
        else -> state.replaceFirstChar { it.uppercase() }
    }

    private fun displayName(service: String): String = com.pocketds.hub.model.ServiceNames.display(service)

    private fun serviceLogo(service: String): Int = com.pocketds.hub.ui.ServiceLogo.resource(service)

    private fun pill(color: Int) = ThemeGradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Styler.dp(host.viewContext, 99f)
        setColor(color)
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TAG_NOTICE = -0x7ffffc01
        val MEDIA_SERVICES = listOf("sonarr", "radarr", "bazarr")
        val BOOK_SERVICES = listOf("bookkeeprr", "kavita", "storyteller")
        val HEALTH_WORD_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    }
}

private class NoticeHolder(view: View) : RecyclerView.ViewHolder(view)
