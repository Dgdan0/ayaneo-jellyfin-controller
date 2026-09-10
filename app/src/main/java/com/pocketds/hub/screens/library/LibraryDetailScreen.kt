package com.pocketds.hub.screens.library

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.LibraryStateRequest
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlaybackProgressStore
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.screens.discover.ReleaseTargetsScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.MediaActionIcon
import com.pocketds.hub.ui.MediaActionIconDrawable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Movie, series, season, or episode metadata sourced directly from Jellyfin. */
class LibraryDetailScreen(
    private val api: HubApi,
    private val itemId: String,
    private val fallbackTitle: String,
    private val expectedType: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = fallbackTitle
    override val focusOnShow = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var heading: TextView
    private lateinit var originalTitle: TextView
    private lateinit var meta: TextView
    private lateinit var progress: TextView
    private lateinit var overview: TextView
    private lateinit var credits: TextView
    private lateinit var mediaInfo: TextView
    private lateinit var status: TextView
    private lateinit var episodePreviewLabel: TextView
    private lateinit var episodePreview: LinearLayout
    private lateinit var episodePreviewImage: ImageView
    private lateinit var episodePreviewTitle: TextView
    private lateinit var episodePreviewMeta: TextView
    private lateinit var episodePreviewProgress: ProgressBar
    private lateinit var actions: LinearLayout
    private lateinit var playAction: TextView
    private lateinit var restartAction: TextView
    private lateinit var optionsAction: TextView
    private lateinit var watchedAction: TextView
    private lateinit var favoriteAction: TextView
    private lateinit var downloadAction: TextView
    private lateinit var seasonLabel: TextView
    private lateinit var seasons: RecyclerView
    private val seasonAdapter = SeasonAdapter()
    private var host: ScreenHost? = null
    private var item: LibraryItem? = null
    private var itemJob: Job? = null
    private var seasonsJob: Job? = null
    private var targetJob: Job? = null
    private var stateJob: Job? = null
    private var returnRefreshJob: Job? = null
    private var seriesTarget: SeriesPlayTargetResponse? = null
    private var selectedSeason = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return ScrollView(host.viewContext).apply {
            isFocusable = false
            isFillViewport = true
            setBackgroundColor(colors.background)
            clipChildren = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(90))
                backdrop = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    alpha = .35f
                    setBackgroundColor(colors.posterPlaceholder)
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(112))
                }
                addView(backdrop)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(12), 0, 0)
                    poster = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(colors.posterPlaceholder)
                        layoutParams = LinearLayout.LayoutParams(dp(96), dp(144))
                    }
                    addView(poster)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), 0, 0, 0)
                        heading = TextView(context).apply {
                            text = fallbackTitle
                            textSize = 25f
                            setTextColor(colors.primaryText)
                        }
                        addView(heading)
                        originalTitle = TextView(context).apply {
                            textSize = 12f
                            setTextColor(colors.mutedText)
                            setPadding(0, dp(2), 0, 0)
                            visibility = View.GONE
                        }
                        addView(originalTitle)
                        meta = TextView(context).apply {
                            textSize = 13f
                            setTextColor(colors.mutedText)
                            setPadding(0, dp(4), 0, 0)
                        }
                        addView(meta)
                        progress = TextView(context).apply {
                            textSize = 12f
                            setTextColor(colors.accent)
                            setPadding(0, dp(8), 0, 0)
                        }
                        addView(progress)
                        status = TextView(context).apply {
                            text = "Asking Jellyfin…"
                            textSize = 11f
                            setTextColor(colors.mutedText)
                            setPadding(0, dp(8), 0, 0)
                        }
                        addView(status)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                })
                actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.START
                    setPadding(dp(8), dp(12), dp(8), dp(4))
                    playAction = actionButton("Play", ACTION_PLAY)
                    addView(playAction)
                    restartAction = actionButton("Start over", ACTION_RESTART)
                    addView(restartAction)
                    optionsAction = actionButton("Playback options", ACTION_OPTIONS)
                    addView(optionsAction)
                    watchedAction = actionButton("Mark watched", ACTION_WATCHED)
                    addView(watchedAction)
                    favoriteAction = actionButton("Favourite", ACTION_FAVORITE)
                    addView(favoriteAction)
                    downloadAction = actionButton("Download", ACTION_DOWNLOAD)
                    addView(downloadAction)
                    visibility = View.GONE
                }
                addView(HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = false
                    addView(actions, ViewGroup.LayoutParams(WRAP, WRAP))
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                overview = TextView(context).apply {
                    textSize = 15f
                    setTextColor(colors.primaryText)
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, dp(14), 0, dp(8))
                }
                addView(overview)
                credits = TextView(context).apply {
                    textSize = 12f
                    setTextColor(colors.mutedText)
                    setLineSpacing(0f, 1.12f)
                    setPadding(0, dp(5), 0, dp(6))
                    visibility = View.GONE
                }
                addView(credits)
                mediaInfo = TextView(context).apply {
                    textSize = 12f
                    setTextColor(colors.primaryText)
                    setLineSpacing(0f, 1.16f)
                    setPadding(0, dp(9), 0, dp(8))
                    visibility = View.GONE
                }
                addView(mediaInfo)
                episodePreviewLabel = TextView(context).apply {
                    textSize = 17f
                    setTextColor(colors.primaryText)
                    setPadding(0, dp(10), 0, dp(5))
                    visibility = View.GONE
                }
                addView(episodePreviewLabel)
                episodePreview = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = Styler.cardBackground(context, colors, cornerDp = 12f)
                    Styler.makeFocusable(this)
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setPadding(dp(7), dp(7), dp(14), dp(7))
                    visibility = View.GONE
                    layoutParams = LinearLayout.LayoutParams(dp(390), dp(124))
                    episodePreviewImage = ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(colors.posterPlaceholder)
                    }
                    addView(episodePreviewImage, LinearLayout.LayoutParams(dp(184), dp(104)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), 0, 0, 0)
                        episodePreviewTitle = TextView(context).apply {
                            textSize = 15f
                            maxLines = 2
                            setTextColor(colors.primaryText)
                        }
                        addView(episodePreviewTitle)
                        episodePreviewMeta = TextView(context).apply {
                            textSize = 11f
                            maxLines = 2
                            setTextColor(colors.mutedText)
                            setPadding(0, dp(5), 0, 0)
                        }
                        addView(episodePreviewMeta)
                        episodePreviewProgress = ProgressBar(
                            context,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                        ).apply {
                            max = 1_000
                            visibility = View.GONE
                        }
                        addView(episodePreviewProgress, LinearLayout.LayoutParams(MATCH, dp(8)).apply {
                            topMargin = dp(7)
                        })
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    FocusDecorator.attach(this, ringVisible)
                    setOnFocusChangeListener { view, focused ->
                        FocusDecorator.refresh(view, ringVisible())
                        if (focused) host.refreshHints()
                    }
                    activateOnTap { openSeriesTargetDetails() }
                }
                addView(episodePreview)
                seasonLabel = TextView(context).apply {
                    text = "Seasons"
                    textSize = 17f
                    setTextColor(colors.primaryText)
                    setPadding(0, dp(10), 0, dp(5))
                    visibility = View.GONE
                }
                addView(seasonLabel)
                seasons = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                    adapter = seasonAdapter
                    clipChildren = false
                    clipToPadding = false
                    visibility = View.GONE
                    setItemViewCacheSize(8)
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(226))
                }
                addView(seasons)
            })
        }
    }

    override fun onShow() {
        val returning = item != null
        if (!returning && itemJob?.isActive != true) loadItem()
        if (expectedType == "series" && seasonAdapter.itemCount == 0 && seasonsJob?.isActive != true) {
            loadSeasons()
        }
        if (item?.type == "series" && seriesTarget == null && targetJob?.isActive != true) loadPlayTarget()
        if (returning) {
            applyPendingPlaybackProgress()
            returnRefreshJob?.cancel()
            returnRefreshJob = scope.launch {
                delay(RETURN_REFRESH_DELAY_MILLIS)
                loadItem()
                if (item?.type == "series" || expectedType == "series") {
                    loadSeasons()
                    loadPlayTarget()
                }
                returnRefreshJob = null
            }
        }
    }

    override fun onHide() {
        selectedSeason = focusedSeasonPosition().takeIf { it >= 0 } ?: selectedSeason
        scope.coroutineContext.cancelChildren()
        itemJob = null
        seasonsJob = null
        targetJob = null
        stateJob = null
        returnRefreshJob = null
    }

    override fun onDestroyView() { scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        if (::playAction.isInitialized && playAction.visibility == View.VISIBLE && playAction.isEnabled) {
            return playAction.requestFocus()
        }
        if (seasonAdapter.itemCount == 0) return false
        val target = selectedSeason.coerceIn(0, seasonAdapter.itemCount - 1)
        seasons.scrollToPosition(target)
        seasons.post { seasons.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints(): List<ButtonHint> = buildList {
        val value = item
        val focusedAction = listOf(playAction, restartAction, optionsAction, watchedAction, favoriteAction, downloadAction)
            .firstOrNull { it.visibility == View.VISIBLE && it.hasFocus() }
        if (focusedAction != null) {
            add(ButtonHint.activate(focusedAction.contentDescription.toString()))
        } else {
            when (value?.type) {
                "movie", "episode" -> add(ButtonHint.activate(if (canResume(value)) "Resume" else "Play"))
                "series" -> if (seriesTarget != null) {
                    add(ButtonHint.activate(playAction.contentDescription.toString()))
                }
            }
        }
        if (value?.type == "movie" || value?.type == "episode") {
            add(ButtonHint.primary("Playback options"))
            add(ButtonHint.secondary("Start over"))
        }
        if (::seasons.isInitialized && seasons.hasFocus() && focusedSeason() != null) {
            add(ButtonHint.activate("Episodes"))
            if (!value?.mediaKey.isNullOrEmpty()) add(ButtonHint.secondary("Find releases"))
        }
        add(ButtonHint.back())
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    override fun onPad(action: PadAction): Boolean = when (action) {
        is PadAction.Step -> when {
            !actions.hasFocus() -> false
            action.direction == Direction.LEFT -> { moveActionFocus(-1); true }
            action.direction == Direction.RIGHT -> { moveActionFocus(1); true }
            else -> false
        }
        PadAction.Activate -> when {
            playAction.hasFocus() -> { playAction.performClick(); true }
            restartAction.hasFocus() -> { restartAction.performClick(); true }
            optionsAction.hasFocus() -> { optionsAction.performClick(); true }
            watchedAction.hasFocus() -> { watchedAction.performClick(); true }
            favoriteAction.hasFocus() -> { favoriteAction.performClick(); true }
            downloadAction.hasFocus() -> { downloadAction.performClick(); true }
            episodePreview.hasFocus() -> { episodePreview.performClick(); true }
            else -> focusedSeason()?.let(::openSeason) != null
        }
        PadAction.Primary -> if (item?.type == "movie" || item?.type == "episode") {
            host?.openPlaybackOptions(itemId, if (canResume(item)) "resume" else "restart")
            true
        } else false
        PadAction.Secondary -> when {
            item?.type == "movie" || item?.type == "episode" -> {
                host?.playItem(itemId, "restart")
                true
            }
            item?.type == "series" && seasons.hasFocus() -> {
                focusedSeason()?.let(::openReleaseTargets) != null
            }
            else -> false
        }
        PadAction.Refresh -> {
            loadItem()
            if (item?.type == "series" || expectedType == "series") {
                loadSeasons()
                loadPlayTarget()
            }
            true
        }
        else -> false
    }

    private fun moveActionFocus(delta: Int) {
        val available = listOf(playAction, restartAction, optionsAction, watchedAction, favoriteAction, downloadAction)
            .filter { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
        val current = available.indexOfFirst { it.hasFocus() }
        if (current < 0) return
        available.getOrNull(current + delta)?.requestFocus()
    }

    private fun loadItem() {
        if (itemJob?.isActive == true) return
        status.setTextColor(colors.mutedText)
        status.text = if (item == null) "Asking Jellyfin…" else "Refreshing…"
        itemJob = scope.launch {
            when (val result = api.libraryItem(itemId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = if (item == null) result.message + " · Select retries"
                        else result.message + " · showing previous details"
                }
            }
            itemJob = null
        }
    }

    private fun render(body: LibraryItemResponse) {
        val context = requireNotNull(host).viewContext
        val value = PlaybackProgressStore.applyTo(
            context,
            HubSettings.userId(context),
            body.item
        )
        item = value
        heading.text = value.title.ifEmpty { fallbackTitle }
        originalTitle.text = value.originalTitle
        originalTitle.visibility = if (
            value.originalTitle.isNotBlank() && !value.originalTitle.equals(value.title, ignoreCase = true)
        ) View.VISIBLE else View.GONE
        meta.text = buildList {
            if (value.type.isNotEmpty()) add(value.type.replaceFirstChar { it.uppercase() })
            if (value.year > 0) add(value.year.toString())
            if (value.type == "episode" && value.premiereDate.length >= 10) add(value.premiereDate.take(10))
            if (value.runtimeSeconds > 0) add(runtime(value.runtimeSeconds))
            if (value.officialRating.isNotEmpty()) add(value.officialRating)
            if (value.rating > 0) add("★ %.1f".format(value.rating))
            if (value.criticRating > 0) add("Critics %.0f%%".format(value.criticRating))
            addAll(value.genres)
        }.joinToString(" · ")
        progress.text = buildList {
            when {
                value.played -> add("✓ Watched")
                value.progress > 0 -> add("${(value.progress * 100).toInt()}% watched")
                value.unplayedCount > 0 -> add("${value.unplayedCount} unwatched")
            }
            if (value.favorite) add("★ Favourite")
        }.joinToString(" · ")
        overview.text = value.overview.ifEmpty { "No description available." }
        credits.text = creditText(value)
        credits.visibility = if (credits.text.isNullOrBlank()) View.GONE else View.VISIBLE
        mediaInfo.text = mediaInfoText(value)
        mediaInfo.visibility = if (mediaInfo.text.isNullOrBlank()) View.GONE else View.VISIBLE
        status.setTextColor(if (body.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            body.partial.isNotEmpty() -> body.partial.joinToString(" · ") { it.message }
            body.cache.stale -> "Showing cached watch state"
            else -> ""
        }
        loadImage(backdrop, value.backdrop.ifEmpty { value.thumb })
        loadImage(poster, value.poster.ifEmpty { value.thumb })
        renderActions(value)
        if (value.type == "series" && seasonAdapter.itemCount == 0 && seasonsJob?.isActive != true) {
            loadSeasons()
        }
        if (value.type == "series" && seriesTarget == null && targetJob?.isActive != true) loadPlayTarget()
    }

    private fun renderActions(value: LibraryItem) {
        actions.visibility = if (value.type in setOf("movie", "episode", "series")) View.VISIBLE else View.GONE
        val playable = value.type == "movie" || value.type == "episode"
        restartAction.visibility = if (playable) View.VISIBLE else View.GONE
        optionsAction.visibility = if (playable) View.VISIBLE else View.GONE
        watchedAction.visibility = View.VISIBLE
        favoriteAction.visibility = View.VISIBLE
        downloadAction.visibility = View.VISIBLE
        val waitingForSeriesTarget = value.type == "series" && seriesTarget == null
        watchedAction.isFocusable = !waitingForSeriesTarget
        favoriteAction.isFocusable = !waitingForSeriesTarget
        watchedAction.text = ""
        watchedAction.contentDescription = if (value.played) "Mark unwatched" else "Mark watched"
        setActionIcon(
            watchedAction,
            if (value.played) MediaActionIcon.WATCHED else MediaActionIcon.UNWATCHED
        )
        favoriteAction.text = ""
        favoriteAction.contentDescription = if (value.favorite) {
            "Remove from favourites"
        } else {
            "Add to favourites"
        }
        setActionIcon(
            favoriteAction,
            if (value.favorite) MediaActionIcon.FAVOURITE else MediaActionIcon.NOT_FAVOURITE
        )
        val local = if (value.type == "movie" || value.type == "episode") {
            OfflineRepository.get(requireNotNull(host).viewContext).forItem(value.id)
        } else null
        val downloaded = local?.state == com.pocketds.hub.offline.OfflineState.COMPLETE
        downloadAction.text = ""
        downloadAction.contentDescription = when {
            downloaded -> "Downloaded"
            local != null -> "Download ${local.state.wire}, ${(local.progress * 100).toInt()} percent"
            else -> "Download"
        }
        setActionIcon(downloadAction, if (downloaded) MediaActionIcon.DOWNLOADED else MediaActionIcon.DOWNLOAD)
        playAction.visibility = View.VISIBLE
        playAction.isEnabled = playable || seriesTarget != null
        playAction.alpha = if (playAction.isEnabled) 1f else .55f
        val playLabel = when {
            playable && canResume(value) -> "Resume · ${playTime(value.positionSeconds.toLong() * 1_000)}"
            playable -> "Play"
            seriesTarget == null -> "Finding next episode…"
            else -> seriesActionLabel(requireNotNull(seriesTarget))
        }
        playAction.text = when {
            playable && canResume(value) -> playTime(value.positionSeconds.toLong() * 1_000)
            value.type == "series" && seriesTarget?.kind == "resume" -> {
                playTime(requireNotNull(seriesTarget).item.positionSeconds.toLong() * 1_000)
            }
            else -> ""
        }
        playAction.contentDescription = playLabel
        resizeActionForText(playAction)
        restartAction.text = ""
        restartAction.contentDescription = "Start over"
        optionsAction.text = ""
        optionsAction.contentDescription = "Playback options"
        if (value.type == "series") renderEpisodePreview(seriesTarget)
        host?.refreshHints()
    }

    private fun loadPlayTarget() {
        if (targetJob?.isActive == true) return
        targetJob = scope.launch {
            when (val result = api.seriesPlayTarget(itemId)) {
                is HubResult.Ok -> {
                    val alreadyFocusedContent = actions.hasFocus() || seasons.hasFocus() || episodePreview.hasFocus()
                    val resolved = withPendingPlaybackProgress(result.value)
                    seriesTarget = resolved
                    renderEpisodePreview(resolved)
                    item?.let(::renderActions)
                    if (!alreadyFocusedContent) playAction.post { playAction.requestFocus() }
                }
                is HubResult.Failed -> {
                    episodePreviewLabel.visibility = View.GONE
                    episodePreview.visibility = View.GONE
                    playAction.isEnabled = false
                    playAction.alpha = .55f
                    playAction.text = ""
                    playAction.contentDescription = "No playable episode"
                    resizeActionForText(playAction)
                }
            }
            targetJob = null
        }
    }

    private fun applyPendingPlaybackProgress() {
        val context = host?.viewContext ?: return
        item?.let { current ->
            val resolved = PlaybackProgressStore.applyTo(
                context,
                HubSettings.userId(context),
                current
            )
            if (resolved != current) render(LibraryItemResponse(item = resolved))
        }
        seriesTarget?.let { current ->
            val resolved = withPendingPlaybackProgress(current)
            if (resolved != current) {
                seriesTarget = resolved
                renderEpisodePreview(resolved)
                item?.let(::renderActions)
            }
        }
    }

    private fun withPendingPlaybackProgress(target: SeriesPlayTargetResponse): SeriesPlayTargetResponse {
        val context = host?.viewContext ?: return target
        val resolved = PlaybackProgressStore.applyTo(
            context,
            HubSettings.userId(context),
            target.item
        )
        return if (resolved == target.item) target else target.copy(kind = "resume", item = resolved)
    }

    private fun renderEpisodePreview(target: SeriesPlayTargetResponse?) {
        if (target == null) {
            episodePreviewLabel.visibility = View.GONE
            episodePreview.visibility = View.GONE
            return
        }
        val episode = target.item
        episodePreviewLabel.text = when (target.kind) {
            "resume" -> "Continue watching"
            "next" -> "Next episode"
            else -> "Start series"
        }
        episodePreviewLabel.visibility = View.VISIBLE
        episodePreview.visibility = View.VISIBLE
        episodePreviewTitle.text = episode.subtitle.ifEmpty { episode.title }
        episodePreviewMeta.text = buildList {
            if (episode.runtimeSeconds > 0) add(runtime(episode.runtimeSeconds))
            when {
                episode.played -> add("Watched")
                episode.progress > 0 -> add("${(episode.progress * 100).toInt()}% watched")
            }
        }.joinToString(" · ")
        episodePreviewProgress.progress = when {
            !episode.played && episode.progress > 0 -> (episode.progress * 1_000).toInt().coerceIn(0, 1_000)
            else -> 0
        }
        episodePreviewProgress.visibility = if (!episode.played && episode.progress > 0) View.VISIBLE else View.GONE
        loadImage(episodePreviewImage, episode.thumb.ifEmpty { episode.poster })
        episodePreview.contentDescription = "${episodePreviewLabel.text}, ${episodePreviewTitle.text}"
    }

    private fun performAction(action: String) {
        when (action) {
            ACTION_PLAY -> {
                val value = item ?: return
                if (value.type == "series") {
                    val target = seriesTarget ?: return
                    host?.playItem(target.item.id, if (target.kind == "resume") "resume" else "restart")
                } else {
                    host?.playItem(value.id, if (canResume(value)) "resume" else "restart")
                }
            }
            ACTION_RESTART -> host?.playItem(itemId, "restart")
            ACTION_OPTIONS -> host?.openPlaybackOptions(itemId, if (canResume(item)) "resume" else "restart")
            ACTION_WATCHED -> item?.let { updateState(played = !it.played) }
            ACTION_FAVORITE -> item?.let { updateState(favorite = !it.favorite) }
            ACTION_DOWNLOAD -> item?.let { value ->
                val existing = if (value.type in setOf("movie", "episode")) {
                    OfflineRepository.get(requireNotNull(host).viewContext).forItem(value.id)
                } else null
                if (existing != null) host?.notify("This item is ${existing.state.wire} in Offline")
                else host?.downloadItem(value)
            }
        }
    }

    private fun openSeriesTargetDetails() {
        val episode = seriesTarget?.item ?: return
        host?.push(LibraryDetailScreen(api, episode.id, episode.title, "episode", ringVisible))
    }

    private fun updateState(played: Boolean? = null, favorite: Boolean? = null) {
        if (stateJob?.isActive == true) return
        val previous = item ?: return
        val optimistic = previous.copy(
            played = played ?: previous.played,
            favorite = favorite ?: previous.favorite,
            progress = if (played == true) 0.0 else previous.progress,
            positionSeconds = if (played == true) 0 else previous.positionSeconds
        )
        render(LibraryItemResponse(item = optimistic))
        status.setTextColor(colors.mutedText)
        status.text = "Saving to Jellyfin…"
        stateJob = scope.launch {
            when (val result = api.updateLibraryState(
                itemId,
                LibraryStateRequest(played = played, favorite = favorite)
            )) {
                is HubResult.Ok -> {
                    render(result.value)
                    if (result.value.item.type == "series" && played != null) {
                        seriesTarget = null
                        loadPlayTarget()
                    }
                }
                is HubResult.Failed -> {
                    render(LibraryItemResponse(item = previous))
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host?.notify(result.message)
                }
            }
            stateJob = null
        }
    }

    private fun creditText(value: LibraryItem): String = buildList {
        if (value.studios.isNotEmpty()) add("Studios  ${value.studios.joinToString(", ")}")
        val directors = value.people.filter { it.type.equals("Director", true) }.map { it.name }.distinct()
        val writers = value.people.filter {
            it.type.equals("Writer", true) || it.type.equals("Screenwriter", true)
        }.map { it.name }.distinct()
        val cast = value.people.filter {
            it.type.equals("Actor", true) || it.type.equals("GuestStar", true)
        }.take(12).map { person ->
            if (person.role.isBlank()) person.name else "${person.name} (${person.role})"
        }
        if (directors.isNotEmpty()) add("Directed by  ${directors.joinToString(", ")}")
        if (writers.isNotEmpty()) add("Written by  ${writers.joinToString(", ")}")
        if (cast.isNotEmpty()) add("Cast  ${cast.joinToString(", ")}")
    }.joinToString("\n")

    private fun mediaInfoText(value: LibraryItem): String {
        if (value.mediaVersions.isEmpty()) return ""
        return buildString {
            append("Media information")
            value.mediaVersions.forEachIndexed { versionIndex, version ->
                append("\n")
                append(version.name.ifBlank { "Version ${versionIndex + 1}" })
                val facts = buildList {
                    if (version.container.isNotBlank()) add(version.container.uppercase())
                    if (version.sizeBytes > 0) add(fileSize(version.sizeBytes))
                    if (version.bitrate > 0) add("%.1f Mbps".format(version.bitrate / 1_000_000.0))
                }
                if (facts.isNotEmpty()) append(" · ").append(facts.joinToString(" · "))
                version.tracks.forEach { track ->
                    append("\n  ").append(track.type.replaceFirstChar { it.uppercase() })
                    val details = buildList {
                        if (track.title.isNotBlank()) add(track.title)
                        else {
                            if (track.language.isNotBlank()) add(track.language.uppercase())
                            if (track.codec.isNotBlank()) add(track.codec.uppercase())
                        }
                        if (track.width > 0 && track.height > 0) add("${track.width}×${track.height}")
                        if (track.channels > 0) add(if (track.channels == 6) "5.1" else "${track.channels} ch")
                        if (track.hdr.isNotBlank() && !track.hdr.equals("SDR", true)) add(track.hdr)
                        if (track.default) add("Default")
                        if (track.forced) add("Forced")
                    }
                    if (details.isNotEmpty()) append(" · ").append(details.distinct().joinToString(" · "))
                }
            }
        }
    }

    private fun fileSize(bytes: Long): String {
        val gib = bytes / 1_073_741_824.0
        return if (gib >= 1.0) "%.1f GB".format(gib) else "%.0f MB".format(bytes / 1_048_576.0)
    }

    private fun actionButton(label: String, action: String) = TextView(requireNotNull(host).viewContext).apply {
        text = ""
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(colors.primaryText)
        background = Styler.cardBackground(context, colors)
        Styler.makeFocusable(this)
        contentDescription = label
        setPadding(dp(12), dp(10), dp(12), dp(10))
        compoundDrawablePadding = 0
        setActionIcon(this, iconForAction(action))
        layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(9) }
        FocusDecorator.attach(this, ringVisible, scale = false)
        setOnFocusChangeListener { view, _ ->
            FocusDecorator.refresh(view, ringVisible())
            host?.refreshHints()
        }
        activateOnTap { performAction(action) }
    }

    private fun resizeActionForText(view: TextView) {
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        params.width = if (view.text.isNullOrEmpty()) dp(50) else dp(92)
        view.layoutParams = params
    }

    private fun iconForAction(action: String): MediaActionIcon = when (action) {
        ACTION_RESTART -> MediaActionIcon.START_OVER
        ACTION_OPTIONS -> MediaActionIcon.OPTIONS
        ACTION_WATCHED -> MediaActionIcon.UNWATCHED
        ACTION_FAVORITE -> MediaActionIcon.NOT_FAVOURITE
        ACTION_DOWNLOAD -> MediaActionIcon.DOWNLOAD
        else -> MediaActionIcon.PLAY
    }

    private fun setActionIcon(view: TextView, icon: MediaActionIcon) {
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            MediaActionIconDrawable(view.context, icon, colors.primaryText),
            null,
            null,
            null
        )
    }

    private fun seriesActionLabel(target: SeriesPlayTargetResponse): String {
        val episode = target.item
        val code = if (episode.seasonNumber > 0 && episode.indexNumber > 0) {
            " S${episode.seasonNumber}E${episode.indexNumber}"
        } else ""
        return when (target.kind) {
            "resume" -> "Resume$code"
            "next" -> "Play next episode$code"
            else -> "Start series$code"
        }
    }

    private fun canResume(value: LibraryItem?): Boolean = value != null &&
        value.positionSeconds >= 30 && value.runtimeSeconds - value.positionSeconds > 30 && !value.played

    private fun playTime(milliseconds: Long): String {
        val total = milliseconds / 1_000
        return if (total >= 3_600) "%d:%02d:%02d".format(total / 3_600, total % 3_600 / 60, total % 60)
            else "%d:%02d".format(total / 60, total % 60)
    }

    private fun loadSeasons() {
        if (seasonsJob?.isActive == true) return
        seasonsJob = scope.launch {
            when (val result = api.librarySeasons(itemId)) {
                is HubResult.Ok -> renderSeasons(result.value)
                is HubResult.Failed -> {
                    seasonLabel.visibility = View.VISIBLE
                    seasonLabel.text = "Seasons · ${result.message} · Select retries"
                    seasonLabel.setTextColor(colors.dangerText)
                }
            }
            seasonsJob = null
        }
    }

    private fun renderSeasons(body: LibrarySeasonsResponse) {
        seasonAdapter.submit(body.items)
        seasonLabel.visibility = View.VISIBLE
        seasons.visibility = if (body.items.isEmpty()) View.GONE else View.VISIBLE
        seasonLabel.setTextColor(colors.primaryText)
        seasonLabel.text = if (body.items.isEmpty()) "No seasons found" else "Seasons"
        host?.refreshHints()
    }

    private fun focusedSeasonPosition(): Int {
        if (!::seasons.isInitialized) return -1
        val focused = seasons.focusedChild ?: return selectedSeason.takeIf { seasonAdapter.itemCount > 0 } ?: -1
        return seasons.getChildAdapterPosition(focused).takeIf { it >= 0 }
            ?: selectedSeason.takeIf { seasonAdapter.itemCount > 0 } ?: -1
    }
    private fun focusedSeason() = seasonAdapter.at(focusedSeasonPosition())
    private fun openSeason(season: LibraryItem) {
        selectedSeason = focusedSeasonPosition().coerceAtLeast(0)
        host?.push(
            EpisodesScreen(
                api, itemId, item?.mediaKey.orEmpty(), item?.title ?: fallbackTitle,
                season, ringVisible
            )
        )
    }

    private fun openReleaseTargets(season: LibraryItem) {
        val value = item ?: return
        if (value.mediaKey.isEmpty()) {
            host?.notify("This series has no TMDB match, so Sonarr releases cannot be linked safely")
            return
        }
        selectedSeason = focusedSeasonPosition().coerceAtLeast(0)
        host?.push(
            ReleaseTargetsScreen(
                api, value.mediaKey, value.title, season.seasonNumber,
                season.poster.ifEmpty { season.thumb }, 0, ringVisible
            )
        )
    }

    private fun loadImage(view: ImageView, path: String) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        if (path.isEmpty()) return
        imageLoader().enqueue(
            ImageRequest.Builder(view.context).data(api.imageUrl(path)).target(view)
                .bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun imageLoader(): ImageLoader =
        (api as? HubClient)?.imageLoader ?: ImageLoader(requireNotNull(host).viewContext)

    private inner class SeasonAdapter : RecyclerView.Adapter<SeasonHolder>() {
        private val values = mutableListOf<LibraryItem>()
        fun submit(next: List<LibraryItem>) { values.clear(); values.addAll(next); notifyDataSetChanged() }
        fun at(position: Int) = values.getOrNull(position)
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonHolder {
            lateinit var image: ImageView
            lateinit var label: TextView
            lateinit var detail: TextView
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(6), dp(6), dp(6), dp(8))
                layoutParams = RecyclerView.LayoutParams(dp(132), dp(216)).apply {
                    marginEnd = dp(10)
                    topMargin = dp(4)
                }
                image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(colors.posterPlaceholder)
                }
                addView(image, LinearLayout.LayoutParams(MATCH, dp(168)))
                label = TextView(context).apply {
                    textSize = 14f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(colors.primaryText)
                    setPadding(dp(4), dp(6), dp(4), 0)
                }
                addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
                detail = TextView(context).apply {
                    textSize = 10f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(colors.mutedText)
                }
                addView(detail, LinearLayout.LayoutParams(MATCH, WRAP))
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selectedSeason = seasons.getChildAdapterPosition(this)
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_SEASON) as? LibraryItem)?.let(::openSeason) }
            }
            return SeasonHolder(card, image, label, detail)
        }
        override fun onBindViewHolder(holder: SeasonHolder, position: Int) {
            val value = values[position]
            holder.title.text = value.title.ifEmpty {
                if (value.seasonNumber == 0) "Specials" else "Season ${value.seasonNumber}"
            }
            holder.meta.text = when {
                value.played -> "Watched"
                value.unplayedCount > 0 -> "${value.unplayedCount} unwatched"
                value.progress > 0 -> "${(value.progress * 100).toInt()}% watched"
                else -> ""
            }
            holder.itemView.setTag(TAG_SEASON, value)
            loadImage(holder.image, value.poster.ifEmpty { value.thumb })
        }
    }

    private class SeasonHolder(
        view: View,
        val image: ImageView,
        val title: TextView,
        val meta: TextView
    ) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())
    private fun runtime(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes < 60) "${minutes} min" else "${minutes / 60}h ${minutes % 60}m"
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TAG_SEASON = -0x7fffffe3
        const val ACTION_PLAY = "play"
        const val ACTION_RESTART = "restart"
        const val ACTION_OPTIONS = "options"
        const val ACTION_WATCHED = "watched"
        const val ACTION_FAVORITE = "favorite"
        const val ACTION_DOWNLOAD = "download"
        const val RETURN_REFRESH_DELAY_MILLIS = 450L
    }
}

