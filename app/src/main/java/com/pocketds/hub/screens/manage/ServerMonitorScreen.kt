package com.pocketds.hub.screens.manage

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.HostContainer
import com.pocketds.hub.model.ServerMonitor
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.ui.DashboardParts
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SettingsCard
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.typeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The media PC at a glance: CPU, memory and uptime on cards across the top,
 * then its disks and what is playing beside the Docker containers. Read-only;
 * the pad moves through the rows so a long list scrolls, and Select refreshes
 * (it also refreshes itself every 15 seconds while shown).
 */
class ServerMonitorScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Server monitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private val work = JobSlot()
    private var firstRender = true

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), dp(24), 0)
            setBackgroundColor(colors.background)
            status = DashboardParts.text(context, "Asking the media PC…", 12f, colors.mutedText)
            addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, 0, 0, dp(16)) }
            addView(FocusScrollView(context).apply { clipToPadding = false; addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() {
        load()
        scope.launch { while (isActive) { delay(REFRESH_MILLIS); load() } }
    }

    override fun onHide() = scope.coroutineContext.cancelChildren()
    override fun onDestroyView() { scope.cancel(); host = null }
    override fun requestInitialFocus(): Boolean = body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() == true
    override fun hints() = listOf(ButtonHint.back(), ButtonHint.refresh())
    override fun onPad(action: PadAction): Boolean {
        if (action != PadAction.Refresh) return false
        load()
        return true
    }

    private fun load() {
        if (work.isBusy) return
        work.launch(scope) {
            when (val result = api.serverMonitor()) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.text = "Could not refresh · ${result.message} · the figures below may be old"
                    status.setTextColor(colors.dangerText)
                }
            }
        }
    }

    private fun render(value: ServerMonitor) {
        val context = checkNotNull(host).viewContext
        val focused = body.findFocus()?.tag as? String
        body.removeAllViews()
        val time = runCatching {
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value.checkedAt))
        }.getOrDefault("")
        status.setTextColor(colors.mutedText)
        status.text = listOf("${value.host.os.ifEmpty { "Media PC" }} host", "checked $time".takeIf { time.isNotEmpty() },
            "refreshes every 15 seconds").filterNotNull().joinToString(" · ")

        val total = value.host.memoryTotalBytes
        val used = (total - value.host.memoryAvailableBytes).coerceAtLeast(0)
        val cpu = value.host.cpuPercent
        val figures = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            DashboardParts.stat(context, colors, "CPU", cpu?.let { String.format(Locale.US, "%.0f%%", it) } ?: "—",
                "Over a short sample", cpu?.div(100.0), warning = (cpu ?: 0.0) >= 90.0),
            DashboardParts.stat(context, colors, "Memory", if (total > 0) Fmt.bytes(used) else "—",
                if (total > 0) "of ${Fmt.bytes(total)}" else "Unavailable", if (total > 0) used.toDouble() / total else null),
            DashboardParts.stat(context, colors, "Up for", Fmt.uptime(value.host.uptimeSeconds).ifEmpty { "—" }, "Since the PC last started")
        ).forEachIndexed { i, card -> figures.addView(card, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(12) }) }
        body.addView(figures)

        val left = column(context)
        val right = column(context)
        left.addView(disks(value))
        left.addView(playing(value), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        right.addView(containers(value.containers, value.dockerWarning))
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(right, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
        // The first answer lands after the page has shown, with nothing to
        // focus yet; focus had fallen to the tabs above.
        body.post {
            val back = focused?.let { body.findViewWithTag<View>(it) }
            if (back?.requestFocus() != true && !body.hasFocus() && firstRender) requestInitialFocus()
            firstRender = false
            host?.refreshHints()
        }
    }

    private fun disks(value: ServerMonitor): SettingsCard = card("Disks", "${value.host.disks.size} with space the hub can see").apply {
        val context = this.context
        if (value.host.disks.isEmpty()) quiet(this, "No disk figures from this PC")
        value.host.disks.forEach { disk ->
            val view = DashboardParts.disk(context, colors, disk)
            addView(DashboardParts.row(context, colors, ringVisible, "disk:${disk.name}", view.contentDescription.toString()).apply {
                orientation = LinearLayout.VERTICAL
                addView(view, LinearLayout.LayoutParams(MATCH, WRAP))
            })
        }
        // "G:\ space unavailable": a drive the hub can name but not measure.
        value.host.warnings.forEach { quiet(this, it) }
    }

    private fun playing(value: ServerMonitor): SettingsCard = card("Playing now", "for this profile").apply {
        when {
            value.sessionWarning.isNotEmpty() -> quiet(this, value.sessionWarning)
            value.sessions.isEmpty() -> quiet(this, "Nothing is playing")
        }
        value.sessions.forEachIndexed { i, session ->
            val line = listOf(session.device, session.client, if (session.paused) "Paused" else session.method.ifEmpty { "Playing" })
                .filter(String::isNotBlank).joinToString(" · ")
            addView(item("session:$i", session.title, line, if (session.paused) colors.mutedText else colors.accent))
        }
    }

    private fun containers(values: List<HostContainer>, warning: String): SettingsCard {
        val running = values.count { it.state == "running" }
        return card("Docker containers", if (values.isEmpty()) "" else "$running of ${values.size} running").apply {
            when {
                warning.isNotEmpty() -> quiet(this, warning)
                values.isEmpty() -> quiet(this, "Docker reports no containers")
            }
            values.sortedWith(compareBy({ it.state != "running" }, { it.name })).forEach { container ->
                val unhealthy = container.status.contains("unhealthy", ignoreCase = true)
                val state = if (unhealthy) "degraded" else container.state
                addView(item("container:${container.name}", container.name,
                    listOf(container.status, container.image).filter(String::isNotBlank).joinToString(" · "),
                    DashboardParts.stateColor(colors, state)))
            }
        }
    }

    /** A name with its state dot and a quiet line under it. */
    private fun item(id: String, name: String, line: String, dotColor: Int): LinearLayout {
        val context = checkNotNull(host).viewContext
        return DashboardParts.row(context, colors, ringVisible, id, "$name, $line").apply {
            gravity = android.view.Gravity.TOP
            addView(DashboardParts.dot(context, dotColor).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(DashboardParts.text(context, name, 12.5f, colors.primaryText, 600).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                if (line.isNotBlank()) addView(DashboardParts.text(context, line, 11f, colors.mutedText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun card(title: String, trailing: String): SettingsCard = SettingsCard(checkNotNull(host).viewContext, colors).apply {
        title(title, trailing)
        titleView?.typeRole(Type.Role.HEADING, 15f)
        // Room under the heading before the first row.
        addView(View(context), LinearLayout.LayoutParams(MATCH, dp(4)))
    }

    private fun quiet(card: SettingsCard, text: String) {
        card.addView(DashboardParts.text(card.context, text, 11.5f, colors.mutedText).apply { setPadding(dp(6), dp(4), dp(6), dp(2)) })
    }

    private fun column(context: android.content.Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
    }

    private fun dp(value: Int) = Styler.dpInt(checkNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val REFRESH_MILLIS = 15_000L
    }
}
