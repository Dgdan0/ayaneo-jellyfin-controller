package com.pocketds.hub.screens.manage

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.text.TextUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.R
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.HubInfo
import com.pocketds.hub.model.ServiceHealth
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.AppIcon
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.model.ServiceNames

/** Service dashboard with authenticated, service-specific maintenance actions. */
class ManageScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title = "Manage"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private val adapter = ServiceAdapter()
    private var loadJob: Job? = null
    private var scanJob: Job? = null
    private var selectedService = "hub"
    private var pendingFocus = RecyclerView.NO_POSITION

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)

            summary = TextView(context).apply {
                text = "Services"
                typeRole(Type.Role.SCREEN)
                setTextColor(colors.primaryText)
                setPadding(dp(24), dp(14), dp(24), 0)
            }
            addView(summary)

            status = TextView(context).apply {
                text = "Checking Ayaneo Hub…"
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(dp(24), dp(4), dp(24), dp(10))
            }
            addView(status)

            list = RecyclerView(context).apply {
                // Two across: thirteen full-width rows took four screens to pass.
                layoutManager = GridLayoutManager(context, COLUMNS)
                adapter = this@ManageScreen.adapter
                itemAnimator = null
                clipToPadding = false
                setPadding(dp(18), 0, dp(18), dp(16))
                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(view: View) {
                            if (getChildAdapterPosition(view) != pendingFocus) return
                            pendingFocus = RecyclerView.NO_POSITION
                            view.post { view.requestFocus() }
                        }

                        override fun onChildViewDetachedFromWindow(view: View) = Unit
                    }
                )
            }
            addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
    }

    override fun onShow() {
        adapter.showHub(configuredHubRow())
        adapter.showHub(monitorRow())
        refresh()
    }

    override fun onHide() {
        loadJob?.cancel()
        loadJob = null
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        scanJob?.cancel()
        scope.cancel()
    }

    override fun requestInitialFocus(): Boolean =
        list.findViewHolderForAdapterPosition(adapter.indexOf(selectedService))
            ?.itemView?.requestFocus() == true || list.getChildAt(0)?.requestFocus() == true

    override fun hints(): List<ButtonHint> = buildList {
        add(ButtonHint.activate(
            when {
                selectedService == "monitor" -> "Open"
                selectedService == "hub" -> "Edit"
                adapter.find(selectedService)?.dashboardUrl.isNullOrEmpty() -> "Details"
                else -> "Open"
            }
        ))
        if (selectedService in scannableServices) add(ButtonHint.primary("Scan library"))
        add(ButtonHint.refresh())
    }

    override fun onPad(action: PadAction): Boolean = when (action) {
        is PadAction.Step -> {
            val position = list.findContainingViewHolder(list.findFocus() ?: list)?.bindingAdapterPosition
                ?.takeIf { it != RecyclerView.NO_POSITION } ?: adapter.indexOf(selectedService).coerceAtLeast(0)
            when (action.direction) {
                Direction.UP -> moveService(-COLUMNS)
                Direction.DOWN -> moveService(COLUMNS)
                Direction.LEFT -> if (position % COLUMNS > 0) moveService(-1)
                Direction.RIGHT -> if (position % COLUMNS < COLUMNS - 1) moveService(1)
            }
            true
        }
        PadAction.Primary -> if (selectedService in scannableServices) {
            scanLibrary(selectedService)
            true
        } else false
        PadAction.Refresh -> {
            refresh()
            true
        }
        else -> false
    }

    private fun moveService(delta: Int) {
        if (adapter.itemCount == 0) return
        val focusedPosition = list.findContainingViewHolder(list.findFocus())
            ?.bindingAdapterPosition
            ?.takeIf { it != RecyclerView.NO_POSITION }
        val current = focusedPosition ?: adapter.indexOf(selectedService).coerceAtLeast(0)
        val target = current + delta
        if (target !in 0 until adapter.itemCount) return
        pendingFocus = target
        list.scrollToPosition(target)
        list.post {
            list.findViewHolderForAdapterPosition(target)?.itemView?.let {
                pendingFocus = RecyclerView.NO_POSITION
                it.requestFocus()
            }
        }
    }

    private fun scanLibrary(service: String) {
        val label = ServiceNames.display(service)
        if (scanJob?.isActive == true) {
            host.notify("A library scan is already running")
            return
        }
        status.setTextColor(colors.mutedText)
        status.text = "Scanning $label library…"
        scanJob = scope.launch {
            val result = if (service == "jellyfin") {
                api.scanJellyfinLibrary()
            } else {
                api.scanReadingLibrary(service)
            }
            when (result) {
                is HubResult.Ok -> {
                    status.setTextColor(colors.accent)
                    status.text = "$label library scan accepted · new files may take a moment to appear"
                    host.notify("$label library scan accepted")
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host.notify(result.message)
                }
            }
            scanJob = null
        }
    }

    private fun refresh() {
        if (loadJob?.isActive == true) return
        status.showStatus(
            StatusMessage(if (adapter.itemCount == 0) "Checking Ayaneo Hub…" else "Refreshing services…"), colors
        )
        loadJob = scope.launch {
            when (val result = api.health()) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    // The hub's own reason matters most on the one screen where
                    // it can be fixed: a rejected token used to read as
                    // "not reachable" and sent people to edit the address.
                    val hasServices = adapter.itemCount > 2
                    if (!hasServices) adapter.showHub(configuredHubRow(state = "down"))
                    val message = if (hasServices) result.message
                        else "${result.message} · open Ayaneo Hub to edit the connection"
                    status.showStatus(StatusText.failed(message, result.kind, hasData = hasServices), colors)
                }
            }
            loadJob = null
        }
    }

    private fun render(value: HealthResponse) {
        val rows = buildList {
            add(configuredHubRow(value.hub))
            add(monitorRow())
            value.services.sortedBy { ServiceNames.rank(it.name) }.forEach {
                add(it.toRow())
            }
        }
        adapter.submit(rows)
        val problemCount = rows.count { it.state == "down" || it.state == "misconfigured" }
        val running = rows.count { it.state == "up" }
        summary.text = "Services"
        status.setTextColor(if (problemCount == 0) colors.mutedText else colors.badgePending)
        status.text = if (problemCount == 0) {
            "All $running running · A opens a service's own page"
        } else {
            "$problemCount service${if (problemCount == 1) " needs" else "s need"} attention"
        }
        restoreFocus()
    }

    private fun ServiceHealth.toRow(): ServiceRow {
        val details = buildList {
            if (version.isNotEmpty()) add("v$version")
            if (latencyMs > 0) add("${latencyMs} ms")
            if (lastError.isNotEmpty()) add(lastError)
            addAll(notes.filter { it.isNotBlank() })
        }.joinToString(" · ")
        return ServiceRow(
            id = name,
            name = ServiceNames.display(name),
            state = state,
            icon = name,
            dashboardUrl = dashboardUrl,
            detail = details
        )
    }

    private fun restoreFocus() {
        if (!list.isShown) return
        val position = adapter.indexOf(selectedService).coerceAtLeast(0)
        list.scrollToPosition(position)
        list.post {
            val focused = list.findFocus()
            if (focused == null || focused === list) {
                list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
    }

    private fun activate(row: ServiceRow) {
        selectedService = row.id
        if (row.id == "monitor") {host.push(ServerMonitorScreen(api,ringVisible));return}
        if (row.id == "hub") {
            host.push(HubConnectionScreen(api, ringVisible))
            return
        }
        if (row.dashboardUrl.isNotEmpty()) {
            try {
                host.viewContext.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(row.dashboardUrl))
                )
                return
            } catch (_: ActivityNotFoundException) {
                host.notify("No browser is available to open ${row.name}")
                return
            } catch (_: SecurityException) {
                host.notify("Android blocked the ${row.name} address")
                return
            }
        }
        host.notify(buildString {
            append(row.name).append(" · ").append(stateLabel(row.state))
            if (row.detail.isNotEmpty()) append(" · ").append(row.detail)
        })
    }

    private inner class ServiceAdapter : RecyclerView.Adapter<ServiceHolder>() {
        private val rows = mutableListOf<ServiceRow>()

        init {
            setHasStableIds(true)
        }

        fun submit(values: List<ServiceRow>) {
            rows.clear()
            rows.addAll(values)
            notifyDataSetChanged()
        }

        fun indexOf(id: String): Int = rows.indexOfFirst { it.id == id }
        fun find(id: String): ServiceRow? = rows.firstOrNull { it.id == id }

        fun showHub(row: ServiceRow) {
            val position = indexOf(row.id)
            if (position < 0) {
                rows.add(0, row)
                notifyItemInserted(0)
            } else {
                rows[position] = row
                notifyItemChanged(position)
            }
        }

        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceHolder {
            val card = ServiceCardView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    setMargins(dp(7), dp(5), dp(7), dp(5))
                }
                FocusDecorator.attach(this, ringVisible, scale = false)
            }
            return ServiceHolder(card)
        }

        override fun onBindViewHolder(holder: ServiceHolder, position: Int) {
            val row = rows[position]
            val card = holder.itemView as ServiceCardView
            card.bind(row)
            card.activateOnTap { activate(row) }
            FocusDecorator.listen(card, ringVisible) { _, focused ->
                if (focused) {
                    selectedService = row.id
                    host.refreshHints()
                }
            }
        }
    }

    private inner class ServiceCardView(context: android.content.Context) : LinearLayout(context) {
        private val name: TextView
        private val state: TextView
        private val dot: View
        private val detail: TextView
        private val icon: ImageView
        private val scan: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = Styler.cardBackground(context, colors, cornerDp = 16f)
            Styler.makeFocusable(this)
            isClickable = true

            icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(icon, LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })

            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                name = TextView(context).apply {
                    textSize = 14.5f
                    textWeight(600)
                    setTextColor(colors.primaryText)
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(name, LayoutParams(MATCH, WRAP))
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, 0)
                    dot = DashboardParts.dot(context, colors.mutedText)
                    addView(dot)
                    state = TextView(context).apply { textSize = 11.5f; textWeight(600) }
                    addView(state)
                })
                detail = TextView(context).apply {
                    textSize = 11f
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(2), 0, 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                addView(detail, LayoutParams(MATCH, WRAP))
            }, LayoutParams(0, WRAP, 1f))

            scan = PillButton.create(context, colors, "Scan", AppIcon.REFRESH, heightDp = 32f).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                contentDescription = "Scan Jellyfin libraries"
                visibility = View.GONE
            }
            addView(scan, LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        }

        fun bind(row: ServiceRow) {
            com.pocketds.hub.ui.ServiceLogo.bind(icon,serviceLogo(row.icon))
            icon.background = if (row.icon == "hub") ThemeGradientDrawable().apply {
                cornerRadius = Styler.dp(context, 10f)
                setColor(0xFF0FADA0.toInt())
            } else null
            val inset = if (row.icon == "hub") dp(1) else dp(2)
            icon.setPadding(inset, inset, inset, inset)
            name.text = row.name
            state.text = stateLabel(row.state)
            val color = stateColor(row.state)
            state.setTextColor(if (row.state == "overview" || row.state == "checking") colors.mutedText else color)
            dot.background = ThemeGradientDrawable.oval(color)
            dot.visibility = if (row.state == "overview") View.GONE else View.VISIBLE
            detail.text = row.detail.ifEmpty { "No additional information" }
            scan.visibility = if (row.id in scannableServices) View.VISIBLE else View.GONE
            scan.contentDescription = "Scan ${row.name} library"
            scan.activateOnTap { scanLibrary(row.id) }
            contentDescription = buildString {
                append(row.name).append(", ").append(stateLabel(row.state))
                if (row.detail.isNotEmpty()) append(", ").append(row.detail)
                when {
                    row.id == "hub" -> append(", edits Hub connection")
                    row.id in scannableServices -> append(", X scans library, A opens service dashboard")
                    row.dashboardUrl.isNotEmpty() -> append(", opens service dashboard")
                }
            }
        }
    }

    private fun serviceLogo(service: String): Int = com.pocketds.hub.ui.ServiceLogo.resource(service)

    private fun stateColor(value: String): Int = when (value) {
        "overview", "checking" -> colors.mutedText
        else -> DashboardParts.stateColor(colors, value)
    }

    private fun stateLabel(value: String): String = when (value) {
        "overview" -> "Open"
        "up" -> "Running"
        "checking" -> "Checking"
        "disabled" -> "Disabled"
        "misconfigured" -> "Needs setup"
        "down" -> "Unavailable"
        else -> value.ifEmpty { "Unknown" }.replaceFirstChar { it.uppercase() }
    }

    private fun configuredHubRow(
        health: HubInfo? = null,
        state: String = if (health == null) "checking" else "up"
    ) = ServiceRow(
        id = "hub",
        name = "Ayaneo Hub",
        state = state,
        icon = "hub",
        detail = buildList {
            add(HubSettings.baseUrl(host.viewContext).ifEmpty { "No address configured" })
            health?.let {
                if (it.version.isNotEmpty()) add("v${it.version}")
                add("up ${Fmt.uptime(it.uptimeSeconds)}")
                add("${it.tokenCount} access token${if (it.tokenCount == 1) "" else "s"}")
            }
        }.joinToString(" · ")
    )

    private fun monitorRow() = ServiceRow("monitor", "Server monitor", "overview", "hub", detail="CPU, memory, disk space, containers and current playback")

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private data class ServiceRow(
        val id: String,
        val name: String,
        val state: String,
        val icon: String,
        val dashboardUrl: String = "",
        val detail: String
    )

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLUMNS = 2

        val scannableServices = setOf("jellyfin", "kavita", "storyteller")
    }
}

private class ServiceHolder(view: View) : RecyclerView.ViewHolder(view)
