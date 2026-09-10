package com.pocketds.hub.screens.discover

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.Release
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Interactive search — the manual release picker, as Radarr and Sonarr have it.
 *
 * Rejected releases are shown, not hidden, and that is the whole point. On this
 * stack an interactive search for Gran Torino returned 100 releases of which
 * **none** were acceptable; hiding them would have produced an empty screen,
 * which is exactly the uninformative state the user was already in. Instead the
 * *arr's own sentence — "Existing file meets cutoff: Bluray-1080p" — is printed
 * verbatim under each one, and that sentence is the answer.
 *
 * A rejected release can still be grabbed. Overriding the profile is precisely
 * what a manual picker is for; it just takes a confirmation first.
 */
class ReleasesScreen(
    private val api: HubApi,
    private val mediaKey: String,
    private val mediaTitle: String,
    private val season: Int,
    private val episode: Int,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title: String = "Releases"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var overlay: ChoiceOverlay
    private val adapter = ReleaseAdapter()

    private var host: ScreenHost? = null
    private var searchJob: kotlinx.coroutines.Job? = null
    private var showRejected = true

    /** Everything the hub sent, before the rejected filter. */
    private var all: List<Release> = emptyList()

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)

        val root = FrameLayout(context).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        heading = TextView(context).apply {
            textSize = 15f
            setTextColor(colors.primaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = mediaTitle
            setPadding(
                Styler.dpInt(context, 14f), Styler.dpInt(context, 10f),
                Styler.dpInt(context, 14f), 0
            )
        }
        content.addView(heading)

        status = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(
                Styler.dpInt(context, 14f), Styler.dpInt(context, 2f),
                Styler.dpInt(context, 14f), Styler.dpInt(context, 6f)
            )
            text = "Asking every indexer… this takes a few seconds."
        }
        content.addView(status)

        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ReleasesScreen.adapter
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

    /**
     * Same rule as Discover: reload when there is nothing loaded, rather than
     * once ever. A pause/resume during startup cancels the in-flight search,
     * and a once-only flag would leave the screen empty for good.
     */
    override fun onShow() {
        if (adapter.itemCount == 0 && searchJob?.isActive != true) search()
    }

    override fun onHide() {
        if (overlay.isOpen) {
            overlay.dismiss()
            host?.refreshHints()
        }
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
        return listOf(
            ButtonHint.activate("Grab"),
            ButtonHint.back(),
            ButtonHint.secondary(if (showRejected) "Hide rejected" else "Show rejected"),
            ButtonHint.primary("Search again")
        )
    }

    override fun requestInitialFocus(): Boolean =
        ::list.isInitialized && list.getChildAt(0)?.requestFocus() == true

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return when (action) {
            PadAction.Activate -> focused()?.let { confirmGrab(it) } != null
            PadAction.Primary -> {
                search(force = true)
                true
            }
            PadAction.Secondary -> {
                showRejected = !showRejected
                applyFilter()
                host?.refreshHints()
                true
            }
            else -> false
        }
    }

    private fun focused(): Release? {
        if (!::list.isInitialized) return null
        val view = list.focusedChild ?: return null
        return adapter.itemAt(list.getChildAdapterPosition(view))
    }

    private fun search(force: Boolean = false) {
        status.setTextColor(colors.mutedText)
        status.text = "Asking every indexer… this takes a few seconds."
        if (force) adapter.submit(emptyList())
        searchJob?.cancel()
        searchJob = scope.launch {
            val started = System.currentTimeMillis()
            when (val result = api.releases(mediaKey, season, episode)) {
                is HubResult.Ok -> {
                    val body = result.value
                    all = body.releases
                    applyFilter()
                    val ms = System.currentTimeMillis() - started
                    DebugLog.log("net", "releases ${body.releases.size} in ${ms}ms")
                    status.setTextColor(
                        if (body.accepted == 0) colors.badgePending else colors.mutedText
                    )
                    status.text = buildString {
                        append(body.releases.size).append(" releases")
                        append(" · ").append(body.accepted).append(" acceptable")
                        if (body.accepted == 0 && body.releases.isNotEmpty()) {
                            // The single most useful thing this screen can say.
                            // An empty-looking result with no explanation is the
                            // state the user came here to get out of.
                            append(" — every one was refused, see why below")
                        }
                        if (body.cache.hit) append(" · cached ${body.cache.ageSeconds}s ago")
                    }
                    list.post { list.getChildAt(0)?.requestFocus() }
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                }
            }
            host?.refreshHints()
        }
    }

    private fun applyFilter() {
        adapter.submit(if (showRejected) all else all.filter { !it.rejected })
    }

    private fun confirmGrab(release: Release) {
        if (release.scopeBlocked) {
            val selectedScope = if (episode > 0) {
                "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            } else {
                "Season $season"
            }
            overlay.show(
                title = "More than $selectedScope",
                subtitle = "This release contains media outside the target you selected.",
                choices = listOf(ChoiceOverlay.Choice("cancel", "Choose another release")),
                onCancel = { host?.refreshHints() }
            ) { host?.refreshHints() }
            host?.refreshHints()
            return
        }
        val warning = if (release.rejected) {
            release.rejections.firstOrNull().orEmpty()
        } else {
            ""
        }
        overlay.show(
            title = release.title,
            subtitle = buildString {
                append(Fmt.bytes(release.sizeBytes))
                append(" · ").append(release.quality)
                append(" · ").append(release.seeders).append(" seeders")
                if (warning.isNotEmpty()) append("\n").append(warning)
            },
            choices = listOfNotNull(
                ChoiceOverlay.Choice("cancel", "Cancel"),
                ChoiceOverlay.Choice(
                    "grab",
                    if (release.rejected) "Grab anyway" else "Grab this release",
                    if (release.rejected) {
                        // Overriding the profile is the entire point of a manual
                        // picker, so this is offered rather than blocked -- but
                        // it is named honestly.
                        "Overrides what ${'$'}{release.indexer} and your profile decided"
                    } else {
                        release.indexer
                    },
                    danger = release.rejected
                )
            ),
            startIndex = 0,
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            if (picked == "grab") grab(release)
        }
        host?.refreshHints()
    }

    private fun grab(release: Release) {
        status.setTextColor(colors.mutedText)
        status.text = "Sending to the download client…"
        scope.launch {
            when (val result = api.grab(mediaKey, release.id, season, episode)) {
                is HubResult.Ok -> {
                    DebugLog.log("net", "grabbed ${release.id}")
                    status.setTextColor(colors.badgeAvailable)
                    status.text = "Grabbed — ${result.value.title.ifEmpty { release.title }}"
                    host?.notify("Grabbed ${release.quality} — check Downloads")
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host?.notify(result.message)
                }
            }
        }
    }

    private inner class ReleaseAdapter : RecyclerView.Adapter<RowHolder>() {
        private val items = mutableListOf<Release>()

        fun itemAt(position: Int): Release? = items.getOrNull(position)

        fun submit(next: List<Release>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = ReleaseRowView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    val m = Styler.dpInt(parent.context, 4f)
                    setMargins(m, m, m, m)
                }
                FocusDecorator.attach(this, ringVisible, scale = false)
            }
            return RowHolder(row)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val release = items[position]
            (holder.itemView as ReleaseRowView).bind(release)
            holder.itemView.setOnClickListener { confirmGrab(release) }
        }

        override fun getItemCount(): Int = items.size
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** One candidate release. */
private class ReleaseRowView(
    context: android.content.Context,
    private val colors: PocketColors
) : LinearLayout(context) {

    private val titleView: TextView
    private val statsView: TextView
    private val rejectionView: TextView
    private val badge: TextView

    init {
        orientation = VERTICAL
        background = Styler.cardBackground(context, colors)
        val h = Styler.dpInt(context, 12f)
        val v = Styler.dpInt(context, 9f)
        setPadding(h, v, h, v)
        Styler.makeFocusable(this)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(header, LayoutParams(MATCH, WRAP))

        badge = TextView(context).apply {
            textSize = 10f
            setPadding(
                Styler.dpInt(context, 7f), Styler.dpInt(context, 2f),
                Styler.dpInt(context, 7f), Styler.dpInt(context, 2f)
            )
        }
        header.addView(badge, LayoutParams(WRAP, WRAP).apply {
            rightMargin = Styler.dpInt(context, 8f)
        })

        titleView = TextView(context).apply {
            textSize = 14f
            setTextColor(colors.primaryText)
            maxLines = 1
            // MIDDLE, not END: the release group and codec live at the end of
            // the name and are exactly what distinguishes two otherwise
            // identical-looking rows.
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        header.addView(titleView, LayoutParams(0, WRAP, 1f))

        statsView = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 3f), 0, 0)
        }
        addView(statsView, LayoutParams(MATCH, WRAP))

        rejectionView = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.dangerText)
            maxLines = 2
            visibility = GONE
        }
        addView(rejectionView, LayoutParams(MATCH, WRAP))
    }

    fun bind(release: Release) {
        titleView.text = release.title

        badge.text = release.quality.ifEmpty { "?" }
        badge.setTextColor(colors.accentText)
        // Qualified: GradientDrawable has a `colors` property of its own, and an
        // unqualified reference inside apply{} resolves to that instead.
        val badgeFill = if (release.rejected) {
            this@ReleaseRowView.colors.mutedText
        } else {
            this@ReleaseRowView.colors.badgeAvailable
        }
        badge.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 9f)
            setColor(badgeFill)
        }

        statsView.text = buildString {
            append(Fmt.bytes(release.sizeBytes))
            append(" · ").append(release.seeders).append("s/").append(release.leechers).append("p")
            if (release.ageDays > 0) append(" · ").append(release.ageDays).append("d old")
            if (release.freeleech) append(" · freeleech")
            if (release.languages.isNotEmpty()) {
                append(" · ").append(release.languages.joinToString("/"))
            }
            append(" · ").append(release.indexer)
        }

        // Verbatim, and joined rather than truncated to the first: "not wanted
        // in profile" and "not enough seeders" are different problems with
        // different fixes, and seeing only one of them sends you the wrong way.
        val reason = release.rejections.joinToString(" · ")
        rejectionView.text = reason
        rejectionView.visibility = if (reason.isEmpty()) GONE else VISIBLE
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
