package com.pocketds.hub.screens.offline

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.offline.OfflineBatch
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineCatalogEntry
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineState
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

/** Persistent transfer manager and the device's offline library. */
class OfflineScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Offline"
    override val focusOnShow = true

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var queueTab: TextView
    private lateinit var libraryTab: TextView
    private lateinit var summary: TextView
    private lateinit var content: LinearLayout
    private lateinit var overlay: ChoiceOverlay
    private var mode = MODE_LIBRARY
    private var selectedId = ""
    private var renderedSignature = ""
    private var receiverRegistered = false
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "Offline"; textSize = 23f; setTextColor(colors.primaryText)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                libraryTab = tab("Downloaded", MODE_LIBRARY)
                queueTab = tab("Download manager", MODE_QUEUE)
                addView(libraryTab); addView(queueTab)
            })
            summary = TextView(context).apply {
                textSize = 11f; setTextColor(colors.mutedText); setPadding(0, dp(6), 0, dp(5))
            }
            addView(summary)
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(context).apply {
                isFocusable = false; clipToPadding = false; setPadding(0, 0, 0, dp(80)); addView(content)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render(force = true)
        return root
    }

    override fun onShow() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                host.viewContext,
                changedReceiver,
                IntentFilter(OfflineRepository.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        render(force = true)
        if (repository.batches().any { batch ->
                !batch.paused && batch.jobs.any { it.state in setOf(OfflineState.QUEUED, OfflineState.WAITING) }
            } || repository.outbox().isNotEmpty()
        ) OfflineDownloadService.start(host.viewContext)
    }
    override fun onHide() {
        if (receiverRegistered) {
            host.viewContext.unregisterReceiver(changedReceiver)
            receiverRegistered = false
        }
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() {
        if (receiverRegistered) {
            host.viewContext.unregisterReceiver(changedReceiver)
            receiverRegistered = false
        }
    }

    override fun requestInitialFocus(): Boolean {
        val selected = findTagged(content, selectedId)
        return selected?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true || libraryTab.requestFocus()
    }

    override fun hints(): List<ButtonHint> {
        if (overlay.isOpen) return listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        val row = focusedRow()
        return buildList {
            when (row) {
                is TaggedCatalog -> {
                    add(ButtonHint.activate(if (row.value.isSeries) "Open" else "Play"))
                    add(ButtonHint.primary("Remove download"))
                }
                is TaggedDownload -> {
                    if (row.value.state == OfflineState.COMPLETE) add(ButtonHint.activate("Play"))
                    else add(ButtonHint.activate("Manage"))
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
        if (action is PadAction.Step && (queueTab.hasFocus() || libraryTab.hasFocus())) {
            if (action.direction == Direction.LEFT) libraryTab.requestFocus()
            if (action.direction == Direction.RIGHT) queueTab.requestFocus()
            return action.direction == Direction.LEFT || action.direction == Direction.RIGHT
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
            PadAction.Secondary -> if (row is TaggedDownload &&
                row.value.state in setOf(OfflineState.FAILED, OfflineState.WAITING)
            ) {
                OfflineDownloadService.retry(host.viewContext, row.value.id); true
            } else false
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
        val completed = repository.completed()
        val signature = if (mode == MODE_QUEUE) {
            batches.flatMap { batch -> batch.jobs.map { row ->
                "${batch.id}:${batch.paused}:${row.id}:${row.state}:${row.bytesDownloaded}:${row.speedBytesPerSecond}:${row.error}"
            } }.joinToString("|")
        } else completed.joinToString("|") { "${it.id}:${it.updatedAt}:${it.localPath}" }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        val hadFocus = host.viewContext.let { (it as? android.app.Activity)?.currentFocus }
        val focusedTag = hadFocus?.tag
        if (focusedTag is TaggedDownload) selectedId = focusedTag.value.id
        if (focusedTag is TaggedBatch) selectedId = focusedTag.value.id
        if (focusedTag is TaggedCatalog) selectedId = focusedTag.value.key
        content.removeAllViews()
        queueTab.background = tabBackground(mode == MODE_QUEUE)
        libraryTab.background = tabBackground(mode == MODE_LIBRARY)
        if (mode == MODE_QUEUE) renderQueue(batches) else renderLibrary(completed, batches)
        if (hadFocus != null &&
            (focusedTag is TaggedDownload || focusedTag is TaggedBatch || focusedTag is TaggedCatalog)
        ) {
            content.post { findTagged(content, selectedId)?.requestFocus() }
        }
    }

    private fun renderQueue(allBatches: List<OfflineBatch>) {
        val batches = allBatches.filter { it.jobs.any { job -> job.state != OfflineState.COMPLETE } }
        val queued = batches.sumOf { it.jobs.count { job -> job.state != OfflineState.COMPLETE } }
        summary.text = "$queued pending · ${fileSize(repository.availableBytes())} free · downloads run one at a time"
        if (batches.isEmpty()) {
            empty("No downloads are waiting. Use the download icon on a movie, episode, season, or series.")
            return
        }
        batches.forEach { batch ->
            content.addView(batchHeader(batch))
            batch.jobs.filter { it.state != OfflineState.COMPLETE }.forEach { content.addView(downloadRow(it)) }
        }
    }

    private fun renderLibrary(completed: List<OfflineDownload>, batches: List<OfflineBatch>) {
        val batchTitles = batches.associate { it.id to it.title }
        val groups = OfflineCatalog.titles(completed, batchTitles)
        val complete = groups.sumOf { it.rows.size }
        val size = groups.sumOf { entry -> entry.rows.sumOf { it.totalBytes } }
        summary.text = "${groups.size} title${if (groups.size == 1) "" else "s"} · $complete file${if (complete == 1) "" else "s"} · ${fileSize(size)}"
        if (groups.isEmpty()) { empty("Downloaded movies and series will appear here and remain playable without a network."); return }
        val grid = GridLayout(host.viewContext).apply {
            columnCount = 5
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(0, dp(4), 0, dp(22))
        }
        groups.forEach { value ->
            grid.addView(catalogCard(value), GridLayout.LayoutParams().apply {
                width = 0
                height = WRAP
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(5), dp(5), dp(5), dp(7))
            })
        }
        content.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
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
                "${value.rows.size} episode${if (value.rows.size == 1) "" else "s"} · ${fileSize(size)}"
            } else fileSize(size),
            jellyfinItemId = value.key
        )
        return PosterCardView(host.viewContext, colors, 158f).apply {
            tag = TaggedCatalog(value)
            contentDescription = if (value.isSeries) {
                "${value.title}, ${value.rows.size} downloaded episodes"
            } else "${value.title}, downloaded movie"
            val loader = (api as? HubClient)?.imageLoader ?: ImageLoader(context)
            bind(hit, loader, { it }, showAvailability = false)
            FocusDecorator.attach(this, ringVisible)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) {
                    selectedId = value.key
                    host.refreshHints()
                }
            }
            activateOnTap { openCatalog(value) }
        }
    }

    private fun openCatalog(value: OfflineCatalogEntry) {
        if (value.isSeries) {
            host.push(OfflineSeriesScreen(api, value.key, value.title, ringVisible))
        } else {
            value.rows.firstOrNull()?.let { host.playItem(it.manifest.item.id) }
        }
    }

    private fun confirmRemoveCatalog(value: OfflineCatalogEntry) {
        val noun = if (value.isSeries) "${value.rows.size} downloaded episodes" else "the downloaded movie"
        overlay.show(
            "Remove ${value.title}?",
            "$noun will be deleted from this device.",
            listOf(
                ChoiceOverlay.Choice("keep", "Keep download"),
                ChoiceOverlay.Choice("remove", "Remove", danger = true)
            ),
            onCancel = host::refreshHints
        ) {
            if (it == "remove") value.rows.forEach { row ->
                OfflineDownloadService.remove(host.viewContext, row.id)
            }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun batchHeader(batch: OfflineBatch): View = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = Styler.cardBackground(context, colors, cornerDp = 11f)
        setPadding(dp(12), dp(8), dp(12), dp(8)); tag = TaggedBatch(batch)
        Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = batch.title; textSize = 15f; setTextColor(colors.primaryText) })
            addView(TextView(context).apply {
                val active = batch.jobs.firstOrNull { it.state == OfflineState.DOWNLOADING }
                val transfer = active?.speedBytesPerSecond?.takeIf { it > 0 }?.let { speed ->
                    val remaining = batch.totalBytes - batch.downloadedBytes
                    " · ${fileSize(speed)}/s · ${duration(remaining / speed)} left"
                }.orEmpty()
                text = "${batch.completeCount}/${batch.jobs.size} complete · ${fileSize(batch.downloadedBytes)} / ${fileSize(batch.totalBytes)}$transfer"
                textSize = 10f; setTextColor(colors.mutedText)
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = if (batch.paused) "▶" else "Ⅱ"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(colors.accent)
        }, LinearLayout.LayoutParams(dp(44), dp(40)))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(7); bottomMargin = dp(3) }
        decorate(this)
        activateOnTap {
            if (batch.paused) OfflineDownloadService.resumeBatch(context, batch.id)
            else OfflineDownloadService.pauseBatch(context, batch.id)
        }
    }

    private fun downloadRow(row: OfflineDownload): View {
        lateinit var image: ImageView
        val view = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(10), dp(6)); tag = TaggedDownload(row)
            background = Styler.cardBackground(context, colors, cornerDp = 10f)
            Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(dp(112), dp(63)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0)
                addView(TextView(context).apply {
                    text = episodeTitle(row); textSize = 14f; maxLines = 1; setTextColor(colors.primaryText)
                })
                addView(TextView(context).apply {
                    text = stateText(row); textSize = 10f
                    setTextColor(if (row.state == OfflineState.FAILED) colors.dangerText else colors.mutedText)
                })
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1_000; progress = (row.progress * 1_000).toInt()
                    visibility = if (row.state == OfflineState.COMPLETE) View.GONE else View.VISIBLE
                }, LinearLayout.LayoutParams(MATCH, dp(7)).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = if (row.state == OfflineState.COMPLETE) "✓" else "${(row.progress * 100).toInt()}%"
                textSize = 13f; gravity = Gravity.CENTER; setTextColor(colors.accent)
            }, LinearLayout.LayoutParams(dp(58), MATCH))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(78)).apply { bottomMargin = dp(5); marginStart = dp(10) }
            decorate(this)
            activateOnTap {
                if (row.state == OfflineState.COMPLETE) host.playItem(row.manifest.item.id) else showDownloadDetails(row)
            }
        }
        loadArtwork(image, row)
        return view
    }

    private fun sectionLabel(title: String, detail: String) = LinearLayout(host.viewContext).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        addView(TextView(context).apply { text = title; textSize = 17f; setTextColor(colors.primaryText) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = detail; textSize = 10f; setTextColor(colors.mutedText) })
        setPadding(dp(2), dp(10), dp(2), dp(4))
    }

    private fun empty(message: String) {
        content.addView(TextView(host.viewContext).apply {
            text = message; textSize = 14f; gravity = Gravity.CENTER; setTextColor(colors.mutedText)
            setPadding(dp(40), dp(70), dp(40), dp(40))
        })
    }

    private fun tab(label: String, target: Int) = TextView(host.viewContext).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(colors.primaryText)
        setPadding(dp(14), dp(7), dp(14), dp(7)); Styler.makeFocusable(this)
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(42)).apply { marginStart = dp(7) }
        FocusDecorator.attach(this, ringVisible, scale = false)
        setOnFocusChangeListener { view, _ -> FocusDecorator.refresh(view, ringVisible()); host.refreshHints() }
        activateOnTap {
            mode = target
            selectedId = ""
            renderedSignature = ""
            render(force = true)
            requestInitialFocus()
        }
    }

    private fun decorate(view: View) {
        FocusDecorator.attach(view, ringVisible, scale = false)
        view.setOnFocusChangeListener { focused, hasFocus ->
            FocusDecorator.refresh(focused, ringVisible())
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
            "${stateText(row)}\n${fileSize(row.bytesDownloaded)} of ${fileSize(row.totalBytes)}\n${row.error}",
            buildList {
                if (row.state == OfflineState.PAUSED) add(ChoiceOverlay.Choice("resume", "Resume download"))
                else if (row.state in setOf(OfflineState.QUEUED, OfflineState.DOWNLOADING, OfflineState.WAITING)) {
                    add(ChoiceOverlay.Choice("pause", "Pause download"))
                }
                if (row.state == OfflineState.FAILED || row.state == OfflineState.WAITING) add(ChoiceOverlay.Choice("retry", "Retry now"))
                add(ChoiceOverlay.Choice("close", "Close"))
            }, onCancel = host::refreshHints
        ) {
            when (it) {
                "pause" -> OfflineDownloadService.pauseItem(host.viewContext, row.id)
                "resume" -> OfflineDownloadService.resumeItem(host.viewContext, row.id)
                "retry" -> OfflineDownloadService.retry(host.viewContext, row.id)
            }
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun confirmRemove(row: OfflineDownload) {
        overlay.show("Remove ${row.manifest.item.title}?", "The local file and downloaded subtitles will be deleted.",
            listOf(ChoiceOverlay.Choice("keep", "Keep download"), ChoiceOverlay.Choice("remove", "Remove", danger = true)),
            onCancel = host::refreshHints
        ) { if (it == "remove") OfflineDownloadService.remove(host.viewContext, row.id); host.refreshHints() }
        host.refreshHints()
    }

    private fun confirmRemoveBatch(batch: OfflineBatch) {
        overlay.show("Cancel ${batch.title}?", "Choose whether completed episodes remain in Downloaded.",
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

    private fun stateText(row: OfflineDownload): String = buildList {
        add(row.state.wire.replaceFirstChar { it.uppercase() })
        if (row.state != OfflineState.COMPLETE) add("${fileSize(row.bytesDownloaded)} / ${fileSize(row.totalBytes)}")
        if (row.state == OfflineState.DOWNLOADING && row.speedBytesPerSecond > 0) {
            add("${fileSize(row.speedBytesPerSecond)}/s")
            add("${duration((row.totalBytes - row.bytesDownloaded).coerceAtLeast(0) / row.speedBytesPerSecond)} left")
        }
        if (row.error.isNotBlank()) add(row.error)
    }.joinToString(" · ")

    private fun episodeTitle(row: OfflineDownload): String {
        val item = row.manifest.item
        return if (item.type == "episode" && item.seasonNumber > 0 && item.indexNumber > 0) {
            "S${item.seasonNumber}E${item.indexNumber} · ${item.title}"
        } else item.title
    }

    private fun loadImage(view: ImageView, path: String) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder)); if (path.isEmpty()) return
        ((api as? HubClient)?.imageLoader ?: ImageLoader(host.viewContext)).enqueue(
            ImageRequest.Builder(view.context).data(api.imageUrl(path)).target(view)
                .bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }
    private fun loadArtwork(view: ImageView, row: OfflineDownload) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val local = sequenceOf("thumb", "poster", "backdrop")
            .map { repository.artworkFile(row, it) }.firstOrNull { it.isFile && it.length() > 0 }
        if (local != null) {
            ((api as? HubClient)?.imageLoader ?: ImageLoader(host.viewContext)).enqueue(
                ImageRequest.Builder(view.context).data(local).target(view)
                    .bitmapConfig(Bitmap.Config.RGB_565).build()
            )
        } else loadImage(view, row.manifest.item.thumb.ifEmpty { row.manifest.item.poster })
    }
    private fun tabBackground(selected: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = Styler.dp(host.viewContext, 10f)
        setColor(if (selected) this@OfflineScreen.colors.focusFill else this@OfflineScreen.colors.cardSurface)
        if (selected) setStroke(dp(1), this@OfflineScreen.colors.accent)
    }
    private fun fileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
    private fun duration(seconds: Long): String = when {
        seconds >= 3_600 -> "%dh %02dm".format(seconds / 3_600, seconds % 3_600 / 60)
        seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private data class TaggedDownload(val value: OfflineDownload)
    private data class TaggedBatch(val value: OfflineBatch)
    private data class TaggedCatalog(val value: OfflineCatalogEntry)
    private companion object {
        const val MODE_QUEUE = 0
        const val MODE_LIBRARY = 1
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
