package com.pocketds.hub.screens.library

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.PlaybackProgressStore
import com.pocketds.hub.playback.ResumeRules
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.PagedLoadState
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeCardView
import com.pocketds.hub.ui.EpisodeLabel
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.showStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One season's episodes as a row of stills, under a series' tabs.
 *
 * This was a screen of its own, pushed from a season poster. On the redesigned
 * detail page the seasons are a row of choices and the episodes sit right
 * under them, so this is the same paging, focus memory and return refresh as
 * a view the page owns. Pages already loaded are kept per season, so going
 * back to a season shows it at once.
 */
class SeasonEpisodesView(
    context: Context,
    private val api: HubApi,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val scope: CoroutineScope,
    /** Glass episodes ([EpisodeCardView]'s glass tile), at the prototype's 176dp. */
    private val glass: Boolean = false
) : LinearLayout(context) {
    var onPlay: ((LibraryItem) -> Unit)? = null
    var onFocusedEpisode: ((LibraryItem) -> Unit)? = null
    /** A season's episode count, once its first page says. */
    var onTotal: ((seasonId: String, total: Int) -> Unit)? = null

    private class SeasonState(val paging: PagedLoadState, val items: MutableList<LibraryItem> = mutableListOf()) {
        var selected = -1
    }

    private val seasons = HashMap<String, SeasonState>()
    private val status = TextView(context).apply {
        textSize = 11f
        setTextColor(colors.mutedText)
        setPadding(dp(24), 0, dp(24), 0)
        visibility = GONE
    }
    private val adapter = EpisodeAdapter()
    val list: RecyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        adapter = this@SeasonEpisodesView.adapter
        setItemViewCacheSize(10)
        isFocusable = false
        clipToPadding = false
        clipChildren = false
        if (glass) setPadding(dp(16), dp(6), dp(16), dp(10)) else setPadding(dp(18), dp(8), dp(18), dp(10))
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val state = current ?: return
                val manager = view.layoutManager as LinearLayoutManager
                state.paging.next(manager.findLastVisibleItemPosition(), state.items.size)?.let(::loadPage)
            }
        })
    }
    private var seriesId = ""
    private var season: LibraryItem? = null
    /** The episode Play would start, tagged UP NEXT and scrolled to on first show. */
    private var target = ""
    private var loadJob: Job? = null
    private val current: SeasonState? get() = season?.let { seasons[it.id] }

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(status)
        addView(list, LayoutParams(MATCH, WRAP))
    }

    fun show(seriesId: String, season: LibraryItem, targetEpisodeId: String) {
        val changed = this.season?.id != season.id
        this.seriesId = seriesId
        this.season = season
        target = targetEpisodeId
        val state = seasons.getOrPut(season.id) { SeasonState(PagedLoadState(PREFETCH_AHEAD)) }
        adapter.submit(state.items)
        if (changed) list.scrollToPosition(startPosition(state).coerceAtLeast(0))
        if (state.paging.loadedPage == 0 && loadJob?.isActive != true) (state.paging.retry() ?: state.paging.initial())?.let(::loadPage)
    }

    fun setTarget(episodeId: String) {
        if (target == episodeId) return
        target = episodeId
        adapter.notifyDataSetChanged()
    }

    val hasEpisodes: Boolean get() = adapter.itemCount > 0
    val focusedEpisode: LibraryItem? get() = list.focusedChild?.let(list::getChildAdapterPosition)?.let { current?.items?.getOrNull(it) }

    /** The remembered episode, or the one Play would start, or the first. */
    fun focusEpisode(): Boolean {
        val state = current ?: return false
        if (state.items.isEmpty()) return false
        val position = startPosition(state).coerceIn(0, state.items.size - 1)
        list.scrollToPosition(position)
        list.post { list.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
        return true
    }

    private fun startPosition(state: SeasonState): Int =
        state.selected.takeIf { it >= 0 } ?: state.items.indexOfFirst { it.id == target }.takeIf { it >= 0 } ?: 0

    /** After playback: the checkpoint at once, then the server's word on the episode just played. */
    fun refreshAfterPlayback() {
        val state = current ?: return
        val context = context
        state.items.forEachIndexed { index, episode ->
            val resolved = PlaybackProgressStore.applyTo(context, HubSettings.userId(context), episode)
            if (resolved != episode) { state.items[index] = resolved; adapter.notifyItemChanged(index) }
        }
        val episode = state.items.getOrNull(state.selected) ?: return
        scope.launch {
            val fresh = (api.libraryItem(episode.id) as? HubResult.Ok)?.value?.item ?: return@launch
            val index = state.items.indexOfFirst { it.id == fresh.id }.takeIf { it >= 0 } ?: return@launch
            state.items[index] = PlaybackProgressStore.applyTo(context, HubSettings.userId(context), fresh)
            if (current === state) adapter.notifyItemChanged(index)
        }
    }

    /** Pages loaded so far go; the season reloads from page one. */
    fun reload() {
        val state = current ?: return
        loadJob?.cancel()
        state.paging.reset()
        state.items.clear()
        adapter.submit(state.items)
        state.paging.initial()?.let(::loadPage)
    }

    fun cancel() {
        loadJob?.cancel()
        seasons.values.forEach { it.paging.cancelLoading() }
    }

    private fun loadPage(page: Int) {
        val season = season ?: return
        val state = current ?: return
        if (loadJob?.isActive == true) return
        if (state.items.isEmpty()) status.showStatus(StatusText.loading("episodes", refreshing = false), colors)
        status.visibility = if (state.items.isEmpty()) VISIBLE else GONE
        loadJob = scope.launch {
            when (val result = api.libraryEpisodes(seriesId, season.id, page)) {
                is HubResult.Ok -> render(season.id, state, result.value)
                is HubResult.Failed -> {
                    state.paging.fail(page)
                    status.visibility = VISIBLE
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = state.items.isNotEmpty()), colors)
                }
            }
        }
    }

    private fun render(seasonId: String, state: SeasonState, body: LibraryEpisodesResponse) {
        state.paging.complete(body.page, body.totalPages)
        if (body.page == 1) onTotal?.invoke(seasonId, body.total)
        val known = state.items.map { it.id }.toHashSet()
        val context = context
        val added = body.items.filter { known.add(it.id) }
            .map { PlaybackProgressStore.applyTo(context, HubSettings.userId(context), it) }
        val first = state.items.isEmpty()
        state.items.addAll(added)
        if (current !== state) return
        adapter.submit(state.items)
        status.visibility = if (state.items.isEmpty()) VISIBLE else GONE
        if (state.items.isEmpty()) status.showStatus(StatusText.notice("No episodes in this season yet."), colors)
        if (first) list.scrollToPosition(startPosition(state).coerceAtLeast(0))
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        private var values: List<LibraryItem> = emptyList()

        fun submit(next: MutableList<LibraryItem>) {
            values = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = values.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            val card = EpisodeCardView(parent.context, colors, ringVisible, compact = true, glass = glass).apply {
                showsPlayOnFocus = true
                layoutParams = RecyclerView.LayoutParams(dp(if (glass) EpisodeCardView.GLASS_STRIP_WIDTH_DP else EpisodeCardView.STRIP_WIDTH_DP), WRAP)
                    .apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            }
            val holder = EpisodeHolder(card)
            card.onFocused = {
                val position = holder.bindingAdapterPosition
                current?.selected = position
                values.getOrNull(position)?.let { onFocusedEpisode?.invoke(it) }
            }
            card.onActivate = { values.getOrNull(holder.bindingAdapterPosition)?.let { onPlay?.invoke(it) } }
            return holder
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val value = values[position]
            val watched = ResumeRules.showsWatched(value.played, value.progress)
            val meta = buildList {
                if (value.runtimeSeconds > 0) add(Fmt.runtime(value.runtimeSeconds.toLong()))
                ResumeRules.watchLabel(value.played, value.progress)?.let(::add)
            }.joinToString(" · ")
            val name = "${value.indexNumber}. ${value.title.ifBlank { EpisodeLabel.code(value.seasonNumber, value.indexNumber) }}"
            holder.card.bind(
                EpisodeCardView.Model(
                    title = name,
                    meta = meta,
                    still = api.imageUrl(value.thumb.ifEmpty { value.poster }).takeIf(String::isNotEmpty),
                    progress = if (watched) 0.0 else value.progress,
                    badge = if (value.id == target && !watched) "UP NEXT" else "",
                    watched = watched,
                    description = "Episode ${value.indexNumber}, ${value.title}, $meta"
                ),
                Artwork.loader(api, holder.card.context)
            )
        }
    }

    private class EpisodeHolder(val card: EpisodeCardView) : RecyclerView.ViewHolder(card)

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PREFETCH_AHEAD = 8
    }
}
