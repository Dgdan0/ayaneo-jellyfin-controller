package com.pocketds.hub.screens.manage

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
import androidx.recyclerview.widget.LinearLayoutManager
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
import kotlinx.coroutines.launch

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
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.primaryText)
                setPadding(dp(18), dp(16), dp(18), 0)
            }
            addView(summary)

            status = TextView(context).apply {
                text = "Checking Ayaneo Hub…"
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(dp(18), dp(3), dp(18), dp(9))
            }
            addView(status)

            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@ManageScreen.adapter
                itemAnimator = null
                clipToPadding = false
                setPadding(dp(10), 0, dp(10), dp(88))
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
                selectedService == "hub" -> "Edit"
                adapter.find(selectedService)?.dashboardUrl.isNullOrEmpty() -> "Details"
                else -> "Open"
            }
        ))
        if (selectedService == "jellyfin") add(ButtonHint.primary("Scan libraries"))
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    override fun onPad(action: PadAction): Boolean = when (action) {
        is PadAction.Step -> when (action.direction) {
            Direction.UP -> { moveService(-1); true }
            Direction.DOWN -> { moveService(1); true }
            else -> false
        }
        PadAction.Primary -> if (selectedService == "jellyfin") {
            scanLibraries()
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
        val target = (current + delta).coerceIn(0, adapter.itemCount - 1)
        if (target == current) return
        pendingFocus = target
        list.scrollToPosition(target)
        list.post {
            list.findViewHolderForAdapterPosition(target)?.itemView?.let {
                pendingFocus = RecyclerView.NO_POSITION
                it.requestFocus()
            }
        }
    }

    private fun scanLibraries() {
        if (scanJob?.isActive == true) {
            host.notify("Jellyfin library scan is already starting")
            return
        }
        status.setTextColor(colors.mutedText)
        status.text = "Starting Jellyfin library scan…"
        scanJob = scope.launch {
            when (val result = api.scanJellyfinLibrary()) {
                is HubResult.Ok -> {
                    status.setTextColor(colors.accent)
                    status.text = "Jellyfin library scan started · new files may take a moment to appear"
                    host.notify("Jellyfin library scan started")
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
        status.setTextColor(colors.mutedText)
        status.text = if (adapter.itemCount == 0) "Checking Ayaneo Hub…" else "Refreshing services…"
        loadJob = scope.launch {
            when (val result = api.health()) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = if (adapter.itemCount <= 1) {
                        adapter.showHub(configuredHubRow(state = "down"))
                        "Ayaneo Hub is not reachable · open it to edit the address"
                    } else {
                        "Could not refresh · showing the previous result"
                    }
                }
            }
            loadJob = null
        }
    }

    private fun render(value: HealthResponse) {
        val rows = buildList {
            add(configuredHubRow(value.hub))
            value.services.sortedBy { serviceOrder[it.name] ?: Int.MAX_VALUE }.forEach {
                add(it.toRow())
            }
        }
        adapter.submit(rows)
        val problemCount = rows.count { it.state == "down" || it.state == "misconfigured" }
        val running = rows.count { it.state == "up" }
        summary.text = "Services · $running running"
        status.setTextColor(if (problemCount == 0) colors.mutedText else colors.badgePending)
        status.text = if (problemCount == 0) {
            "Ayaneo Hub and all enabled services are responding"
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
            name = displayNames[name] ?: name.replaceFirstChar { it.uppercase() },
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
            card.setOnFocusChangeListener { _, focused ->
                FocusDecorator.refresh(card, ringVisible())
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
        private val detail: TextView
        private val icon: ImageView
        private val scan: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(78)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = cardBackground()
            isFocusable = true
            isClickable = true

            icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(icon, LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(14)
            })

            addView(LinearLayout(context).apply {
                orientation = VERTICAL

                addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                name = TextView(context).apply {
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.primaryText)
                }
                addView(name, LayoutParams(0, WRAP, 1f))
                state = TextView(context).apply {
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(11), dp(4), dp(11), dp(4))
                }
                addView(state)
                }, LayoutParams(MATCH, WRAP))

                detail = TextView(context).apply {
                    textSize = 12f
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 2
                }
                addView(detail, LayoutParams(MATCH, WRAP))
            }, LayoutParams(0, WRAP, 1f))

            scan = TextView(context).apply {
                text = "↻  Scan"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
                background = Styler.chipBackground(context, colors)
                minWidth = dp(78)
                minHeight = dp(44)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = false
                contentDescription = "Scan Jellyfin libraries"
                setOnClickListener { scanLibraries() }
                visibility = View.GONE
            }
            addView(scan, LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        }

        fun bind(row: ServiceRow) {
            icon.setImageResource(serviceLogo(row.icon))
            icon.background = if (row.icon == "hub") GradientDrawable().apply {
                cornerRadius = Styler.dp(context, 12f)
                setColor(0xFF0FADA0.toInt())
            } else null
            val inset = if (row.icon == "hub") dp(1) else dp(2)
            icon.setPadding(inset, inset, inset, inset)
            name.text = row.name
            state.text = stateLabel(row.state)
            state.setTextColor(if (row.state == "up") colors.accentText else colors.primaryText)
            state.background = GradientDrawable().apply {
                cornerRadius = Styler.dp(context, 12f)
                setColor(stateColor(row.state))
            }
            detail.text = row.detail.ifEmpty { "No additional information" }
            scan.visibility = if (row.id == "jellyfin") View.VISIBLE else View.GONE
            contentDescription = buildString {
                append(row.name).append(", ").append(stateLabel(row.state))
                if (row.detail.isNotEmpty()) append(", ").append(row.detail)
                when {
                    row.id == "hub" -> append(", edits Hub connection")
                    row.id == "jellyfin" -> append(", X scans libraries, A opens service dashboard")
                    row.dashboardUrl.isNotEmpty() -> append(", opens service dashboard")
                }
            }
        }
    }

    private fun serviceLogo(service: String): Int = when (service) {
        "jellyfin" -> R.drawable.logo_jellyfin
        "jellyseerr" -> R.drawable.logo_jellyseerr
        "sonarr" -> R.drawable.logo_sonarr
        "radarr" -> R.drawable.logo_radarr
        "bazarr" -> R.drawable.logo_bazarr
        "qbittorrent" -> R.drawable.logo_qbittorrent
        else -> R.drawable.ic_launcher_foreground
    }

    private fun cardBackground(): StateListDrawable {
        fun face(color: Int, stroke: Int = 0) = GradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 14f)
            setColor(color)
            if (stroke > 0) setStroke(stroke, this@ManageScreen.colors.focusRing)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(colors.cardSurfacePressed))
            addState(intArrayOf(android.R.attr.state_focused), face(colors.focusFill, dp(3)))
            addState(intArrayOf(), face(colors.cardSurface))
        }
    }

    private fun stateColor(value: String): Int = when (value) {
        "up" -> colors.badgeAvailable
        "checking" -> colors.stripBackground
        "disabled" -> colors.posterPlaceholder
        "misconfigured" -> colors.badgePending
        else -> colors.badgeFailed
    }

    private fun stateLabel(value: String): String = when (value) {
        "up" -> "Running"
        "checking" -> "Checking"
        "disabled" -> "Disabled"
        "misconfigured" -> "Needs setup"
        "down" -> "Unavailable"
        else -> value.ifEmpty { "Unknown" }.replaceFirstChar { it.uppercase() }
    }

    private fun uptime(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val days = safe / 86_400
        val hours = (safe % 86_400) / 3_600
        val minutes = (safe % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
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
                add("Running for ${uptime(it.uptimeSeconds)}")
                add("${it.tokenCount} access token${if (it.tokenCount == 1) "" else "s"}")
            }
        }.joinToString(" · ")
    )

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

        val displayNames = mapOf(
            "jellyfin" to "Jellyfin",
            "jellyseerr" to "Jellyseerr",
            "sonarr" to "Sonarr",
            "radarr" to "Radarr",
            "qbittorrent" to "qBittorrent",
            "bazarr" to "Bazarr"
        )
        val serviceOrder = listOf(
            "jellyfin", "jellyseerr", "sonarr", "radarr", "qbittorrent", "bazarr"
        ).withIndex().associate { it.value to it.index }
    }
}

private class ServiceHolder(view: View) : RecyclerView.ViewHolder(view)
