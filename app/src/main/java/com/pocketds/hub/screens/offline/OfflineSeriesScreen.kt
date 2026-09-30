package com.pocketds.hub.screens.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineCatalogProgress
import com.pocketds.hub.offline.OfflineCatalogSeason
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.offline.OfflineSeriesPresentation
import com.pocketds.hub.offline.OfflineSeriesSuggestion
import com.pocketds.hub.offline.OfflineSeriesViewState
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

/**
 * Offline follows the same detail-page hierarchy as Library: series details,
 * a concrete Continue/Next card, then a horizontal season strip. The only
 * difference is honest availability: seasons and episodes are local only.
 */
class OfflineSeriesScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val seriesTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = seriesTitle
    override val focusOnShow = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private var remoteDetail: LibraryItem? = null
    private var serverTarget: SeriesPlayTargetResponse? = null
    private var presentation = OfflineSeriesViewState(emptyList(), null)
    private var selectedKey = ""
    private var renderedSignature = ""
    private var receiverRegistered = false
    private var detailJob: Job? = null
    private var targetJob: Job? = null
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            scroll = ScrollView(context).apply {
                isFocusable = false
                isFillViewport = true
                clipChildren = false
                content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    setPadding(dp(16), dp(12), dp(16), dp(90))
                }
                addView(content)
            }
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        }.also { render(force = true) }
    }

    override fun onShow() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(host.viewContext, changedReceiver,
                IntentFilter(OfflineRepository.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        render(force = true)
        refreshServerDetails()
    }

    override fun onHide() {
        unregister()
        scope.coroutineContext.cancelChildren()
        detailJob = null
        targetJob = null
    }

    override fun onDestroyView() {
        unregister()
        scope.cancel()
    }

    override fun requestInitialFocus(): Boolean =
        findTagged(content, selectedKey)?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true

    override fun hints(): List<ButtonHint> = buildList {
        when ((host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedSuggestion -> add(ButtonHint.activate(suggestionActionLabel()))
            is TaggedSeason -> add(ButtonHint.activate("Episodes"))
        }
        add(ButtonHint.back())
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    override fun onPad(action: PadAction): Boolean {
        if (action == PadAction.Refresh) {
            refreshServerDetails(force = true)
            render(force = true)
            return true
        }
        return when (val tag = (host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedSuggestion -> if (action == PadAction.Activate) {
                play(tag.suggestion); true
            } else false
            is TaggedSeason -> if (action == PadAction.Activate) {
                host.push(OfflineSeasonScreen(api, seriesTitle, tag.season, ringVisible)); true
            } else false
            else -> false
        }
    }

    private fun refreshServerDetails(force: Boolean = false) {
        if (detailJob?.isActive != true || force) {
            detailJob?.cancel()
            detailJob = scope.launch {
                when (val result = api.libraryItem(seriesId)) {
                    is HubResult.Ok -> {
                        remoteDetail = result.value.item.takeIf { it.id.isNotEmpty() }
                        remoteDetail?.let(repository::rememberSeries)
                        render(force = true)
                    }
                    is HubResult.Failed -> Unit // Local fallback remains fully usable.
                }
                detailJob = null
            }
        }
        if (targetJob?.isActive != true || force) {
            targetJob?.cancel()
            targetJob = scope.launch {
                when (val result = api.seriesPlayTarget(seriesId)) {
                    is HubResult.Ok -> {
                        serverTarget = result.value.takeIf { it.item.id.isNotEmpty() }
                        render(force = true)
                    }
                    is HubResult.Failed -> Unit // Keep a previous good target, else local fallback.
                }
                targetJob = null
            }
        }
    }

    private fun render(force: Boolean = false) {
        if (!::content.isInitialized) return
        val rows = repository.completed().filter { it.manifest.item.seriesId == seriesId }
        val detail = OfflineSeriesPresentation.detail(
            remoteDetail,
            repository.seriesMetadata(seriesId),
            rows.firstOrNull()?.manifest?.series
        )
        val progress = repository.playbackProgress(rows.map { it.manifest.item.id })
        presentation = OfflineSeriesPresentation.resolve(rows, progress, serverTarget)
        val signature = buildString {
            rows.forEach { row ->
                val state = progress[row.manifest.item.id]
                append(row.id).append(':').append(row.updatedAt).append(':')
                    .append(state?.positionMillis).append(':').append(state?.durationMillis).append('|')
            }
            append(detail?.id).append(':').append(detail?.overview).append(':').append(detail?.poster).append('|')
            append(serverTarget?.kind).append(':').append(serverTarget?.item?.id)
        }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedKey)?.key?.let { selectedKey = it }
        val previousScrollY = scroll.scrollY
        content.removeAllViews()
        if (rows.isEmpty()) {
            content.addView(emptyMessage("No episodes from this series remain on the device."))
            return
        }
        renderHeader(rows, detail)
        renderActionRow()
        renderOverview(rows.size, detail)
        renderSuggestion(progress)
        renderSeasons(presentation.seasons)
        content.post {
            if (findTagged(content, selectedKey)?.requestFocus() != true) {
                scroll.scrollTo(0, previousScrollY.coerceAtMost((content.height - scroll.height).coerceAtLeast(0)))
            }
        }
    }

    private fun renderHeader(rows: List<OfflineDownload>, detail: LibraryItem?) {
        val local = rows.first()
        val backdrop = ImageView(host.viewContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = .35f
            setBackgroundColor(colors.posterPlaceholder)
        }
        content.addView(backdrop, LinearLayout.LayoutParams(MATCH, dp(112)))
        val poster = ImageView(host.viewContext).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
        }
        content.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(poster, LinearLayout.LayoutParams(dp(96), dp(144)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(context).apply {
                    text = detail?.title?.ifBlank { seriesTitle } ?: seriesTitle
                    textSize = 25f
                    maxLines = 2
                    setTextColor(colors.primaryText)
                })
                addView(TextView(context).apply {
                    text = buildList {
                        add("Downloaded series")
                        detail?.year?.takeIf { it > 0 }?.let { add(it.toString()) }
                        detail?.genres?.take(2)?.let { addAll(it) }
                    }.joinToString(" · ")
                    textSize = 13f
                    maxLines = 2
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(context).apply {
                    text = "${rows.size} downloaded episode${if (rows.size == 1) "" else "s"} · ${fileSize(rows.sumOf { it.totalBytes })}"
                    textSize = 12f
                    maxLines = 2
                    setTextColor(colors.accent)
                    setPadding(0, dp(8), 0, 0)
                })
                addView(TextView(context).apply {
                    text = if (serverTarget == null) "Available offline" else "Jellyfin progress checked"
                    textSize = 11f
                    maxLines = 1
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(8), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        loadHeaderArtwork(backdrop, local, "backdrop", detail?.backdrop)
        loadHeaderArtwork(poster, local, "poster", detail?.poster)
    }

    private fun renderActionRow() {
        val available = presentation.suggestion as? OfflineSeriesSuggestion.Available
        if (available == null) return
        val action = TextView(host.viewContext).apply {
            text = "▶"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(colors.primaryText)
            background = Styler.cardBackground(context, colors, cornerDp = 11f)
            tag = TaggedSuggestion(available)
            Styler.makeFocusable(this)
            contentDescription = suggestionActionLabel()
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { play(available) }
        }
        content.addView(HorizontalScrollView(host.viewContext).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(8), dp(12), dp(8), dp(4))
            addView(LinearLayout(context).apply { addView(action, LinearLayout.LayoutParams(dp(54), dp(44))) })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun renderOverview(downloadedEpisodes: Int, detail: LibraryItem?) {
        content.addView(TextView(host.viewContext).apply {
            text = detail?.overview?.takeIf { it.isNotBlank() }
                ?: "Only the $downloadedEpisodes episode${if (downloadedEpisodes == 1) "" else "s"} stored on this Pocket DS are shown below."
            textSize = 15f
            setLineSpacing(0f, 1.12f)
            setTextColor(colors.primaryText)
            setPadding(0, dp(14), 0, dp(8))
        })
    }

    private fun renderSuggestion(progress: Map<String, OfflineCatalogProgress>) {
        val suggestion = presentation.suggestion ?: return
        val available = suggestion as? OfflineSeriesSuggestion.Available
        val item = when (suggestion) {
            is OfflineSeriesSuggestion.Available -> serverTarget?.item?.takeIf { it.id == suggestion.download.manifest.item.id }
                ?: suggestion.download.manifest.item
            is OfflineSeriesSuggestion.NotDownloaded -> suggestion.item
        }
        val label = when (suggestion.kind) {
            "resume" -> "Continue watching"
            "next" -> "Next episode"
            else -> "Start series"
        }
        content.addView(TextView(host.viewContext).apply {
            text = label
            textSize = 17f
            setTextColor(colors.primaryText)
            setPadding(0, dp(10), 0, dp(5))
        })
        val card = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 12f)
            setPadding(dp(7), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(390), dp(124))
            if (available != null) {
                tag = TaggedSuggestion(available)
                Styler.makeFocusable(this)
                contentDescription = suggestionActionLabel()
                FocusDecorator.attach(this, ringVisible, scale = false)
                setOnFocusChangeListener { view, hasFocus ->
                    FocusDecorator.refresh(view, ringVisible())
                    if (hasFocus) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
                }
                activateOnTap { play(available) }
            } else {
                alpha = .72f
                contentDescription = (suggestion as OfflineSeriesSuggestion.NotDownloaded).message
            }
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(colors.posterPlaceholder)
            }
            addView(image, LinearLayout.LayoutParams(dp(184), dp(104)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(context).apply {
                    text = item.subtitle.ifEmpty { item.title }
                    textSize = 15f
                    maxLines = 2
                    setTextColor(colors.primaryText)
                })
                addView(TextView(context).apply {
                    text = suggestionMeta(suggestion, item)
                    textSize = 11f
                    maxLines = 2
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(5), 0, 0)
                })
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1_000
                    visibility = View.GONE
                }
                addView(bar, LinearLayout.LayoutParams(MATCH, dp(8)).apply { topMargin = dp(7) })
                val localProgress = available?.let { progress[it.download.manifest.item.id]?.resumePosition() ?: it.positionMillis } ?: 0L
                val duration = available?.download?.manifest?.item?.runtimeSeconds?.times(1_000L)?.takeIf { it > 0 }
                    ?: item.runtimeSeconds * 1_000L
                if (available != null && localProgress > 0 && duration > 0) {
                    bar.visibility = View.VISIBLE
                    bar.progress = (localProgress * 1_000L / duration).toInt().coerceIn(0, 1_000)
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            loadSuggestionArtwork(image, available?.download, item)
        }
        content.addView(card)
    }

    private fun renderSeasons(seasons: List<OfflineCatalogSeason>) {
        content.addView(TextView(host.viewContext).apply {
            text = "Downloaded seasons"
            textSize = 17f
            setTextColor(colors.primaryText)
            setPadding(0, dp(10), 0, dp(5))
        })
        val seasonRow = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(16), dp(12))
            seasons.forEach { addView(seasonCard(it)) }
        }
        content.addView(HorizontalScrollView(host.viewContext).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(seasonRow)
        }, LinearLayout.LayoutParams(MATCH, dp(230)))
    }

    private fun seasonCard(season: OfflineCatalogSeason): View {
        val first = season.rows.first()
        lateinit var image: ImageView
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 10f)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            tag = TaggedSeason(season)
            Styler.makeFocusable(this)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(MATCH, dp(152)))
            addView(TextView(context).apply {
                text = seasonName(season.number)
                textSize = 13f
                maxLines = 1
                setTextColor(colors.primaryText)
                setPadding(dp(2), dp(5), dp(2), 0)
            })
            addView(TextView(context).apply {
                text = "${season.rows.size} downloaded episode${if (season.rows.size == 1) "" else "s"}"
                textSize = 9f
                maxLines = 1
                setTextColor(colors.mutedText)
                setPadding(dp(2), 0, dp(2), 0)
            })
            layoutParams = LinearLayout.LayoutParams(dp(166), dp(214)).apply { marginEnd = dp(9) }
            contentDescription = "${seasonName(season.number)}, ${season.rows.size} downloaded episodes"
            FocusDecorator.attach(this, ringVisible)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { host.push(OfflineSeasonScreen(api, seriesTitle, season, ringVisible)) }
            loadLocalArtwork(image, first, "poster")
        }
    }

    private fun play(suggestion: OfflineSeriesSuggestion.Available) {
        host.playItem(suggestion.download.manifest.item.id, if (suggestion.kind == "resume") "resume" else "restart")
    }

    private fun suggestionActionLabel() = when (presentation.suggestion?.kind) {
        "resume" -> "Resume"
        "next" -> "Play next episode"
        else -> "Start series"
    }

    private fun suggestionMeta(suggestion: OfflineSeriesSuggestion, item: LibraryItem): String = buildList {
        if (item.runtimeSeconds > 0) add(runtime(item.runtimeSeconds))
        when (suggestion) {
            is OfflineSeriesSuggestion.Available -> if (suggestion.kind == "resume" && suggestion.positionMillis > 0) {
                add("Resume · ${time(suggestion.positionMillis)}")
            } else add("Downloaded")
            is OfflineSeriesSuggestion.NotDownloaded -> add("Not downloaded · choose a downloaded season below")
        }
    }.joinToString(" · ")

    private fun loadHeaderArtwork(view: ImageView, local: OfflineDownload, kind: String, remote: String?) {
        val file = repository.artworkFile(local, kind).takeIf { it.isFile && it.length() > 0 }
        if (file != null) {
            enqueueImage(view, file)
        } else if (!remote.isNullOrBlank()) {
            enqueueImage(view, api.imageUrl(remote))
        } else view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
    }

    private fun loadSuggestionArtwork(view: ImageView, local: OfflineDownload?, item: LibraryItem) {
        val file = local?.let { download ->
            sequenceOf("thumb", "poster", "backdrop").map { repository.artworkFile(download, it) }
                .firstOrNull { it.isFile && it.length() > 0 }
        }
        if (file != null) enqueueImage(view, file)
        else {
            val path = item.thumb.ifEmpty { item.poster }
            if (path.isNotEmpty()) enqueueImage(view, api.imageUrl(path))
            else view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        }
    }

    private fun loadLocalArtwork(view: ImageView, row: OfflineDownload, kind: String) {
        val file = sequenceOf(kind, "thumb", "backdrop").map { repository.artworkFile(row, it) }
            .firstOrNull { it.isFile && it.length() > 0 }
        if (file != null) enqueueImage(view, file) else view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
    }

    private fun enqueueImage(view: ImageView, source: Any) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        ((api as? HubClient)?.imageLoader ?: ImageLoader(view.context)).enqueue(
            ImageRequest.Builder(view.context).data(source).target(view).bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun emptyMessage(message: String) = TextView(host.viewContext).apply {
        text = message
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(colors.mutedText)
        setPadding(dp(30), dp(80), dp(30), dp(30))
    }

    private fun unregister() {
        if (receiverRegistered) {
            host.viewContext.unregisterReceiver(changedReceiver)
            receiverRegistered = false
        }
    }

    private fun findTagged(root: ViewGroup, key: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if ((child.tag as? TaggedKey)?.key == key) return child
            if (child is ViewGroup) findTagged(child, key)?.let { return it }
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

    private fun seasonName(number: Int) = if (number == 0) "Specials" else "Season $number"
    private fun runtime(seconds: Int) = if (seconds >= 3_600) "%dh %02dm".format(seconds / 3_600, seconds % 3_600 / 60) else "%dm".format(seconds / 60)
    private fun time(millis: Long) = "%d:%02d".format(millis / 60_000L, millis / 1_000L % 60L)
    private fun fileSize(bytes: Long) = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private sealed interface TaggedKey { val key: String }
    private data class TaggedSuggestion(val suggestion: OfflineSeriesSuggestion.Available) : TaggedKey { override val key = "suggestion" }
    private data class TaggedSeason(val season: OfflineCatalogSeason) : TaggedKey { override val key = "season:${season.key}" }
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
