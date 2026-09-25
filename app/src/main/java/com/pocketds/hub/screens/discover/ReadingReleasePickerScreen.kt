package com.pocketds.hub.screens.discover

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingRelease
import com.pocketds.hub.model.ReadingRequestTarget
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.ChoiceOverlay
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
import kotlinx.coroutines.launch

/** BookKeeprr interactive search, for books, audiobooks, comics and manga. */
class ReadingReleasePickerScreen(
    private val api: HubApi,
    private val targets: List<ReadingRequestTarget>,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Choose a release"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var targetButton: TextView
    private lateinit var status: TextView
    private lateinit var retryButton: TextView
    private lateinit var list: RecyclerView
    private lateinit var overlay: ChoiceOverlay
    private val adapter = ReleaseAdapter()
    private var targetIndex = 0
    private var loadJob: Job? = null
    private var grabJob: Job? = null
    private var loaded = false
    private val target get() = targets[targetIndex]

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        targetButton = TextView(host.viewContext).apply {
            textSize = 17f; setTextColor(colors.primaryText)
            setPadding(dp(16), dp(12), dp(16), dp(8))
            background = Styler.cardBackground(context, colors)
            if (ReadingReleasePickerPolicy.hasTargetChooser(targets.size)) {
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { chooseTarget() }
            }
        }
        content.addView(targetButton, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(8), dp(12), 0)
        })
        val statusRow = LinearLayout(host.viewContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        status = TextView(host.viewContext).apply {
            textSize = 12f; setTextColor(colors.mutedText)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        statusRow.addView(status, LinearLayout.LayoutParams(0, WRAP, 1f))
        retryButton = TextView(host.viewContext).apply {
            text = "↻  Search again"
            contentDescription = "Search releases again"
            textSize = 12f
            setTextColor(colors.primaryText)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Styler.cardBackground(context, colors)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { search() }
        }
        statusRow.addView(retryButton, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(12) })
        content.addView(statusRow)
        list = RecyclerView(host.viewContext).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ReadingReleasePickerScreen.adapter
            itemAnimator = null
            setItemViewCacheSize(12)
            clipToPadding = false
            setPadding(dp(8), 0, dp(8), dp(70))
        }
        content.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        updateTarget()
        return root
    }

    override fun onShow() { if (!loaded && loadJob?.isActive != true) search() }
    override fun onHide() { scope.coroutineContext.cancelChildren(); if (overlay.isOpen) overlay.dismiss() }
    override fun onDestroyView() { scope.cancel() }
    override fun requestInitialFocus(): Boolean =
        list.getChildAt(0)?.requestFocus() == true || retryButton.requestFocus()
    override fun onSystemBack(): Boolean = if (overlay.isOpen) { overlay.dismiss(); true } else false
    override fun hints(): List<ButtonHint> = if (overlay.isOpen)
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    else buildList {
        add(ButtonHint.activate("Select release")); add(ButtonHint.back())
        add(ButtonHint.secondary("Search again"))
        if (ReadingReleasePickerPolicy.hasTargetChooser(targets.size)) add(ButtonHint.primary("Choose book"))
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        return when (action) {
            PadAction.Activate -> {
                (list.findFocus() ?: if (retryButton.hasFocus()) retryButton else targetButton).performClick(); true
            }
            PadAction.Primary -> if (ReadingReleasePickerPolicy.hasTargetChooser(targets.size)) { chooseTarget(); true } else false
            PadAction.Secondary -> { search(); true }
            else -> false
        }
    }

    private fun updateTarget() {
        targetButton.text = if (targets.size == 1) target.title else
            "${targetIndex + 1} of ${targets.size} · ${target.title}  ▾"
        targetButton.contentDescription = "Choose book: ${target.title}"
    }

    private fun chooseTarget() {
        if (grabJob?.isActive == true) return
        if (targets.size <= 1) return
        overlay.show("Choose book", "Search releases for one selected title", targets.mapIndexed { index, item ->
            ChoiceOverlay.Choice(index.toString(), item.title, selected = index == targetIndex)
        }) { value ->
            val index = value.toIntOrNull() ?: return@show
            if (index !in targets.indices || index == targetIndex) return@show
            targetIndex = index
            updateTarget()
            loaded = false
            adapter.submit(emptyList())
            search()
        }
    }

    private fun search() {
        if (target.seriesId <= 0) { status.text = "No BookKeeprr series is available for this title"; return }
        if (grabJob?.isActive == true) return
        loadJob?.cancel()
        val seriesID = target.seriesId
        status.setTextColor(colors.mutedText)
        status.text = "Searching indexers for ${target.title}…"
        loadJob = scope.launch {
            val fresh = api.readingReleases(seriesID, search = true)
            val result = if (fresh is HubResult.Failed) api.readingReleases(seriesID, search = false) else fresh
            if (seriesID != target.seriesId) return@launch
            when (result) {
                is HubResult.Ok -> {
                    loaded = true
                    adapter.submit(result.value.releases)
                    val rejected = result.value.releases.count { it.rejected && it.canGrab() }
                    val wrongFormat = result.value.releases.count { !it.canGrab() }
                    val errors = result.value.errors.size
                    status.text = ReadingReleasePickerPolicy.summary(result.value.releases.size, rejected, wrongFormat,
                        errors, fresh is HubResult.Failed)
                    list.post { (list.getChildAt(0) ?: retryButton).requestFocus() }
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = if (fresh is HubResult.Failed) "${fresh.message} · ${result.message}" else result.message
                }
            }
        }
    }

    private fun confirm(release: ReadingRelease) {
        if (release.ownership != "none") {
            host.notify(if (release.ownership == "in-library") "Already in the library" else "Already downloading")
            return
        }
        overlay.show(release.title,
            buildString {
                append("${Fmt.bytes(release.sizeBytes)} · ${release.seeders} seeders · ${release.indexer}")
                append("\n${release.formatLabel()}")
                if (!release.canGrab()) append(" · ${release.reason.ifBlank { "This format cannot be used for this request" }}")
                else if (release.rejected) append("\nOutside profile: ${release.reason.ifBlank { "BookKeeprr did not match this release" }}")
            }, (if (release.canGrab()) listOf(ChoiceOverlay.Choice("grab", if (release.rejected) "Download anyway" else "Download this release")) else emptyList()) +
                ChoiceOverlay.Choice("cancel", "Choose another")) { choice ->
            if (choice == "grab") grab(release)
        }
    }

    private fun grab(release: ReadingRelease) {
        if (grabJob?.isActive == true) return
        val seriesID = target.seriesId
        val targetTitle = target.title
        status.text = "Sending your choice to BookKeeprr…"
        grabJob = scope.launch {
            when (val result = api.grabReadingRelease(seriesID, release.id)) {
                is HubResult.Ok -> {
                    status.text = "Download queued · track it in Books activity or Transfers"
                    host.notify("BookKeeprr queued $targetTitle")
                    adapter.submit(adapter.items.map { if (it.id == release.id) it.copy(ownership = "downloading") else it })
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Search again to retry"
                }
            }
        }
    }

    private inner class ReleaseAdapter : RecyclerView.Adapter<ReleaseHolder>() {
        var items: List<ReadingRelease> = emptyList(); private set
        fun submit(value: List<ReadingRelease>) { items = value; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReleaseHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = Styler.cardBackground(context, colors)
                setPadding(dp(13), dp(9), dp(13), dp(9))
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            }
            val name = TextView(parent.context).apply { textSize = 14f; setTextColor(colors.primaryText); maxLines = 2 }
            val info = TextView(parent.context).apply { textSize = 11f; setTextColor(colors.mutedText) }
            val warning = TextView(parent.context).apply { textSize = 11f; setTextColor(colors.dangerText) }
            row.addView(name); row.addView(info); row.addView(warning)
            return ReleaseHolder(row, name, info, warning)
        }
        override fun onBindViewHolder(holder: ReleaseHolder, position: Int) {
            val release = items[position]
            holder.name.text = release.title
            holder.info.text = "${Fmt.bytes(release.sizeBytes)} · ${release.seeders} seeds · ${release.indexer}" +
                (if (release.freeleech) " · freeleech" else "") + " · ${release.formatLabel()}"
            holder.warning.text = when {
                release.ownership != "none" -> release.ownership.replace('-', ' ')
                !release.canGrab() -> release.reason.ifBlank { "Wrong format for this request" }
                release.rejected -> "Outside profile · ${release.reason}"
                else -> ""
            }
            holder.warning.visibility = if (holder.warning.text.isEmpty()) View.GONE else View.VISIBLE
            holder.itemView.activateOnTap { confirm(release) }
        }
    }

    private class ReleaseHolder(view: View, val name: TextView, val info: TextView, val warning: TextView) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT; const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}
