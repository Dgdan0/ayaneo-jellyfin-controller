package com.pocketds.hub.screens.offline

import com.pocketds.hub.offline.OfflineChanges
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeLabel
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.offline.OfflineBatch
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineCatalogEntry
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineState
import com.pocketds.hub.screens.library.SubtitleScreen
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.state.Fmt
import kotlinx.coroutines.launch

/**
 * Downloads: what is on this handheld, grouped by the Jellyfin library each
 * title came from, and the queue of what is still coming. A bar shows the
 * space used against what is left.
 *
 * It is the prototype's page (#11): the heading and its line, a glass
 * capsule, library headings in small capitals, glass posters and glass queue
 * rows with the white bar; the actions open as the side sheet.
 */
class OfflineScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean,
    private val targetItemId: String = ""
) : Screen {
    override val title = "Downloads"
    override val focusOnShow = true
    override val showsOwnTitle = true

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    /** Library lookups for downloads made before manifests named one; each asked once. */
    private val lookedUp = mutableSetOf<String>()

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var tabs: com.pocketds.hub.ui.BlobSegmentedView
    private lateinit var summary: TextView
    private lateinit var storageLabel: TextView
    private lateinit var storageFill: View
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var overlay: ChoiceOverlay
    private var mode = MODE_LIBRARY
    private var selectedId = ""
    private var targetOpened = false
    private var renderedSignature = ""
    private var renderPosted = false
    private val queueRows = mutableMapOf<String, QueueRowBinding>()
    private val queueHeaders = mutableMapOf<String, QueueHeaderBinding>()
    private val offlineChanges = OfflineChanges { scheduleRender() }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = "On your Pocket"
                        textSize = 21f; typeface = com.pocketds.hub.ui.Type.display(context, 800); includeFontPadding = false
                        setTextColor(colors.primaryText)
                    })
                    summary = TextView(context).apply {
                        textSize = 12f; setTextColor(com.pocketds.hub.ui.SettingsCard.GLASS_QUIET)
                        setPadding(0, dp(6), 0, 0)
                    }
                    addView(summary)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                // Space used on the chosen storage against what is left.
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    storageLabel = TextView(context).apply { textSize = 11f; setTextColor(com.pocketds.hub.ui.SettingsCard.GLASS_QUIET) }
                    addView(storageLabel)
                    storageFill = View(context).apply {
                        background = com.pocketds.hub.ui.ThemeGradientDrawable.rounded(dp(4).toFloat(), colors.accent)
                    }
                    addView(FrameLayout(context).apply {
                        background = com.pocketds.hub.ui.ThemeGradientDrawable.rounded(dp(4).toFloat(), com.pocketds.hub.ui.ProgressLine.GLASS_TRACK)
                        clipToOutline = true
                        addView(storageFill, FrameLayout.LayoutParams(0, MATCH))
                    }, LinearLayout.LayoutParams(dp(200), dp(8)).apply { topMargin = dp(5) })
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(16); bottomMargin = dp(6) })
                tabs = com.pocketds.hub.ui.BlobSegmentedView(context, colors, ringVisible).apply {
                    useGlassTrack()
                    setOptions(listOf(com.pocketds.hub.ui.BlobSegmentedView.Option("library", "Downloaded"),
                        com.pocketds.hub.ui.BlobSegmentedView.Option("queue", "Queue")), "library")
                    onPick = { id -> switchTo(if (id == "queue") MODE_QUEUE else MODE_LIBRARY) }
                    onOptionFocused = { host.refreshHints() }
                }
                addView(tabs, LinearLayout.LayoutParams(WRAP, WRAP))
            })
            addView(View(context), LinearLayout.LayoutParams(MATCH, dp(6)))
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            scroll = FocusScrollView(context).apply {
                clipToPadding = false; setPadding(0, 0, 0, dp(20)); addView(content)
            }
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        // The actions as the side sheet; removing still asks on the centred card.
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        val target = if (!targetOpened && targetItemId.isNotBlank()) repository.forItem(targetItemId) else null
        if (target != null) { mode=MODE_QUEUE; selectedId=target.id }
        render(force = true)
        if (!targetOpened && targetItemId.isNotBlank()) {
            targetOpened=true
            content.post {
                if(target!=null) {requestInitialFocus();showDownloadDetails(target)}
                else host.notify("This download is no longer on this AYANEO.")
            }
        }
        return root
    }

    override fun onShow() {
        offlineChanges.start(host.viewContext)
        render(force = true)
        lookUpLibraries()
        if (repository.batches().any { batch ->
                !batch.paused && batch.jobs.any { it.state in setOf(OfflineState.QUEUED, OfflineState.WAITING) }
            } || repository.outbox().isNotEmpty()
        ) OfflineDownloadService.start(host.viewContext)
    }
    override fun onHide() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        cancelScheduledRender()
        offlineChanges.stop()
        if (::overlay.isInitialized) overlay.dismiss()
    }

    fun openManager() {
        if (!::content.isInitialized) return
        mode = MODE_QUEUE
        selectedId = ""
        scroll.scrollTo(0, 0)
        render(force = true)
        tabs.post { requestInitialFocus() }
    }
    override fun onDestroyView() {
        cancelScheduledRender()
        offlineChanges.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun requestInitialFocus(): Boolean {
        val selected = findTagged(content, selectedId)
        return selected?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true || tabs.focus()
    }

    override fun hints(): List<ButtonHint> {
        if (overlay.isOpen) return listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        if (tabs.hasFocus()) return listOf(ButtonHint.activate("Show"), ButtonHint.back())
        val row = focusedRow()
        return buildList {
            when (row) {
                is TaggedCatalog -> {
                    add(ButtonHint.activate(if (row.value.isSeries) "Open" else "Play"))
                    if(!row.value.isSeries) add(ButtonHint.secondary("More actions"))
                    add(ButtonHint.primary("Remove download"))
                }
                is TaggedDownload -> {
                    if (row.value.state == OfflineState.COMPLETE) add(ButtonHint.activate("Play"))
                    else add(ButtonHint.activate("Manage"))
                    if (row.value.state == OfflineState.COMPLETE) add(ButtonHint.secondary("More actions"))
                    if (row.value.state == OfflineState.FAILED || row.value.state == OfflineState.WAITING) {
                        add(ButtonHint.secondary("Retry"))
                    }
                    add(ButtonHint.primary("Remove"))
                }
                is TaggedBatch -> {
                    add(ButtonHint.activate(if (row.value.paused) "Resume batch" else "Pause batch"))
                    add(ButtonHint.primary("Remove batch"))
                }
            }
            add(ButtonHint.back())
        }
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (action is PadAction.Step && action.direction == Direction.DOWN && tabs.hasFocus()) {
            return firstFocusable(content)?.requestFocus() == true
        }
        val row = focusedRow()
        return when (action) {
            PadAction.Activate -> when (row) {
                is TaggedCatalog -> { openCatalog(row.value); true }
                is TaggedDownload -> {
                    if (row.value.state == OfflineState.COMPLETE) host.playItem(row.value.manifest.item.id)
                    else showDownloadDetails(row.value)
                    true
                }
                is TaggedBatch -> {
                    if (row.value.paused) OfflineDownloadService.resumeBatch(host.viewContext, row.value.id)
                    else OfflineDownloadService.pauseBatch(host.viewContext, row.value.id)
                    true
                }
                else -> false
            }
            PadAction.Secondary -> when {
                row is TaggedCatalog && !row.value.isSeries -> {
                    row.value.rows.firstOrNull()?.let(::showMediaOptions)
                    true
                }
                row is TaggedDownload && row.value.state == OfflineState.COMPLETE -> {
                    showMediaOptions(row.value); true
                }
                row is TaggedDownload && row.value.state in setOf(OfflineState.FAILED, OfflineState.WAITING) -> {
                    OfflineDownloadService.retry(host.viewContext, row.value.id); true
                }
                else -> false
            }
            PadAction.Primary -> when (row) {
                is TaggedCatalog -> { confirmRemoveCatalog(row.value); true }
                is TaggedDownload -> { confirmRemove(row.value); true }
                is TaggedBatch -> { confirmRemoveBatch(row.value); true }
                else -> false
            }
            PadAction.Refresh -> { render(force = true); true }
            else -> false
        }
    }

    private fun render(force: Boolean = false) {
        if (!::content.isInitialized || overlay.isOpen) return
        val batches = repository.batches()
        val completed = if (mode == MODE_LIBRARY) repository.completed() else emptyList()
        val signature = if (mode == MODE_QUEUE) {
            queueStructureSignature(batches)
        } else completed.joinToString("|") { "${it.id}:${it.updatedAt}:${it.localPath}" }
        if (!force && signature == renderedSignature) {
            if (mode == MODE_QUEUE) updateQueueProgress(batches)
            return
        }
        renderedSignature = signature
        val hadFocus = host.viewContext.let { (it as? android.app.Activity)?.currentFocus }
        val focusedTag = hadFocus?.tag
        if (focusedTag is TaggedDownload) selectedId = focusedTag.value.id
        if (focusedTag is TaggedBatch) selectedId = focusedTag.value.id
        if (focusedTag is TaggedCatalog) selectedId = focusedTag.value.key
        val previousScrollY = scroll.scrollY
        content.removeAllViews()
        queueRows.clear()
        queueHeaders.clear()
        tabs.select(if (mode == MODE_QUEUE) "queue" else "library")
        val pending = batches.sumOf { it.jobs.count { job -> job.state != OfflineState.COMPLETE } }
        tabs.relabel("queue", if (pending > 0) "Queue · $pending" else "Queue")
        renderStorage()
        if (mode == MODE_QUEUE) renderQueue(batches) else renderLibrary(completed, batches)
        content.post {
            val restoredFocus = if (hadFocus != null &&
                (focusedTag is TaggedDownload || focusedTag is TaggedBatch || focusedTag is TaggedCatalog)
            ) findTagged(content, selectedId)?.requestFocus() == true else false
            if (!restoredFocus) {
                scroll.scrollTo(0, previousScrollY.coerceAtMost((content.height - scroll.height).coerceAtLeast(0)))
            }
        }
    }

    /** Collapse one transfer's burst of state, progress, and artwork updates into one redraw. */
    private fun scheduleRender() {
        if (!::content.isInitialized || renderPosted) return
        renderPosted = true
        content.postDelayed(renderRunnable, UPDATE_COALESCE_MS)
    }

    private val renderRunnable = Runnable {
        renderPosted = false
        render()
    }

    private fun cancelScheduledRender() {
        if (!::content.isInitialized) return
        content.removeCallbacks(renderRunnable)
        renderPosted = false
    }

    private fun renderQueue(allBatches: List<OfflineBatch>) {
        val batches = allBatches.filter { it.jobs.any { job -> job.state != OfflineState.COMPLETE } }
        setQueueSummary(batches)
        if (batches.isEmpty()) {
            empty("No downloads are waiting. Use the download icon on a movie, episode, season, or series.")
            return
        }
        batches.forEach { batch ->
            content.addView(batchHeader(batch))
            batch.jobs.filter { it.state != OfflineState.COMPLETE }.forEach { content.addView(downloadRow(it)) }
        }
    }

    /** Progress changes many times per second. Keep these existing views and update their values
     * in place; replacing the whole tree here was the visible refresh during every download. */
    private fun updateQueueProgress(allBatches: List<OfflineBatch>) {
        val batches = allBatches.filter { it.jobs.any { job -> job.state != OfflineState.COMPLETE } }
        setQueueSummary(batches)
        batches.forEach { batch ->
            queueHeaders[batch.id]?.bind(batch)
            batch.jobs.filter { it.state != OfflineState.COMPLETE }.forEach { row ->
                queueRows[row.id]?.bind(row)
            }
        }
    }

    private fun setQueueSummary(batches: List<OfflineBatch>) {
        val queued = batches.sumOf { it.jobs.count { job -> job.state != OfflineState.COMPLETE } }
        summary.text = if (queued == 0) "Nothing waiting" else "$queued waiting \u00b7 one at a time, in this order"
    }

    private fun queueStructureSignature(batches: List<OfflineBatch>): String =
        batches.flatMap { batch -> batch.jobs.map { row ->
            "${batch.id}:${batch.paused}:${row.id}:${row.state}:${row.totalBytes}:${row.error}"
        } }.joinToString("|")

    private fun renderLibrary(completed: List<OfflineDownload>, batches: List<OfflineBatch>) {
        val batchTitles = batches.associate { it.id to it.title }
        val entries = OfflineCatalog.titles(completed, batchTitles, com.pocketds.hub.offline.OfflineLibraryNames.all(host.viewContext))
        summary.text = "Plays without the server · grouped by library"
        if (entries.isEmpty()) { empty("Downloaded movies and series will appear here and stay playable without a network."); return }
        OfflineCatalog.byLibrary(entries).forEach { (library, group) ->
            val size = group.sumOf { entry -> entry.rows.sumOf { it.totalBytes } }
            content.addView(sectionLabel(library, "${group.size} title${if (group.size == 1) "" else "s"} · ${Fmt.bytes(size)}"))
            val grid = com.pocketds.hub.ui.PosterGridLayout(host.viewContext).apply {
                setPadding(0, dp(2), dp(8), dp(10))
            }
            group.forEach { value ->
                grid.addView(catalogCard(value), GridLayout.LayoutParams().apply {
                    width = 0
                    height = WRAP
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
                    setMargins(dp(7), dp(7), dp(7), dp(7))
                })
            }
            content.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun renderStorage() {
        val used = repository.completed().sumOf { it.totalBytes }
        val free = repository.availableBytes()
        storageLabel.text = "${Fmt.bytes(used)} used  ·  ${Fmt.bytes(free)} free"
        val fraction = if (used + free > 0) used.toDouble() / (used + free) else 0.0
        storageFill.post {
            val width = ((storageFill.parent as View).width * fraction).toInt().coerceAtLeast(if (used > 0) dp(4) else 0)
            (storageFill.layoutParams as FrameLayout.LayoutParams).let { if (it.width != width) { it.width = width; storageFill.layoutParams = it } }
        }
    }

    private fun switchTo(target: Int) {
        if (mode == target) return
        mode = target
        selectedId = ""
        renderedSignature = ""
        render(force = true)
        host.refreshHints()
    }

    /**
     * Downloads made before manifests named their library ask the hub once,
     * while it is reachable, and remember the answer.
     */
    private fun lookUpLibraries() {
        val known = com.pocketds.hub.offline.OfflineLibraryNames.all(host.viewContext)
        val missing = OfflineCatalog.titles(repository.completed()).filter { it.library.isBlank() && it.key !in known && lookedUp.add(it.key) }
        if (missing.isEmpty()) return
        scope.launch {
            var found = false
            missing.forEach { entry ->
                val item = (api.libraryItem(entry.key) as? com.pocketds.hub.net.HubResult.Ok)?.value?.item ?: return@forEach
                val name = item.library?.name.orEmpty()
                if (name.isNotBlank()) {
                    com.pocketds.hub.offline.OfflineLibraryNames.remember(host.viewContext, entry.key, name)
                    found = true
                }
            }
            if (found && mode == MODE_LIBRARY) { renderedSignature = ""; render(force = true) }
        }
    }

    private fun catalogCard(value: OfflineCatalogEntry): View {
        val first = value.rows.first()
        val poster = repository.artworkFile(first, "poster").takeIf { it.isFile && it.length() > 0 }
        val size = value.rows.sumOf { it.totalBytes }
        val hit = SearchHit(
            media = MediaRef(
                key = value.key,
                type = if (value.isSeries) "series" else "movie",
                title = value.title,
                poster = poster?.let { Uri.fromFile(it).toString() }.orEmpty()
            ),
            subtitle = if (value.isSeries) {
                "${value.rows.size} episode${if (value.rows.size == 1) "" else "s"} · ${Fmt.bytes(size)}"
            } else Fmt.bytes(size),
            jellyfinItemId = value.key
        )
        val card = PosterCardView(host.viewContext, colors).apply {
            tag = TaggedCatalog(value)
            contentDescription = if (value.isSeries) {
                "${value.title}, ${value.rows.size} downloaded episodes"
            } else "${value.title}, downloaded movie"
            val loader = Artwork.loader(api, context)
            bind(hit, loader, { it }, showAvailability = false)
            FocusDecorator.attach(this, ringVisible)
            FocusDecorator.listen(this, ringVisible) { view, hasFocus ->
                if (hasFocus) {
                    selectedId = value.key
                    host.refreshHints()
                }
            }
            activateOnTap { openCatalog(value) }
        }
        if(value.isSeries) return card
        return LinearLayout(host.viewContext).apply {
            orientation=LinearLayout.VERTICAL
            clipChildren=false; clipToPadding=false
            addView(card,LinearLayout.LayoutParams(MATCH,WRAP))
            addView(TextView(context).apply {
                // The size, and a ⋯ a finger can tap for the actions Y opens.
                text="${Fmt.bytes(size)}   ⋯";textSize=11f;setTextColor(colors.mutedText)
                setPadding(dp(2),dp(3),dp(8),dp(5))
                contentDescription="More actions for ${value.title}"
                activateOnTap { value.rows.firstOrNull()?.let(::showMediaOptions) }
            })
        }
    }

    private fun openCatalog(value: OfflineCatalogEntry) {
        if (value.isSeries) {
            host.push(OfflineSeriesScreen(api, value.key, value.title, ringVisible))
        } else {
            value.rows.firstOrNull()?.let { host.playItem(it.manifest.item.id) }
        }
    }

    private fun showMediaOptions(row: OfflineDownload) {
        showOfflineTitleMenu(host, api, overlay, row, ringVisible)
    }

    private fun confirmRemoveCatalog(value: OfflineCatalogEntry) {
        val noun = if (value.isSeries) "${value.rows.size} downloaded episodes" else "the downloaded movie"
        overlay.confirm(
            "Remove ${value.title}?",
            "${noun.replaceFirstChar(Char::uppercase)} will be deleted from this device.",
            action = "Remove",
            keep = "Keep download",
            onCancel = host::refreshHints
        ) {
            value.rows.forEach { row ->
                OfflineDownloadService.remove(host.viewContext, row.id)
            }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun batchHeader(batch: OfflineBatch): View {
        lateinit var detail: TextView
        lateinit var control: TextView
        val view = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            glassRow(this, 11f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            tag = TaggedBatch(batch)
            Styler.makeFocusable(this)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = batch.title; textSize = 13f; textWeight(700); setTextColor(colors.primaryText) })
            detail = TextView(context).apply {
                val active = batch.jobs.firstOrNull { it.state == OfflineState.DOWNLOADING }
                val transfer = active?.speedBytesPerSecond?.takeIf { it > 0 }?.let { speed ->
                    val remaining = batch.totalBytes - batch.downloadedBytes
                    " · ${Fmt.speed(speed)} · ${Fmt.eta(remaining / speed)} left"
                }.orEmpty()
                text = "${batch.completeCount}/${batch.jobs.size} complete · ${Fmt.bytes(batch.downloadedBytes)} / ${Fmt.bytes(batch.totalBytes)}$transfer"
                textSize = 10f; setTextColor(colors.mutedText)
            }
            addView(detail)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        control = TextView(context).apply {
            text = if (batch.paused) "▶" else "Ⅱ"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(colors.accent)
        }
        addView(control, LinearLayout.LayoutParams(dp(44), dp(40)))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7); bottomMargin = dp(3) }
        decorate(this)
        activateOnTap {
            if (batch.paused) OfflineDownloadService.resumeBatch(context, batch.id)
            else OfflineDownloadService.pauseBatch(context, batch.id)
        }
        }
        QueueHeaderBinding(view, detail, control).also {
            queueHeaders[batch.id] = it
            it.bind(batch)
        }
        return view
    }

    private fun downloadRow(row: OfflineDownload): View {
        lateinit var image: ImageView
        lateinit var state: TextView
        lateinit var progress: ProgressBar
        lateinit var percent: TextView
        val view = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(10), dp(6)); tag = TaggedDownload(row)
            glassRow(this, 13f)
            Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(dp(112), dp(63)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(TextView(context).apply {
                    text = episodeTitle(row); textSize = 14f; maxLines = 1; setTextColor(colors.primaryText)
                })
                state = TextView(context).apply { textSize = 12f; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
                addView(state)
                // The white bar, 5dp, as the prototype's queue rows have it.
                progress = ProgressLine.create(context, colors, android.graphics.Color.WHITE)
                addView(progress, LinearLayout.LayoutParams(MATCH, dp(5)).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            percent = TextView(context).apply { textSize = 13f; gravity = Gravity.CENTER; setTextColor(colors.accent) }
            addView(percent, LinearLayout.LayoutParams(dp(58), MATCH))
            minimumHeight=dp(76)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5); marginStart = dp(10) }
            decorate(this)
            activateOnTap {
                if (row.state == OfflineState.COMPLETE) host.playItem(row.manifest.item.id) else showDownloadDetails(row)
            }
        }
        QueueRowBinding(view, state, progress, percent).also {
            queueRows[row.id] = it
            it.bind(row)
        }
        loadArtwork(image, row)
        return view
    }

    private fun sectionLabel(title: String, detail: String) = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        addView(TextView(context).apply {
            // The prototype's group heading, small capitals at 55%.
            text = title.uppercase(); textSize = 12f; textWeight(800); letterSpacing = 0.12f; setTextColor(GROUP_INK)
        })
        addView(TextView(context).apply { text = detail; textSize = 11f; setTextColor(colors.mutedText); setPadding(dp(10), 0, 0, dp(1)) })
        setPadding(0, dp(12), dp(2), dp(2))
    }

    private fun empty(message: String) {
        content.addView(TextView(host.viewContext).apply {
            text = message; textSize = 14f; gravity = Gravity.CENTER; setTextColor(colors.mutedText)
            setPadding(dp(40), dp(70), dp(40), dp(40))
        })
    }

    /** A queue row's or batch's panel of the page's glass, with the white ring on focus. */
    private fun glassRow(view: View, cornerDp: Float) {
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(view, Styler.dp(view.context, cornerDp))
        view.foreground = Styler.focusOutline(view.context, colors, cornerDp, 3f)
    }

    private fun decorate(view: View) {
        FocusDecorator.attach(view, ringVisible, scale = false)
        FocusDecorator.listen(view, ringVisible) { focused, hasFocus ->
            if (hasFocus) {
                when (val value = focused.tag) {
                    is TaggedCatalog -> selectedId = value.value.key
                    is TaggedDownload -> selectedId = value.value.id
                    is TaggedBatch -> selectedId = value.value.id
                }
                host.refreshHints()
            }
        }
    }

    private fun showDownloadDetails(row: OfflineDownload) {
        overlay.show(
            episodeTitle(row),
            "${stateText(row)}\n${Fmt.bytes(row.bytesDownloaded)} of ${Fmt.bytes(row.totalBytes)}\n${row.error}",
            buildList {
                if (row.state == OfflineState.COMPLETE) {
                    add(ChoiceOverlay.Choice("play", "Play downloaded video"))
                    add(ChoiceOverlay.Choice("audio", "Audio & subtitles", "Choose saved tracks before playback"))
                    add(ChoiceOverlay.Choice("subtitles", "Find / update subtitles", "Search providers and update this downloaded copy"))
                }
                if (row.state == OfflineState.PAUSED) add(ChoiceOverlay.Choice("resume", "Resume download"))
                else if (row.state in setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING, OfflineState.WAITING)) {
                    add(ChoiceOverlay.Choice("pause", "Pause download"))
                }
                if (row.state == OfflineState.FAILED || row.state == OfflineState.WAITING) add(ChoiceOverlay.Choice("retry", "Retry now"))
                add(ChoiceOverlay.Choice("close", "Close"))
            }, onCancel = host::refreshHints
        ) {
            when (it) {
                "play" -> host.playItem(row.manifest.item.id)
                "audio" -> host.openPlaybackOptions(row.manifest.item.id)
                "subtitles" -> host.push(SubtitleScreen(api,row.manifest.item.id,row.manifest.item.title,ringVisible))
                "pause" -> OfflineDownloadService.pauseItem(host.viewContext, row.id)
                "resume" -> OfflineDownloadService.resumeItem(host.viewContext, row.id)
                "retry" -> OfflineDownloadService.retry(host.viewContext, row.id)
            }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun confirmRemove(row: OfflineDownload) {
        overlay.confirm("Remove ${row.manifest.item.title}?", "The local file and downloaded subtitles will be deleted.",
            action = "Remove", keep = "Keep download", onCancel = host::refreshHints
        ) { OfflineDownloadService.remove(host.viewContext, row.id); host.refreshHints() }
        host.refreshHints()
    }

    private fun confirmRemoveBatch(batch: OfflineBatch) {
        overlay.ask("Cancel ${batch.title}?", "Choose whether completed episodes remain in Downloaded.",
            listOf(
                ChoiceOverlay.Choice("keep", "Keep batch"),
                ChoiceOverlay.Choice("cancel", "Cancel pending · keep completed"),
                ChoiceOverlay.Choice("remove", "Remove everything", danger = true)
            ),
            onCancel = host::refreshHints
        ) {
            if (it == "cancel") OfflineDownloadService.cancelBatchKeepCompleted(host.viewContext, batch.id)
            if (it == "remove") OfflineDownloadService.removeBatch(host.viewContext, batch.id)
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun focusedRow(): Any? = (host.viewContext as? android.app.Activity)?.currentFocus?.tag

    private fun findTagged(root: ViewGroup, id: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            val match = when (val tag = child.tag) {
                is TaggedCatalog -> tag.value.key == id
                is TaggedDownload -> tag.value.id == id
                is TaggedBatch -> tag.value.id == id
                else -> false
            }
            if (match) return child
            if (child is ViewGroup) findTagged(child, id)?.let { return it }
        }
        return null
    }

    private fun firstFocusable(root: ViewGroup): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.isFocusable && child.visibility == View.VISIBLE) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }

    private inner class QueueHeaderBinding(
        private val view: View,
        private val detail: TextView,
        private val control: TextView
    ) {
        fun bind(batch: OfflineBatch) {
            view.tag = TaggedBatch(batch)
            val active = batch.jobs.firstOrNull { it.state == OfflineState.DOWNLOADING }
            val transfer = active?.speedBytesPerSecond?.takeIf { it > 0 }?.let { speed ->
                val remaining = (batch.totalBytes - batch.downloadedBytes).coerceAtLeast(0L)
                " \u00b7 ${Fmt.speed(speed)} \u00b7 ${Fmt.eta(remaining / speed)} left"
            }.orEmpty()
            detail.text = "${batch.completeCount}/${batch.jobs.size} complete \u00b7 " +
                "${Fmt.bytes(batch.downloadedBytes)} / ${Fmt.bytes(batch.totalBytes)}$transfer"
            control.text = if (batch.paused) "\u25b6" else "\u2161"
        }
    }

    private inner class QueueRowBinding(
        private val view: View,
        private val state: TextView,
        private val progress: ProgressBar,
        private val percent: TextView
    ) {
        fun bind(row: OfflineDownload) {
            view.tag = TaggedDownload(row)
            state.text = stateText(row)
            state.setTextColor(if (row.state == OfflineState.FAILED) colors.dangerText else colors.mutedText)
            progress.progress = (row.progress * 1_000).toInt()
            progress.visibility = if (row.state == OfflineState.COMPLETE) View.GONE else View.VISIBLE
            percent.text = if (row.state == OfflineState.COMPLETE) "\u2713" else "${(row.progress * 100).toInt()}%"
        }
    }

    private fun stateText(row: OfflineDownload): String = buildList {
        add(row.state.wire.replaceFirstChar { it.uppercase() })
        if (row.state != OfflineState.COMPLETE) add("${Fmt.bytes(row.bytesDownloaded)} / ${Fmt.bytes(row.totalBytes)}")
        if (row.state == OfflineState.DOWNLOADING && row.speedBytesPerSecond > 0) {
            add(Fmt.speed(row.speedBytesPerSecond))
            add("${Fmt.eta((row.totalBytes - row.bytesDownloaded).coerceAtLeast(0) / row.speedBytesPerSecond)} left")
        }
        if (row.error.isNotBlank()) add(row.error)
    }.joinToString(" · ")

    private fun episodeTitle(row: OfflineDownload): String {
        val item = row.manifest.item
        return if (item.type == "episode") EpisodeLabel.of(item.seasonNumber, item.indexNumber, item.title)
        else item.title
    }

    private fun loadImage(view: ImageView, path: String) =
        Artwork.bindHub(view, api, path, opaque = true, placeholderColor = colors.posterPlaceholder)
    private fun loadArtwork(view: ImageView, row: OfflineDownload) {
        val local = sequenceOf("thumb", "poster", "backdrop")
            .map { repository.artworkFile(row, it) }.firstOrNull { it.isFile && it.length() > 0 }
        if (local != null) {
            Artwork.bind(view, Artwork.loader(api, view.context), local,
                opaque = true, placeholderColor = colors.posterPlaceholder)
        } else loadImage(view, row.manifest.item.thumb.ifEmpty { row.manifest.item.poster })
    }
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private data class TaggedDownload(val value: OfflineDownload)
    private data class TaggedBatch(val value: OfflineBatch)
    private data class TaggedCatalog(val value: OfflineCatalogEntry)
    private companion object {
        /** Glass: a library's heading over its titles, white at 55%. */
        const val GROUP_INK = 0x8CFFFFFF.toInt()
        const val UPDATE_COALESCE_MS = 180L
        const val MODE_QUEUE = 0
        const val MODE_LIBRARY = 1
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