/** A season's episodes, retaining loaded pages and focus while it is on its stack. */
class EpisodesScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val seriesMediaKey: String,
    private val seriesTitle: String,
    private val season: LibraryItem,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = season.title.ifEmpty {
        if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paging = PagedLoadState(PREFETCH_AHEAD)
    private val adapter = EpisodeAdapter()
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var returnRefreshJob: Job? = null
    private var selected = 0
    private var selectedItemId = ""
    private var refreshing = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            status = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(14), dp(7), dp(14), dp(5))
            }
            addView(status)
            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = this@EpisodesScreen.adapter
                setItemViewCacheSize(12)
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                clipToPadding = false
                setPadding(dp(12), dp(8), dp(12), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                        val manager = view.layoutManager as LinearLayoutManager
                        paging.next(
                            manager.findLastVisibleItemPosition(),
                            this@EpisodesScreen.adapter.itemCount
                        )?.let(::loadPage)
                    }
                })
            }
            addView(list)
        }
    }

    override fun onShow() {
        if (paging.loadedPage == 0 && loadJob?.isActive != true) {
            (paging.retry() ?: paging.initial())?.let(::loadPage)
        } else {
            applyPendingEpisodeProgress()
            restoreFocus()
            refreshSelectedEpisode()
        }
    }

    override fun onHide() {
        selected = focusedPosition().takeIf { it >= 0 } ?: selected
        selectedItemId = focusedEpisode()?.id ?: selectedItemId
        paging.cancelLoading()
        scope.coroutineContext.cancelChildren()
        loadJob = null
        returnRefreshJob = null
    }

    override fun onDestroyView() { scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        if (!::list.isInitialized || adapter.itemCount == 0) return false
        val target = selected.coerceIn(0, adapter.itemCount - 1)
        list.scrollToPosition(target)
        list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints() = buildList {
        add(ButtonHint.activate("Details"))
        add(ButtonHint.primary("Download season"))
        if (seriesMediaKey.isNotEmpty()) add(ButtonHint.secondary("Find releases"))
        add(ButtonHint.back())
        add(ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh))
    }

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> focusedEpisode()?.let(::open) != null
        PadAction.Secondary -> if (seriesMediaKey.isNotEmpty()) {
            host?.push(
                ReleaseTargetsScreen(
                    api, seriesMediaKey, seriesTitle, season.seasonNumber,
                    season.poster.ifEmpty { season.thumb }, focusedEpisode()?.indexNumber ?: 0,
                    ringVisible
                )
            )
            true
        } else false
        PadAction.Primary -> {
            host?.downloadItem(
                LibraryItem(id = seriesId, type = "series", title = seriesTitle),
                season.id
            )
            true
        }
        PadAction.Refresh -> {
            if (loadJob?.isActive != true) {
                val retry = paging.retry()
                if (retry != null) loadPage(retry) else reload()
            }
            true
        }
        else -> false
    }

    private fun reload() {
        paging.reset()
        selectedItemId = focusedEpisode()?.id ?: selectedItemId
        refreshing = true
        paging.initial()?.let(::loadPage)
    }

    private fun loadPage(page: Int) {
        if (loadJob?.isActive == true) return
        status.setTextColor(colors.mutedText)
        status.text = when {
            refreshing -> "Refreshing episodes…"
            adapter.itemCount == 0 -> "Loading episodes…"
            else -> "Loading more…"
        }
        loadJob = scope.launch {
            when (val result = api.libraryEpisodes(seriesId, season.id, page)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    paging.fail(page)
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + if (adapter.itemCount == 0) " · Select retries"
                        else " · showing previous episodes · Select retries"
                }
            }
            loadJob = null
        }
    }

    private fun render(body: LibraryEpisodesResponse) {
        paging.complete(body.page, body.totalPages)
        if (body.page == 1 && refreshing) {
            adapter.replace(body.items)
            selected = adapter.indexOf(selectedItemId).takeIf { it >= 0 } ?: 0
            refreshing = false
        } else adapter.append(body.items)
        status.setTextColor(if (body.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            body.items.isEmpty() && adapter.itemCount == 0 -> "No episodes found."
            body.cache.stale -> "${adapter.itemCount} of ${body.total} · cached"
            else -> "${adapter.itemCount} of ${body.total} episodes"
        }
        if (body.page == 1) restoreFocus()
        host?.refreshHints()
    }

    private fun restoreFocus() { if (adapter.itemCount > 0) requestInitialFocus() }
    private fun focusedPosition(): Int {
        val focused = list.focusedChild ?: return selected.takeIf { adapter.itemCount > 0 } ?: -1
        return list.getChildAdapterPosition(focused).takeIf { it >= 0 }
            ?: selected.takeIf { adapter.itemCount > 0 } ?: -1
    }
    private fun focusedEpisode() = adapter.at(focusedPosition())

    private fun applyPendingEpisodeProgress() {
        val context = host?.viewContext ?: return
        val episode = adapter.at(adapter.indexOf(selectedItemId)) ?: focusedEpisode() ?: return
        val resolved = PlaybackProgressStore.applyTo(context, HubSettings.userId(context), episode)
        if (resolved != episode) adapter.update(resolved)
    }

    private fun refreshSelectedEpisode() {
        val episodeId = selectedItemId.ifEmpty { focusedEpisode()?.id.orEmpty() }
        if (episodeId.isEmpty()) return
        returnRefreshJob?.cancel()
        returnRefreshJob = scope.launch {
            delay(RETURN_REFRESH_DELAY_MILLIS)
            when (val result = api.libraryItem(episodeId)) {
                is HubResult.Ok -> {
                    val context = host?.viewContext ?: return@launch
                    adapter.update(
                        PlaybackProgressStore.applyTo(
                            context,
                            HubSettings.userId(context),
                            result.value.item
                        )
                    )
                    host?.refreshHints()
                }
                is HubResult.Failed -> Unit
            }
            returnRefreshJob = null
        }
    }

    private fun open(episode: LibraryItem) {
        selected = focusedPosition().coerceAtLeast(0)
        host?.push(LibraryDetailScreen(api, episode.id, episode.title, "episode", ringVisible))
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        private val values = mutableListOf<LibraryItem>()
        fun at(position: Int) = values.getOrNull(position)
        fun indexOf(itemId: String) = values.indexOfFirst { it.id == itemId }
        fun update(value: LibraryItem) {
            val index = indexOf(value.id)
            if (index < 0) return
            values[index] = value
            notifyItemChanged(index)
        }
        fun replace(next: List<LibraryItem>) {
            values.clear()
            values.addAll(next.distinctBy { it.id })
            notifyDataSetChanged()
        }
        fun append(next: List<LibraryItem>) {
            val known = values.asSequence().map { it.id }.toHashSet()
            val added = next.filter { known.add(it.id) }
            val start = values.size
            values.addAll(added)
            if (added.isNotEmpty()) notifyItemRangeInserted(start, added.size)
        }
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            lateinit var image: ImageView
            lateinit var title: TextView
            lateinit var meta: TextView
            lateinit var overview: TextView
            lateinit var progress: ProgressBar
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(7), dp(7), dp(7), dp(10))
                layoutParams = RecyclerView.LayoutParams(dp(270), dp(242)).apply {
                    setMargins(dp(4), dp(4), dp(7), dp(4))
                }
                image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(colors.posterPlaceholder)
                }
                addView(image, LinearLayout.LayoutParams(MATCH, dp(144)))
                title = TextView(context).apply {
                    textSize = 15f
                    setTextColor(colors.primaryText)
                    maxLines = 1
                    setPadding(dp(4), dp(7), dp(4), 0)
                }
                addView(title, LinearLayout.LayoutParams(MATCH, WRAP))
                meta = TextView(context).apply {
                    textSize = 11f
                    setTextColor(colors.mutedText)
                    maxLines = 1
                    setPadding(dp(4), dp(3), dp(4), 0)
                }
                addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
                progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1_000
                    visibility = View.GONE
                }
                addView(progress, LinearLayout.LayoutParams(MATCH, dp(8)).apply {
                    setMargins(dp(4), dp(5), dp(4), 0)
                })
                overview = TextView(context).apply {
                    textSize = 10f
                    setTextColor(colors.mutedText)
                    maxLines = 2
                    setPadding(dp(4), dp(3), dp(4), 0)
                }
                addView(overview, LinearLayout.LayoutParams(MATCH, 0, 1f))
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selected = list.getChildAdapterPosition(this)
                        selectedItemId = (getTag(TAG_EPISODE) as? LibraryItem)?.id.orEmpty()
                        host?.refreshHints()
                    }
                }
                activateOnTap { (getTag(TAG_EPISODE) as? LibraryItem)?.let(::open) }
            }
            return EpisodeHolder(row, image, title, meta, overview, progress)
        }
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val value = values[position]
            holder.title.text = value.subtitle.ifEmpty { value.title }
            holder.meta.text = buildString {
                if (value.runtimeSeconds > 0) append(value.runtimeSeconds / 60).append(" min")
                if (value.played) append(if (isEmpty()) "Watched" else " · Watched")
                else if (value.progress > 0) append(if (isEmpty()) "" else " · ")
                    .append((value.progress * 100).toInt()).append("% watched")
            }
            holder.overview.text = value.overview
            holder.progress.progress = when {
                !value.played && value.progress > 0 -> (value.progress * 1_000).toInt().coerceIn(0, 1_000)
                else -> 0
            }
            holder.progress.visibility = if (!value.played && value.progress > 0) View.VISIBLE else View.GONE
            holder.itemView.setTag(TAG_EPISODE, value)
            holder.itemView.contentDescription = "Episode ${value.indexNumber}, ${value.subtitle.ifEmpty { value.title }}, ${holder.meta.text}"
            holder.image.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
            val path = value.thumb.ifEmpty { value.poster }
            if (path.isNotEmpty()) imageLoader().enqueue(
                ImageRequest.Builder(holder.image.context).data(api.imageUrl(path)).target(holder.image)
                    .bitmapConfig(Bitmap.Config.RGB_565).build()
            )
        }
    }

    private fun <T : View> findTagged(root: ViewGroup, tag: Int): T =
        findTaggedOrNull(root, tag) ?: error("missing tagged child")

    @Suppress("UNCHECKED_CAST")
    private fun <T : View> findTaggedOrNull(root: ViewGroup, tag: Int): T? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.getTag(tag) == true) return child as T
            if (child is ViewGroup) {
                val nested = findTaggedOrNull<T>(child, tag)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun imageLoader(): ImageLoader =
        (api as? HubClient)?.imageLoader ?: ImageLoader(requireNotNull(host).viewContext)
    private class EpisodeHolder(
        view: View,
        val image: ImageView,
        val title: TextView,
        val meta: TextView,
        val overview: TextView,
        val progress: ProgressBar
    ) : RecyclerView.ViewHolder(view)
    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PREFETCH_AHEAD = 8
        const val TAG_EPISODE = -0x7fffffe4
        const val TAG_IMAGE = -0x7fffffe5
        const val TAG_TITLE = -0x7fffffe6
        const val TAG_META = -0x7fffffe7
        const val RETURN_REFRESH_DELAY_MILLIS = 450L
    }
}
