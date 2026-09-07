package com.pocketds.hub.screens.downloads

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.Stages
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.PollSchedule
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
    private val ringVisible: () -> Boolean
) : Screen {

    override val title: String = "Downloads"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var summaryLine: TextView
    private lateinit var statusLine: TextView
    private lateinit var list: RecyclerView
    private lateinit var overlay: ChoiceOverlay
    private val adapter = ItemAdapter()

    private var host: ScreenHost? = null
    private var pollJob: Job? = null
    private var visible = false
    private var failures = 0
    private var anyActive = false
    private var includeFinished = false

    /** Set while a mutation is in flight, so a poll cannot race its own result. */
    private var acting = false

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
            adapter = this@DownloadsScreen.adapter
            // No change animation. The default one cross-fades a *copy* of the
            // view being rebound, which takes focus off the row the user is on --
            // on a two-second poll, forever.
            itemAnimator = null
            setItemViewCacheSize(12)
            clipToPadding = false
            setPadding(
                Styler.dpInt(context, 8f), 0,
                Styler.dpInt(context, 8f), Styler.dpInt(context, 90f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        content.addView(list)

        overlay = ChoiceOverlay(context, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        return root
    }

    override fun onShow() {
        visible = true
        failures = 0
        if (adapter.itemCount == 0) statusLine.text = "Asking the hub…"
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
        ::list.isInitialized && list.getChildAt(0)?.requestFocus() == true

    override fun onPad(action: PadAction): Boolean {
        // Everything is consumed while the menu is open, or a directional press
        // would walk the selection out from behind a confirmation.
        if (overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return when (action) {
            PadAction.Activate -> focusedItem()?.let { openActions(it) } != null
            PadAction.Primary -> {
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
                if (!acting) fetchOnce()
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

    private suspend fun fetchOnce() {
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

    private fun render(body: ActivityResponse) {
        anyActive = body.anyActive
        adapter.submit(body.items)

        val s = body.summary
        summaryLine.setTextColor(colors.primaryText)
        summaryLine.text = buildString {
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
            body.items.isEmpty() && includeFinished -> "Nothing in the queues."
            body.items.isEmpty() -> "Nothing running. Ⓨ shows finished items."
            else -> "${body.items.size} items" + if (includeFinished) " · including finished" else ""
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
        val choices = item.actions.mapNotNull { action -> choiceFor(item, action) }
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
            val choice = choices.first { it.id == picked }
            if (choice.danger) confirm(item, choice) else run(item, picked)
        }
        // A and B now mean something else. Without this the bar still reads
        // "Actions / Show all" while a confirmation is on screen.
        host?.refreshHints()
    }

    private fun choiceFor(item: ActivityItem, action: String): ChoiceOverlay.Choice? =
        when (action) {
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
        acting = true
        statusLine.setTextColor(colors.mutedText)
        statusLine.text = "Working…"
        scope.launch {
            val result = when (action) {
                "stop", "start" -> api.downloadAction(item.id, action)
                "delete" -> api.deleteDownload(item.id, deleteFiles = false)
                "delete_with_data" -> api.deleteDownload(item.id, deleteFiles = true)
                "arr_remove" -> queueCall(item, blocklist = false, search = false)
                "arr_blocklist_and_search" -> queueCall(item, blocklist = true, search = true)
                else -> null
            }
            acting = false
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
                    refreshNow()
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
            holder.itemView.setOnClickListener { openActions(item) }
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

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PAYLOAD_REBIND = "rebind"
    }
}
