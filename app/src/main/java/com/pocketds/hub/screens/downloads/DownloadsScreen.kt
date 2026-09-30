package com.pocketds.hub.screens.downloads

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.ReadingDownloadItem
import com.pocketds.hub.model.ReadingDownloadsResponse
import com.pocketds.hub.model.ReadingTransferAction
import com.pocketds.hub.model.Stages
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.PollSchedule
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The download manager.
 *
 * One list answering "what is happening, and what is broken", joined by the hub
 * across qBittorrent, Radarr and Sonarr. The screen itself decides nothing about
 * what may be done: the hub sends an actions array per item, computed from this
 * token's scopes, and the menu is built from that. A read-only token therefore
 * gets a screen with no destructive buttons, rather than buttons that fail when
 * pressed.
 *
 * Every action that destroys something takes a second press. Not because the hub
 * requires it, but because this is a handheld where the cursor gets flung around
 * by a thumbstick, and "delete the files" must never be one press away from
 * whatever happened to be selected.
 */
class DownloadsScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean,
    startWithAttention: Boolean = false,
    private val targetTransferId: String = "",
    private val targetMediaKey: String = ""
) : Screen, ContentModeScreen {

    override val title: String = "Transfers"
    override val contentDomain: ContentMode? get() = if (targetTransferId.isNotBlank() || targetMediaKey.isNotBlank()) ContentMode.MEDIA else null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var summaryLine: TextView
    private lateinit var statusLine: TextView
    private lateinit var list: RecyclerView
    private lateinit var overlay: ChoiceOverlay
    private val adapter = ItemAdapter()
    private val readingAdapter = ReadingItemAdapter()
    private lateinit var deviceTransfers: TextView
    private lateinit var attentionFilter: TextView
    private lateinit var bandwidthButton: TextView
    private var attentionOnly = startWithAttention
    private var latestActivity: ActivityResponse? = null
    private var targetOpened = false
    private var mode = ContentMode.MEDIA

    private var host: ScreenHost? = null
    private var pollJob: Job? = null
    private var visible = false
    private var failures = 0
    private var anyActive = false
    private var includeFinished = targetTransferId.isNotBlank()

    /** Set while a mutation is in flight, so a poll cannot race its own result. */
    /**
     * The transfer action in flight. The poll loop skips its fetch while this is
     * busy, because a read mid-action returns the old state; the refresh runs
     * from onIdle instead, once the action is really over.
     */
    private val actions = JobSlot()

    /**
     * Poll fast until this moment, after the user acts.
     *
     * qBittorrent does not flip a torrent's state synchronously with the reply
     * to a stop -- measured here, the reply landed in 11ms and the state had not
     * moved 24ms later -- so the immediate refresh reads back the old row and
     * the screen then sits on it for a full idle interval. That reads exactly
     * like a button that did nothing.
     */
    private var settleUntilMs = 0L

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)

        val root = FrameLayout(context).apply { setBackgroundColor(colors.background) }

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        mode = if (targetTransferId.isNotBlank() || targetMediaKey.isNotBlank()) ContentMode.MEDIA else ContentModeSettings.get(context)
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Styler.dpInt(context, 12f), Styler.dpInt(context, 7f),
                Styler.dpInt(context, 12f), 0)
            attentionFilter = TextView(context).apply {
                text = "Needs attention"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(colors.primaryText)
                minimumHeight = Styler.dpInt(context, 48f)
                setPadding(Styler.dpInt(context, 12f), 0, Styler.dpInt(context, 12f), 0)
                background = Styler.chipBackground(context, colors)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
                activateOnTap {
                    attentionOnly = !attentionOnly
                    latestActivity?.let(::render)
                }
            }
            addView(attentionFilter)
            bandwidthButton = TextView(context).apply {
                text = "Bandwidth"
                textSize = 12f
                setTextColor(colors.primaryText)
                gravity = android.view.Gravity.CENTER
                minimumHeight = Styler.dpInt(context, 48f)
                setPadding(Styler.dpInt(context, 12f), 0, Styler.dpInt(context, 12f), 0)
                background = Styler.chipBackground(context, colors)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
                activateOnTap { host.push(BandwidthScreen(api, ringVisible)) }
            }
            addView(bandwidthButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = Styler.dpInt(context, 8f) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            deviceTransfers = TextView(context).apply {
                text = "To this device"
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setTextColor(colors.primaryText)
                minimumHeight = Styler.dpInt(context, 48f)
                setPadding(Styler.dpInt(context, 12f), 0, Styler.dpInt(context, 12f), 0)
                background = Styler.chipBackground(context, colors)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { host?.openOfflineManager() }
            }
            addView(deviceTransfers)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        summaryLine = TextView(context).apply {
            textSize = 13f
            setTextColor(colors.primaryText)
            setPadding(
                Styler.dpInt(context, 14f), Styler.dpInt(context, 10f),
                Styler.dpInt(context, 14f), 0
            )
        }
        content.addView(summaryLine)

        statusLine = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(
                Styler.dpInt(context, 14f), Styler.dpInt(context, 2f),
                Styler.dpInt(context, 14f), Styler.dpInt(context, 6f)
            )
        }
        content.addView(statusLine)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = activeAdapter()
            // No change animation. The default one cross-fades a *copy* of the
            // view being rebound, which takes focus off the row the user is on --
            // on a two-second poll, forever.
            itemAnimator = null
            setItemViewCacheSize(12)
            clipToPadding = false
            setPadding(
                Styler.dpInt(context, 8f), 0,
                Styler.dpInt(context, 8f), Styler.dpInt(context, 20f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        content.addView(list)

        overlay = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        return root
    }

    override fun onShow() {
        visible = true
        val stored = contentDomain ?: host?.viewContext?.let(ContentModeSettings::get) ?: mode
        if (stored != mode) switchMode(stored)
        failures = 0
        if (activeAdapter().itemCount == 0) statusLine.text = "Asking the hub…"
        startPolling()
    }

    override fun onHide() {
        visible = false
        pollJob?.cancel()
        pollJob = null
        // A confirmation left open behind a section switch would be sitting
        // there waiting to fire on the next A press, against an item nobody is
        // looking at any more.
        if (overlay.isOpen) closeOverlay()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun hints(): List<ButtonHint> {
        if (overlay.isOpen) {
            return listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        }
        if (::deviceTransfers.isInitialized && deviceTransfers.hasFocus()) {
            return listOf(ButtonHint.activate("Device downloads"), ButtonHint.back())
        }
        if (::attentionFilter.isInitialized && attentionFilter.hasFocus()) {
            return listOf(ButtonHint.activate(if (attentionOnly) "All transfers" else "Needs attention"), ButtonHint.back())
        }
        if (::bandwidthButton.isInitialized && bandwidthButton.hasFocus()) {
            return listOf(ButtonHint.activate("Bandwidth"), ButtonHint.back())
        }
        if (mode == ContentMode.BOOKS) {
            return listOfNotNull(
                focusedReadingItem()?.takeIf { it.availableActions.isNotEmpty() }
                    ?.let { ButtonHint.activate("Actions") },
                ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
            )
        }
        val item = focusedItem()
        val toggle = when {
            item == null -> null
            item.can("stop") -> "Stop"
            item.can("start") -> "Start"
            else -> null
        }
        return listOfNotNull(
            ButtonHint.activate("Actions"),
            toggle?.let { ButtonHint.primary(it) },
            ButtonHint("Ⓨ", if (includeFinished) "Hide done" else "Show all", PadAction.Secondary),
            // The button is named in the label. There is no conventional glyph
            // for Select, and "⊙ Refresh" told the user nothing about which
            // button to press -- they said so.
            ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
        )
    }

    override fun requestInitialFocus(): Boolean =
        (::list.isInitialized && list.getChildAt(0)?.requestFocus() == true) ||
            (::deviceTransfers.isInitialized && deviceTransfers.requestFocus())

    override fun onPad(action: PadAction): Boolean {
        // Everything is consumed while the menu is open, or a directional press
        // would walk the selection out from behind a confirmation.
        if (overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        if (action == PadAction.Activate && attentionFilter.hasFocus()) {
            attentionFilter.performClick()
            return true
        }
        if (action == PadAction.Activate && bandwidthButton.hasFocus()) {
            bandwidthButton.performClick()
            return true
        }
        return when (action) {
            PadAction.Activate -> if (mode == ContentMode.MEDIA) {
                focusedItem()?.let { openActions(it) } != null
            } else {
                focusedReadingItem()?.let { openReadingActions(it) } != null
            }
            PadAction.Primary -> {
                if (mode == ContentMode.BOOKS) return false
                val item = focusedItem()
                when {
                    item == null -> false
                    // Stopping and starting are reversible, so they happen on the
                    // press itself. Nothing else on this screen does.
                    item.can("stop") -> { run(item, "stop"); true }
                    item.can("start") -> { run(item, "start"); true }
                    else -> false
                }
            }
            PadAction.Secondary -> {
                if (mode == ContentMode.BOOKS) return false
                includeFinished = !includeFinished
                host?.refreshHints()
                refreshNow()
                true
            }
            PadAction.Refresh -> {
                refreshNow()
                true
            }
            else -> false
        }
    }

    // ---- polling -----------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                if (!actions.isBusy) fetchOnce()
                val settling = android.os.SystemClock.uptimeMillis() < settleUntilMs
                val wait = PollSchedule.nextDelayMs(visible, anyActive, failures, settling)
                    ?: break
                delay(wait)
            }
        }
    }

    /** Poll now rather than waiting out the current interval. */
    private fun refreshNow() {
        failures = 0
        startPolling()
    }

    private fun activeAdapter(): RecyclerView.Adapter<out RecyclerView.ViewHolder> =
        if (mode == ContentMode.MEDIA) adapter else readingAdapter

    private fun switchMode(next: ContentMode) {
        if (contentDomain != null && next != contentDomain) return
        if (mode == next) return
        mode = next
        val context = host?.viewContext ?: return
        ContentModeSettings.set(context, mode)
        attentionFilter.visibility = if (mode == ContentMode.MEDIA) View.VISIBLE else View.GONE
        bandwidthButton.visibility = attentionFilter.visibility
        list.adapter = activeAdapter()
        summaryLine.text = ""
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Asking the hub…"
        failures = 0
        refreshNow()
        host?.refreshHints()
    }

    override fun selectContentMode(mode: ContentMode) = switchMode(mode)

    private suspend fun fetchOnce() {
        if (mode == ContentMode.BOOKS) {
            fetchReadingOnce()
            return
        }
        when (val result = api.activity(includeFinished)) {
            is HubResult.Ok -> {
                failures = 0
                render(result.value)
            }
            is HubResult.Failed -> {
                failures++
                DebugLog.log("net", "activity failed #$failures: ${result.kind}")
                // The list is deliberately kept. A failed poll means the hub was
                // unreachable for a moment, not that the downloads stopped, and
                // blanking the screen would say the opposite.
                statusLine.setTextColor(colors.dangerText)
                statusLine.text = result.message + " · retrying"
            }
        }
    }

    private suspend fun fetchReadingOnce() {
        when (val result = api.readingDownloads()) {
            is HubResult.Ok -> {
                failures = 0
                renderReading(result.value)
            }
            is HubResult.Failed -> {
                failures++
                DebugLog.log("net", "reading downloads failed #$failures: ${result.kind}")
                statusLine.setTextColor(colors.dangerText)
                statusLine.text = result.message + " · retrying"
            }
        }
    }

    private fun render(body: ActivityResponse) {
        host?.viewContext?.let { com.pocketds.hub.settings.TransferAlertObserver.observe(it,body) }
        latestActivity = body
        anyActive = body.anyActive
        val related = if(targetMediaKey.isBlank()) body.items else body.items.filter { transferMatchesMedia(it,targetMediaKey) }
        val displayed = if (attentionOnly) related.filter { it.isBroken } else related
        adapter.submit(displayed)
        if(!targetOpened && targetTransferId.isNotBlank()) {
            targetOpened=true
            val target=body.items.firstOrNull { it.id==targetTransferId }
            list.post {
                if(target!=null) openDiagnosis(target)
                else host?.notify("This transfer is no longer in the active queue.")
            }
        }
        val attentionCount = related.count { it.isBroken }
        attentionFilter.text = if (attentionOnly) "All transfers · $attentionCount need attention" else "Needs attention · $attentionCount"
        attentionFilter.isSelected = attentionOnly

        val s = body.summary
        summaryLine.setTextColor(colors.primaryText)
        summaryLine.text = buildString {
            if (targetMediaKey.isNotBlank()) {
                append(related.size).append(" transfers for this title · ").append(attentionCount).append(" need attention")
                return@buildString
            }
            append(s.downloading).append(" downloading")
            if (s.queued > 0) append(" · ").append(s.queued).append(" queued")
            if (s.seeding > 0) append(" · ").append(s.seeding).append(" seeding")
            if (s.stuck > 0) append(" · ").append(s.stuck).append(" stuck")
            if (s.downSpeedBytes > 0 || s.upSpeedBytes > 0) {
                append("   ↓ ").append(Fmt.speed(s.downSpeedBytes))
                append("  ↑ ").append(Fmt.speed(s.upSpeedBytes))
            }
        }

        statusLine.setTextColor(
            if (body.partial.isEmpty()) colors.mutedText else colors.badgePending
        )
        statusLine.text = when {
            // A partial response is the normal shape here, not an error: this
            // screen is useful with any one of the three services answering, and
            // naming the missing one beats a silently shorter list.
            body.partial.isNotEmpty() ->
                body.partial.joinToString(" · ") { it.service + " " + it.reason }
            targetMediaKey.isNotBlank() && displayed.isEmpty() -> if (attentionOnly) "No transfers currently need attention for this title." else "No transfers found for this title."
            attentionOnly && displayed.isEmpty() -> "No transfers need attention."
            attentionOnly -> "${displayed.size} transfers need attention"
            body.items.isEmpty() && includeFinished -> "Nothing in the queues."
            body.items.isEmpty() -> "Nothing running. Ⓨ shows finished items."
            else -> "${displayed.size} items" + if (includeFinished) " · including finished" else ""
        }
        host?.refreshHints()
    }

    private fun renderReading(body: ReadingDownloadsResponse) {
        anyActive = body.anyActive
        readingAdapter.submit(ReadingTransferSummary.grouped(body.items))
        val downloading = body.items.count { it.status == "downloading" }
        val queued = body.items.count { it.status == "queued" }
        val importing = body.items.count { it.status == "importing" }
        val failed = body.items.count { it.failed }
        val speed = body.items.sumOf { it.downloadSpeedBytesPerSecond }
        summaryLine.setTextColor(colors.primaryText)
        summaryLine.text = buildString {
            append(downloading).append(" downloading")
            if (queued > 0) append(" · ").append(queued).append(" queued")
            if (importing > 0) append(" · ").append(importing).append(" importing")
            if (failed > 0) append(" · ").append(failed).append(" failed")
            if (speed > 0) append("   ↓ ").append(Fmt.speed(speed))
        }
        statusLine.setTextColor(if (failed > 0) colors.badgeFailed else colors.mutedText)
        statusLine.text = when {
            body.items.isEmpty() -> "No book transfers yet."
            failed > 0 -> "$failed transfer${if (failed == 1) "" else "s"} need attention"
            else -> "${body.items.size} BookKeeprr transfer${if (body.items.size == 1) "" else "s"}"
        }
        host?.refreshHints()
    }

    // ---- actions -----------------------------------------------------------

    private fun focusedItem(): ActivityItem? {
        if (!::list.isInitialized) return null
        val focused = list.focusedChild ?: return null
        return adapter.itemAt(list.getChildAdapterPosition(focused))
    }

    private fun openActions(item: ActivityItem) {
        val choices = listOf(ChoiceOverlay.Choice("diagnosis", if (item.isBroken) "Why isn't it working?" else "Check transfer status", item.diagnosis?.title.orEmpty())) +
            item.actions.mapNotNull { action -> choiceFor(item, action) } + ChoiceOverlay.Choice("details", "Transfer details")
        if (choices.isEmpty()) {
            host?.notify("This token cannot control downloads")
            return
        }
        overlay.show(
            title = item.headline,
            subtitle = Stages.label(item.stage) + summarySuffix(item),
            choices = choices,
            // Never start on a destructive entry. On a stuck item every action is
            // destructive, so the cursor sits on the first one and the
            // confirmation carries the weight instead.
            startIndex = choices.indexOfFirst { !it.danger }.coerceAtLeast(0),
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            if (picked == "diagnosis") {
                openDiagnosis(item)
            } else if (picked == "details") {
                overlay.resetBody()
                overlay.open("Transfer details")
                overlay.body.addView(TextView(requireNotNull(host).viewContext).apply {
                    text = item.headline + "\n\n" + Stages.label(item.stage) + summarySuffix(item) + "\n\n" + item.warnings.joinToString("\n")
                    textSize=14f;setTextColor(colors.primaryText);setTextIsSelectable(true)
                })
                overlay.focusBody()
            } else {
                val choice = choices.first { it.id == picked }
                if (choice.danger) confirm(item, choice) else run(item, picked)
            }
        }
        // A and B now mean something else. Without this the bar still reads
        // "Actions / Show all" while a confirmation is on screen.
        host?.refreshHints()
    }

    private fun openDiagnosis(item: ActivityItem) {
        val context = host?.viewContext ?: return
        val diagnosis = item.diagnosis
        overlay.resetBody()
        overlay.open(if (item.isBroken) "Why isn't it working?" else "Transfer status", item.headline) { host?.refreshHints() }
        fun paragraph(value: String, heading: Boolean = false) {
            if (value.isBlank()) return
            overlay.body.addView(TextView(context).apply {
                text = value
                textSize = if (heading) 15f else 13f
                setTextColor(if (heading) colors.primaryText else colors.mutedText)
                setPadding(0, Styler.dpInt(context, 5f), 0, Styler.dpInt(context, 5f))
                if (heading) setTypeface(null, android.graphics.Typeface.BOLD)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
            })
        }
        paragraph(diagnosis?.title ?: "Detailed diagnosis unavailable", true)
        paragraph(diagnosis?.explanation ?: "This Hub does not provide a diagnosis yet. The latest transfer stage is ${Stages.label(item.stage)}.")
        paragraph("Suggested next step", true)
        paragraph(diagnosis?.nextStep ?: "Review the source queue and service health in Manage.")
        if (!diagnosis?.evidence.isNullOrEmpty()) {
            paragraph("Service evidence", true)
            diagnosis?.evidence?.forEach { paragraph(it) }
        }
        paragraph("Snapshot from the last refresh. Refresh to check for changes.")
        overlay.choice("Refresh status") { closeOverlay(); refreshNow() }
        // Only offer the non-destructive action explicitly authorized by the Hub.
        if (diagnosis?.action == "start" && item.can("start")) {
            overlay.choice("Resume transfer") { closeOverlay(); run(item, "start") }
        }
        overlay.focusBody()
        host?.refreshHints()
    }

    private fun choiceFor(item: ActivityItem, action: String): ChoiceOverlay.Choice? =
        when (action) {
            "priority_up" -> ChoiceOverlay.Choice(action, "Move up queue", "Current queue position: ${item.priority}")
            "priority_down" -> ChoiceOverlay.Choice(action, "Move down queue", "Current queue position: ${item.priority}")
            "stop" -> ChoiceOverlay.Choice(action, "Stop", "Leaves the files and the queue row")
            "start" -> ChoiceOverlay.Choice(action, "Start", "Resume this transfer")
            "delete" -> ChoiceOverlay.Choice(
                action, "Remove from client", "Keeps the downloaded files", danger = true
            )
            "delete_with_data" -> ChoiceOverlay.Choice(
                action, "Remove and delete files", "The data is gone for good", danger = true
            )
            "arr_remove" -> ChoiceOverlay.Choice(
                action, "Remove from " + (item.arr?.service ?: "queue"),
                "Drops the queue row and the transfer", danger = true
            )
            "arr_blocklist_and_search" -> ChoiceOverlay.Choice(
                action, "Blocklist and search again",
                "Never grab this release again, then look for another", danger = true
            )
            else -> null
        }

    private fun openReadingActions(item: ReadingDownloadItem) {
        val choices = item.availableActions.map { action ->
            when (action) {
                ReadingTransferAction.RETRY -> ChoiceOverlay.Choice(
                    action.wire, "Retry", "Remove the failed attempt and grab the same release again"
                )
                ReadingTransferAction.CANCEL -> ChoiceOverlay.Choice(
                    action.wire, "Cancel transfer", "Stop this transfer and delete its incomplete files", danger = true
                )
            }
        }
        if (choices.isEmpty()) {
            host?.notify("This transfer has no available actions")
            return
        }
        overlay.show(
            title = item.title,
            subtitle = item.status.replace('_', ' '),
            choices = choices,
            startIndex = choices.indexOfFirst { !it.danger }.coerceAtLeast(0),
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            val choice = choices.first { it.id == picked }
            if (choice.danger) confirmReading(item, choice) else runReading(item, picked)
        }
        host?.refreshHints()
    }

    private fun confirmReading(item: ReadingDownloadItem, choice: ChoiceOverlay.Choice) {
        overlay.show(
            title = choice.label + "?",
            subtitle = item.title + "\n" + choice.detail,
            choices = listOf(
                ChoiceOverlay.Choice("dismiss", "Keep transfer"),
                ChoiceOverlay.Choice(choice.id, choice.label, danger = true)
            ),
            startIndex = 0,
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            if (picked != "dismiss") runReading(item, picked)
        }
        host?.refreshHints()
    }

    private fun runReading(item: ReadingDownloadItem, action: String) {
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = if (action == "retry") "Retrying…" else "Canceling…"
        var refreshAfter = false
        actions.launch(scope, onIdle = { if (refreshAfter) refreshNow() }) {
            val result = when (action) {
                "retry" -> api.retryReadingDownload(item.id)
                "cancel" -> api.cancelReadingDownload(item.id)
                else -> null
            }
            when (result) {
                null -> host?.notify("Unknown action $action")
                is HubResult.Ok -> {
                    host?.notify(if (action == "retry") "Retrying ${item.title}" else "Canceled ${item.title}")
                    settleUntilMs = android.os.SystemClock.uptimeMillis() + PollSchedule.SETTLE_MS
                    refreshAfter = true
                }
                is HubResult.Failed -> {
                    statusLine.setTextColor(colors.dangerText)
                    statusLine.text = result.message
                    host?.notify(result.message)
                    // A failed re-grab leaves a durable retry ticket. Refresh
                    // immediately so the user sees that actionable state.
                    settleUntilMs = android.os.SystemClock.uptimeMillis() + PollSchedule.SETTLE_MS
                    refreshAfter = true
                }
            }
        }
    }

    private fun summarySuffix(item: ActivityItem): String = buildString {
        if (item.sizeBytes > 0) append(" · ").append(Fmt.bytes(item.sizeBytes))
        item.arr?.problem?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
    }

    private fun confirm(item: ActivityItem, choice: ChoiceOverlay.Choice) {
        overlay.show(
            title = choice.label + "?",
            subtitle = item.headline + "\n" + choice.detail,
            choices = listOf(
                ChoiceOverlay.Choice("cancel", "Cancel"),
                ChoiceOverlay.Choice(choice.id, choice.label, danger = true)
            ),
            // Cancel is first and starts under the cursor, so the reflex press
            // after opening this by accident is the harmless one.
            startIndex = 0,
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            if (picked != "cancel") run(item, picked)
        }
        host?.refreshHints()
    }

    private fun closeOverlay() {
        overlay.dismiss()
        host?.refreshHints()
    }

    private fun run(item: ActivityItem, action: String) {
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Working…"
        var refreshAfter = false
        actions.launch(scope, onIdle = { if (refreshAfter) refreshNow() }) {
            val result = when (action) {
                "stop", "start", "priority_up", "priority_down" -> api.downloadAction(item.id, action)
                "delete" -> api.deleteDownload(item.id, deleteFiles = false)
                "delete_with_data" -> api.deleteDownload(item.id, deleteFiles = true)
                "arr_remove" -> queueCall(item, blocklist = false, search = false)
                "arr_blocklist_and_search" -> queueCall(item, blocklist = true, search = true)
                else -> null
            }
            when (result) {
                null -> host?.notify("Unknown action $action")
                is HubResult.Ok -> {
                    DebugLog.log("net", "$action ok on ${item.id}")
                    host?.notify(pastTense(action) + " " + item.headline)
                    // The hub has already dropped its cached snapshot, so this
                    // reads through to the live services -- and keeps doing so
                    // for a few seconds, because the first read is usually too
                    // early to see the change.
                    settleUntilMs = android.os.SystemClock.uptimeMillis() + PollSchedule.SETTLE_MS
                    refreshAfter = true
                }
                is HubResult.Failed -> {
                    DebugLog.log("net", "$action failed on ${item.id}: ${result.message}")
                    statusLine.setTextColor(colors.dangerText)
                    statusLine.text = result.message
                    host?.notify(result.message)
                }
            }
        }
    }

    private suspend fun queueCall(item: ActivityItem, blocklist: Boolean, search: Boolean) =
        item.arr?.let {
            api.removeFromQueue(it.service, it.queueId, removeFromClient = true, blocklist, search)
        } ?: HubResult.Failed(FailureKind.UNKNOWN, "This item has no queue row to remove")

    private fun pastTense(action: String): String = when (action) {
        "stop" -> "Stopped"
        "start" -> "Started"
        "priority_up", "priority_down" -> "Updated queue priority for"
        "delete" -> "Removed"
        "delete_with_data" -> "Deleted"
        "arr_remove" -> "Removed from queue"
        "arr_blocklist_and_search" -> "Blocklisted, searching again for"
        else -> action
    }

    // ---- list --------------------------------------------------------------

    private inner class ItemAdapter : RecyclerView.Adapter<RowHolder>() {
        private val items = mutableListOf<ActivityItem>()

        fun itemAt(position: Int): ActivityItem? = items.getOrNull(position)

        /**
         * Rebinds in place whenever the set of ids is unchanged.
         *
         * This is the difference between a screen you can use and one you
         * cannot. notifyDataSetChanged on a two-second poll rebuilds every
         * holder and throws focus into the void; the same ids in the same order
         * means the view under the cursor is the same view, so it keeps focus
         * while its numbers move underneath it.
         */
        fun submit(next: List<ActivityItem>) {
            val sameShape = next.size == items.size &&
                next.indices.all { next[it].id == items[it].id }
            val focusedId = focusedItem()?.id
            items.clear()
            items.addAll(next)
            if (sameShape) {
                notifyItemRangeChanged(0, items.size, PAYLOAD_REBIND)
            } else {
                notifyDataSetChanged()
                settleFocus(focusedId)
            }
        }

        /**
         * Put focus somewhere sensible after the list changed shape, then
         * redraw the hints.
         *
         * Runs even when nothing was focused before, which is the case that was
         * wrong: arriving at this screen the list is empty, so there is no id to
         * restore, and returning early meant no row was ever focused *by us* and
         * the hint bar was never recomputed. X read blank on a transfer that
         * could plainly be stopped.
         */
        private fun settleFocus(id: String?) {
            val position = if (id == null) -1 else items.indexOfFirst { it.id == id }
            list.post {
                if (overlay.isOpen || attentionFilter.hasFocus() || deviceTransfers.hasFocus() || bandwidthButton.hasFocus()) return@post
                val target = if (position >= 0) {
                    list.findViewHolderForAdapterPosition(position)?.itemView
                } else {
                    null
                }
                if (target != null) {
                    target.requestFocus()
                } else if (list.focusedChild == null) {
                    // Either the focused item finished, or this is the first
                    // load. Landing on the first row beats leaving the window
                    // with no focus owner, which makes the next press do nothing.
                    list.getChildAt(0)?.requestFocus()
                }
                // Only now is there a focused row to read the contextual action
                // off. render() refreshes the hints too, but that runs a frame
                // too early -- before focus exists.
                host?.refreshHints()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = DownloadRowView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    val m = Styler.dpInt(parent.context, 4f)
                    setMargins(m, m, m, m)
                }
                // No scale on a full-width row: growing it 8% pushes it under its
                // neighbours instead of making it stand out from them.
                FocusDecorator.attach(this, ringVisible, scale = false)
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = items[position]
            (holder.itemView as DownloadRowView).bind(item)
            holder.itemView.activateOnTap { openActions(item) }
        }

        override fun onBindViewHolder(
            holder: RowHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            onBindViewHolder(holder, position)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class ReadingItemAdapter : RecyclerView.Adapter<RowHolder>() {
        private val items = mutableListOf<ReadingDownloadItem>()

        fun submit(next: List<ReadingDownloadItem>) {
            val sameShape = next.size == items.size && next.indices.all { next[it].id == items[it].id }
            val focusedID = focusedReadingItem()?.id
            items.clear()
            items.addAll(next)
            if (sameShape) {
                notifyItemRangeChanged(0, items.size, PAYLOAD_REBIND)
            } else {
                notifyDataSetChanged()
                list.post {
                    val position = items.indexOfFirst { it.id == focusedID }
                    val target = if (position >= 0) {
                        list.findViewHolderForAdapterPosition(position)?.itemView
                    } else {
                        list.getChildAt(0)
                    }
                    target?.requestFocus()
                    host?.refreshHints()
                }
            }
        }

        fun itemAt(position: Int): ReadingDownloadItem? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = ReadingDownloadRowView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    val margin = Styler.dpInt(parent.context, 4f)
                    setMargins(margin, margin, margin, margin)
                }
                FocusDecorator.attach(this, ringVisible, scale = false)
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = items[position]
            (holder.itemView as ReadingDownloadRowView).bind(item,
                position == 0 || ReadingTransferSummary.groupLabel(items[position - 1].contentType) !=
                    ReadingTransferSummary.groupLabel(item.contentType))
            holder.itemView.activateOnTap { openReadingActions(item) }
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int, payloads: MutableList<Any>) =
            onBindViewHolder(holder, position)

        override fun getItemCount(): Int = items.size
    }

    private fun focusedReadingItem(): ReadingDownloadItem? {
        if (!::list.isInitialized || mode != ContentMode.BOOKS) return null
        val focused = list.focusedChild ?: return null
        return readingAdapter.itemAt(list.getChildAdapterPosition(focused))
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PAYLOAD_REBIND = "rebind"
    }
}
