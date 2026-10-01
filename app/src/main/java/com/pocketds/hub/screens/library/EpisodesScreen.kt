package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeCardView
import com.pocketds.hub.ui.EpisodeLabel
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlaybackProgressStore
import com.pocketds.hub.screens.discover.ReleaseTargetsScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.state.PagedLoadState
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
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

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
        EpisodeLabel.season(season.seasonNumber)
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
                clipChildren = false
                setPadding(dp(16), dp(12), dp(16), dp(84))
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
        add(ButtonHint.refresh())
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
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = adapter.itemCount > 0), colors)
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
        status.showStatus(
            if (body.items.isEmpty() && adapter.itemCount == 0) StatusMessage("No episodes found.")
            else StatusText.loaded("${adapter.itemCount} of ${body.total} episodes", body.cache, body.partial.map { it.service }),
            colors
        )
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
            val card = EpisodeCardView(parent.context, colors, ringVisible).apply {
                layoutParams = RecyclerView.LayoutParams(dp(EpisodeCardView.WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
            }
            card.onFocused = {
                selected = list.getChildAdapterPosition(card)
                selectedItemId = (card.getTag(TAG_EPISODE) as? LibraryItem)?.id.orEmpty()
                host?.refreshHints()
            }
            card.onActivate = { (card.getTag(TAG_EPISODE) as? LibraryItem)?.let(::open) }
            return EpisodeHolder(card)
        }
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val value = values[position]
            val meta = buildList {
                if (value.runtimeSeconds > 0) add(Fmt.runtime(value.runtimeSeconds.toLong()))
                if (value.played) add("Watched")
                else if (value.progress > 0) add("${(value.progress * 100).toInt()}% watched")
            }.joinToString(" · ")
            holder.card.setTag(TAG_EPISODE, value)
            holder.card.bind(
                EpisodeCardView.Model(
                    title = value.subtitle.ifEmpty { EpisodeLabel.of(value.seasonNumber, value.indexNumber, value.title) },
                    meta = meta,
                    still = api.imageUrl(value.thumb.ifEmpty { value.poster }).takeIf(String::isNotEmpty),
                    progress = if (value.played) 0.0 else value.progress,
                    overview = value.overview,
                    description = "Episode ${value.indexNumber}, ${value.subtitle.ifEmpty { value.title }}, $meta"
                ),
                Artwork.loader(api, holder.card.context)
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
    private class EpisodeHolder(val card: EpisodeCardView) : RecyclerView.ViewHolder(card)
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
