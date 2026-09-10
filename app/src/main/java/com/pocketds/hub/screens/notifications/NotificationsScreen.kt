package com.pocketds.hub.screens.notifications

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
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.NotificationReadStore
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Recent automation activity and current health, kept separate by service. */
class NotificationsScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean,
    private val onUnreadChanged: (Int) -> Unit
) : Screen {

    override val title = "Notifications"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private val columns = linkedMapOf<String, ServiceColumnView>()
    private var pollJob: Job? = null
    private var visible = false
    private var selectedID = ""
    private var hasContent = false
    private lateinit var readStore: NotificationReadStore
    private var unreadIds: Set<String> = emptySet()
    private var latestResponse: NotificationsResponse? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        readStore = NotificationReadStore(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)

            addView(TextView(context).apply {
                text = "Notifications"
                textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.primaryText)
                setPadding(dp(16), dp(11), dp(16), 0)
            })

            status = TextView(context).apply {
                text = "Loading Sonarr, Radarr and Bazarr…"
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(16), dp(2), dp(16), dp(8))
            }
            addView(status)

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), 0, dp(8), dp(82))
                SERVICES.forEach { service ->
                    val column = ServiceColumnView(service)
                    columns[service] = column
                    addView(column, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                        setMargins(dp(5), 0, dp(5), 0)
                    })
                }
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() {
        visible = true
        startPolling(showLoading = !hasContent)
        if (hasContent) requestInitialFocus()
    }

    override fun onHide() {
        visible = false
        pollJob?.cancel()
        pollJob = null
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        columns.clear()
    }

    override fun hints(): List<ButtonHint> = listOfNotNull(
        selectedNotice()?.let { ButtonHint.activate("Show message") },
        ButtonHint.primary("Mark all seen").takeIf { unreadIds.isNotEmpty() },
        ButtonHint("↻", "Refresh (Select)", PadAction.Refresh)
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
            columns.values.any { it.isLastItemFocused() }
        else -> false
    }

    override fun requestInitialFocus(): Boolean {
        if (selectedID.isNotEmpty()) {
            columns.values.firstOrNull { it.contains(selectedID) }?.let {
                return it.focus(selectedID)
            }
        }
        return columns.values.firstOrNull { it.hasItems() }?.focus("") == true
    }

    private fun startPolling(showLoading: Boolean) {
        pollJob?.cancel()
        if (showLoading) {
            status.setTextColor(colors.mutedText)
            status.text = "Loading Sonarr, Radarr and Bazarr…"
        } else if (hasContent) {
            status.setTextColor(colors.mutedText)
            status.text = "Refreshing activity…"
        }
        pollJob = scope.launch {
            while (visible) {
                fetchOnce()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun fetchOnce() {
        when (val result = api.notifications(NotificationSettings.limits(host.viewContext))) {
            is HubResult.Ok -> render(result.value)
            is HubResult.Failed -> {
                status.setTextColor(colors.dangerText)
                status.text = result.message + if (hasContent) " · showing previous activity" else " · Select retries"
            }
        }
    }

    private fun render(response: NotificationsResponse) {
        hasContent = true
        latestResponse = response
        readStore.observe(response.sections)
        val byService = response.sections.associateBy { it.service }
        SERVICES.forEach { service ->
            columns[service]?.bind(
                byService[service] ?: NotificationSection(service = service, state = "disabled")
            )
        }
        unreadIds = readStore.unread(columns.values.flatMap { it.itemIds() })
        columns.values.forEach { it.updateUnreadCount() }
        onUnreadChanged(unreadIds.size)
        status.setTextColor(if (response.partial.isEmpty()) colors.mutedText else colors.badgePending)
        updateStatus(response)
        if (columns.values.none { it.contains(selectedID) }) selectedID = ""
        requestInitialFocus()
        host.refreshHints()
    }

    private fun markSeen(id: String, card: NoticeCardView) {
        if (id !in unreadIds) return
        readStore.markSeen(id)
        unreadIds = unreadIds - id
        card.setUnread(false)
        columns.values.forEach { it.updateUnreadCount() }
        onUnreadChanged(unreadIds.size)
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
        onUnreadChanged(0)
        latestResponse?.let(::updateStatus)
        host.notify("All notifications marked as seen")
        host.refreshHints()
    }

    private fun updateStatus(response: NotificationsResponse) {
        status.text = when {
            response.partial.isNotEmpty() -> response.partial.joinToString(" · ") { it.message }
            response.sections.all { it.items.isEmpty() } -> "No recent activity or service warnings."
            unreadIds.isNotEmpty() -> "${unreadIds.size} unread notification${if (unreadIds.size == 1) "" else "s"}"
            response.attentionCount > 0 -> "${response.attentionCount} current service issue${if (response.attentionCount == 1) "" else "s"} · all seen"
            response.cache.stale -> "Recent activity · cached"
            else -> "Recent activity · all services responding"
        }
    }

    private fun selectedNotice(): ServiceNotice? =
        columns.values.firstNotNullOfOrNull { it.focusedNotice() }

    private inner class ServiceColumnView(private val service: String) : LinearLayout(host.viewContext) {
        private val count: TextView
        private val state: TextView
        private val empty: TextView
        private val list: RecyclerView
        private val adapter = NoticeAdapter()

        init {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Styler.dp(context, 14f)
                setColor(this@NotificationsScreen.colors.stripBackground)
            }
            setPadding(dp(7), dp(8), dp(7), dp(7))

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply {
                    setImageResource(serviceLogo(service))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(9) })
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = displayName(service)
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(colors.primaryText)
                    })
                    state = TextView(context).apply {
                        textSize = 10f
                        setTextColor(colors.mutedText)
                    }
                    addView(state)
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
                    layoutParams = RecyclerView.LayoutParams(MATCH, dp(94)).apply {
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
                card.setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(card, ringVisible())
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
            background = Styler.cardBackground(context, colors, 10f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            dot = View(context)
            addView(dot, LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                headline = TextView(context).apply {
                    textSize = 12.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.primaryText)
                    maxLines = 2
                }
                addView(headline)
                detail = TextView(context).apply {
                    textSize = 10.5f
                    setTextColor(colors.mutedText)
                    maxLines = 2
                }
                addView(detail)
                meta = TextView(context).apply {
                    textSize = 9.5f
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
            dot.background = pill(severityColor(notice.severity))
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
            background = Styler.cardBackground(
                context = context,
                colors = colors,
                cornerDp = 10f,
                baseFill = if (unread) colors.unreadSurface else colors.cardSurface
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

    private fun displayName(service: String): String = when (service) {
        "sonarr" -> "Sonarr"
        "radarr" -> "Radarr"
        "bazarr" -> "Bazarr"
        else -> service.replaceFirstChar { it.uppercase() }
    }

    private fun serviceLogo(service: String): Int = when (service) {
        "sonarr" -> R.drawable.logo_sonarr
        "radarr" -> R.drawable.logo_radarr
        "bazarr" -> R.drawable.logo_bazarr
        else -> R.drawable.ic_launcher_foreground
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = Styler.dp(host.viewContext, 99f)
        setColor(color)
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TAG_NOTICE = -0x7ffffc01
        const val POLL_MS = 30_000L
        val SERVICES = listOf("sonarr", "radarr", "bazarr")
        val HEALTH_WORD_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    }
}

private class NoticeHolder(view: View) : RecyclerView.ViewHolder(view)
