package com.pocketds.hub.screens.discover

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReleaseEpisodeTarget
import com.pocketds.hub.model.ReleaseTargetsResponse
import com.pocketds.hub.model.SeasonOption
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
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

/** Portrait season chooser shown before selecting a Sonarr search scope. */
class SeasonReleasePickerScreen(
    private val api: HubApi,
    private val mediaKey: String,
    private val mediaTitle: String,
    private val seasons: List<SeasonOption>,
    private val fallbackPoster: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Find releases"

    private lateinit var list: RecyclerView
    private lateinit var colors: PocketColors
    private var host: ScreenHost? = null
    private var selected = seasons.indexOfFirst { it.number > 0 }.coerceAtLeast(0)
    private val adapter = SeasonTargetAdapter()

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            addView(TextView(context).apply {
                text = "Choose a season"
                textSize = 22f
                setTextColor(colors.primaryText)
                setPadding(dp(16), dp(13), dp(16), 0)
            })
            addView(TextView(context).apply {
                text = "$mediaTitle · then choose the whole season or one aired episode"
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(dp(16), dp(3), dp(16), dp(7))
            })
            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = this@SeasonReleasePickerScreen.adapter
                clipToPadding = false
                setPadding(dp(12), dp(7), dp(12), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            addView(list)
        }
    }

    override fun onShow() { requestInitialFocus() }
    override fun onHide() {
        val child = if (::list.isInitialized) list.focusedChild else null
        if (child != null) selected = list.getChildAdapterPosition(child).coerceAtLeast(0)
    }
    override fun onDestroyView() { host = null }

    override fun requestInitialFocus(): Boolean {
        if (!::list.isInitialized || adapter.itemCount == 0) return false
        val target = selected.coerceIn(0, adapter.itemCount - 1)
        list.scrollToPosition(target)
        list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
        return true
    }

    override fun hints() = listOf(ButtonHint.activate("Choose season"), ButtonHint.back())

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> focusedSeason()?.let(::open) != null
        else -> false
    }

    private fun focusedSeason(): SeasonOption? {
        val child = list.focusedChild ?: return seasons.getOrNull(selected)
        return seasons.getOrNull(list.getChildAdapterPosition(child))
    }

    private fun open(season: SeasonOption) {
        selected = seasons.indexOf(season).coerceAtLeast(0)
        host?.push(
            ReleaseTargetsScreen(
                api, mediaKey, mediaTitle, season.number,
                season.image.ifEmpty { fallbackPoster }, 0, ringVisible
            )
        )
    }

    private inner class SeasonTargetAdapter : RecyclerView.Adapter<SeasonHolder>() {
        override fun getItemCount() = seasons.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SeasonHolder {
            lateinit var art: ImageView
            lateinit var label: TextView
            lateinit var meta: TextView
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(7), dp(7), dp(7), dp(10))
                layoutParams = RecyclerView.LayoutParams(dp(172), dp(282)).apply {
                    setMargins(dp(4), dp(4), dp(8), dp(4))
                }
                art = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(colors.posterPlaceholder)
                }
                addView(art, LinearLayout.LayoutParams(MATCH, dp(224)))
                label = TextView(context).apply {
                    textSize = 15f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(colors.primaryText)
                    setPadding(dp(3), dp(7), dp(3), 0)
                }
                addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
                meta = TextView(context).apply {
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(colors.mutedText)
                }
                addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selected = list.getChildAdapterPosition(this)
                        host?.refreshHints()
                    }
                }
                activateOnTap { focusedSeason()?.let(::open) }
            }
            return SeasonHolder(card, art, label, meta)
        }

        override fun onBindViewHolder(holder: SeasonHolder, position: Int) {
            val value = seasons[position]
            holder.title.text = value.name.ifEmpty {
                if (value.number == 0) "Specials" else "Season ${value.number}"
            }
            holder.meta.text = buildList {
                add("${value.episodeCount} episodes")
                if (value.year > 0) add(value.year.toString())
            }.joinToString(" · ")
            loadImage(holder.art, value.image.ifEmpty { fallbackPoster })
            holder.itemView.activateOnTap { open(value) }
        }
    }

    private data class SeasonHolder(
        val root: View,
        val art: ImageView,
        val title: TextView,
        val meta: TextView
    ) : RecyclerView.ViewHolder(root)

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

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Chooses between one season-wide search and each episode that has aired. */
class ReleaseTargetsScreen(
    private val api: HubApi,
    private val mediaKey: String,
    private val mediaTitle: String,
    private val seasonNumber: Int,
    private val fallbackSeasonImage: String,
    private val initialEpisode: Int,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Find releases"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var seasonAction: LinearLayout
    private lateinit var seasonArt: ImageView
    private lateinit var seasonTitle: TextView
    private lateinit var list: RecyclerView
    private val adapter = EpisodeTargetAdapter()
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var body: ReleaseTargetsResponse? = null
    private var selectedEpisode = initialEpisode

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            addView(TextView(context).apply {
                text = mediaTitle
                textSize = 22f
                maxLines = 1
                setTextColor(colors.primaryText)
                setPadding(dp(16), dp(12), dp(16), 0)
            })
            status = TextView(context).apply {
                text = "Loading released episodes…"
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(dp(16), dp(3), dp(16), dp(7))
            }
            addView(status)
            seasonAction = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(7), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(dp(390), dp(130)).apply {
                    marginStart = dp(16)
                    bottomMargin = dp(9)
                }
                seasonArt = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(colors.posterPlaceholder)
                }
                addView(seasonArt, LinearLayout.LayoutParams(dp(76), dp(114)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), 0, 0, 0)
                    seasonTitle = TextView(context).apply {
                        text = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
                        textSize = 17f
                        setTextColor(colors.primaryText)
                    }
                    addView(seasonTitle)
                    addView(TextView(context).apply {
                        text = "Search the entire season"
                        textSize = 12f
                        setTextColor(colors.mutedText)
                        setPadding(0, dp(5), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { view, _ ->
                    FocusDecorator.refresh(view, ringVisible())
                    host.refreshHints()
                }
                activateOnTap { openSeason() }
            }
            addView(seasonAction)
            loadImage(seasonArt, fallbackSeasonImage)
            addView(TextView(context).apply {
                text = "Released episodes"
                textSize = 17f
                setTextColor(colors.primaryText)
                setPadding(dp(16), dp(2), dp(16), dp(4))
            })
            list = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = this@ReleaseTargetsScreen.adapter
                clipToPadding = false
                setPadding(dp(12), dp(3), dp(12), dp(84))
                layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            }
            addView(list)
        }
    }

    override fun onShow() {
        if (body == null && loadJob?.isActive != true) load() else restoreFocus()
    }

    override fun onHide() {
        focusedEpisode()?.let { selectedEpisode = it.episode }
        scope.coroutineContext.cancelChildren()
        loadJob = null
    }

    override fun onDestroyView() { scope.cancel(); host = null }

    override fun requestInitialFocus(): Boolean {
        restoreFocus()
        return true
    }

    override fun hints() = listOf(
        ButtonHint.activate(if (focusedEpisode() == null) "Search season" else "Search episode"),
        ButtonHint.back(),
        ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
    )

    override fun onPad(action: PadAction): Boolean = when (action) {
        PadAction.Activate -> {
            val episode = focusedEpisode()
            if (episode != null) openEpisode(episode) else openSeason()
            true
        }
        PadAction.Refresh -> { load(force = true); true }
        else -> false
    }

    private fun load(force: Boolean = false) {
        if (loadJob?.isActive == true) return
        if (force) body = null
        status.setTextColor(colors.mutedText)
        status.text = "Loading released episodes…"
        loadJob = scope.launch {
            when (val result = api.releaseTargets(mediaKey, seasonNumber)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Select retries"
                }
            }
            loadJob = null
        }
    }

    private fun render(next: ReleaseTargetsResponse) {
        body = next
        seasonTitle.text = next.seasonTitle.ifEmpty {
            if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
        }
        loadImage(seasonArt, next.seasonImage.ifEmpty { fallbackSeasonImage })
        adapter.submit(next.episodes)
        status.setTextColor(if (next.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            next.episodes.isEmpty() -> "No episodes have aired yet · the season search is still available"
            next.partial.isNotEmpty() -> "${next.episodes.size} aired · artwork partially unavailable"
            else -> "${next.episodes.size} aired episode${if (next.episodes.size == 1) "" else "s"}"
        }
        restoreFocus()
        host?.refreshHints()
    }

    private fun restoreFocus() {
        if (!::seasonAction.isInitialized) return
        if (selectedEpisode <= 0 || adapter.itemCount == 0) {
            seasonAction.post { seasonAction.requestFocus() }
            return
        }
        val index = adapter.indexOf(selectedEpisode).takeIf { it >= 0 } ?: 0
        list.scrollToPosition(index)
        list.post { list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun focusedEpisode(): ReleaseEpisodeTarget? {
        if (!::list.isInitialized || !list.hasFocus()) return null
        val child = list.focusedChild ?: return null
        return adapter.at(list.getChildAdapterPosition(child))
    }

    private fun openSeason() {
        val next = body
        host?.push(
            ReleasesScreen(
                api, mediaKey, "$mediaTitle · ${next?.seasonTitle ?: seasonTitle.text}",
                seasonNumber, 0, ringVisible
            )
        )
    }

    private fun openEpisode(episode: ReleaseEpisodeTarget) {
        selectedEpisode = episode.episode
        host?.push(
            ReleasesScreen(
                api, mediaKey,
                "$mediaTitle · S${episode.season.toString().padStart(2, '0')}E${episode.episode.toString().padStart(2, '0')} · ${episode.title}",
                episode.season, episode.episode, ringVisible
            )
        )
    }

    private inner class EpisodeTargetAdapter : RecyclerView.Adapter<EpisodeHolder>() {
        private val values = mutableListOf<ReleaseEpisodeTarget>()
        fun at(position: Int) = values.getOrNull(position)
        fun indexOf(episode: Int) = values.indexOfFirst { it.episode == episode }
        fun submit(next: List<ReleaseEpisodeTarget>) {
            values.clear(); values.addAll(next); notifyDataSetChanged()
        }
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            lateinit var art: ImageView
            lateinit var label: TextView
            lateinit var meta: TextView
            lateinit var overview: TextView
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setPadding(dp(7), dp(7), dp(7), dp(9))
                layoutParams = RecyclerView.LayoutParams(dp(270), dp(238)).apply {
                    setMargins(dp(4), dp(4), dp(7), dp(4))
                }
                art = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(colors.posterPlaceholder)
                }
                addView(art, LinearLayout.LayoutParams(MATCH, dp(140)))
                label = TextView(context).apply {
                    textSize = 15f
                    maxLines = 1
                    setTextColor(colors.primaryText)
                    setPadding(dp(4), dp(7), dp(4), 0)
                }
                addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
                meta = TextView(context).apply {
                    textSize = 11f
                    maxLines = 1
                    setTextColor(colors.mutedText)
                    setPadding(dp(4), dp(3), dp(4), 0)
                }
                addView(meta, LinearLayout.LayoutParams(MATCH, WRAP))
                overview = TextView(context).apply {
                    textSize = 10f
                    maxLines = 2
                    setTextColor(colors.mutedText)
                    setPadding(dp(4), dp(3), dp(4), 0)
                }
                addView(overview, LinearLayout.LayoutParams(MATCH, 0, 1f))
                FocusDecorator.attach(this, ringVisible)
                setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(this, ringVisible())
                    if (focused) {
                        selectedEpisode = (getTag(TAG_EPISODE) as? ReleaseEpisodeTarget)?.episode ?: 0
                        host?.refreshHints()
                    }
                }
            }
            return EpisodeHolder(card, art, label, meta, overview)
        }

        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            val value = values[position]
            holder.title.text = "E${value.episode.toString().padStart(2, '0')} · ${value.title}"
            holder.meta.text = buildList {
                if (value.airDate.isNotEmpty()) add(value.airDate)
                if (value.runtimeMinutes > 0) add("${value.runtimeMinutes} min")
                if (value.hasFile) add("Downloaded")
                if (!value.monitored) add("Not monitored")
            }.joinToString(" · ")
            holder.overview.text = value.overview
            holder.itemView.setTag(TAG_EPISODE, value)
            holder.itemView.contentDescription = "Episode ${value.episode}, ${value.title}, search releases"
            loadImage(holder.art, value.image)
            holder.itemView.activateOnTap { openEpisode(value) }
        }
    }

    private data class EpisodeHolder(
        val root: View,
        val art: ImageView,
        val title: TextView,
        val meta: TextView,
        val overview: TextView
    ) : RecyclerView.ViewHolder(root)

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

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val TAG_EPISODE = -0x7fffffd8
    }
}
