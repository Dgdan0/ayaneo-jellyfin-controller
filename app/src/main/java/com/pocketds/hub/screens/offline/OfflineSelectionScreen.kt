package com.pocketds.hub.screens.offline

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareItem
import com.pocketds.hub.model.OfflineSelectionItem
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.settings.OfflineSettings
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

/** Controller-first episode picker used by series and season download actions. */
class OfflineSelectionScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val fallbackTitle: String,
    private val seasonId: String = "",
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Choose downloads"
    override val focusOnShow = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var loading: ProgressBar
    private lateinit var overlay: ChoiceOverlay
    private val selected = linkedSetOf<String>()
    private val cards = linkedMapOf<String, EpisodeCard>()
    private var catalog: OfflineSelectionResponse? = null
    private var loadJob: Job? = null
    private var prepareJob: Job? = null
    private var initialMenuShown = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = fallbackTitle
                    textSize = 22f
                    setTextColor(colors.primaryText)
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                counter = TextView(context).apply {
                    textSize = 13f
                    setTextColor(colors.accent)
                    gravity = Gravity.END
                }
                addView(counter, LinearLayout.LayoutParams(dp(210), WRAP))
                addView(TextView(context).apply {
                    text = "↓"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(colors.primaryText)
                    contentDescription = "Download selected episodes"
                    background = Styler.cardBackground(context, colors, cornerDp = 10f)
                    Styler.makeFocusable(this); FocusDecorator.attach(this, ringVisible, scale = false)
                    setOnFocusChangeListener { view, _ -> FocusDecorator.refresh(view, ringVisible()); host.refreshHints() }
                    activateOnTap { confirmSelection() }
                }, LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginStart = dp(7) })
            })
            status = TextView(context).apply {
                text = "Loading available episodes…"
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(0, dp(4), 0, dp(6))
            }
            addView(status)
            loading = ProgressBar(context).apply { isIndeterminate = true }
            addView(loading, LinearLayout.LayoutParams(MATCH, dp(26)))
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(context).apply {
                isFocusable = false
                clipToPadding = false
                setPadding(0, 0, 0, dp(76))
                addView(content)
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        updateCounter()
        return root
    }

    override fun onShow() {
        if (catalog == null && loadJob?.isActive != true) load()
    }

    override fun onHide() {
        scope.coroutineContext.cancelChildren(); loadJob = null; prepareJob = null
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() { scope.cancel() }

    override fun hints(): List<ButtonHint> = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else buildList {
        add(ButtonHint.activate("Select episode"))
        add(ButtonHint.secondary("Select season"))
        add(ButtonHint("Start", "Select all", PadAction.Menu))
        add(ButtonHint.primary("Download selected"))
        add(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        return when (action) {
            PadAction.Activate -> focusedCard()?.let { toggle(it.item.item.id); true } ?: false
            PadAction.Secondary -> focusedCard()?.let { card ->
                val ids = visibleSeasons().firstOrNull { it.season.id == card.seasonId }
                    ?.episodes?.filter { it.available && !alreadyStored(it.item.id) }?.map { it.item.id }.orEmpty()
                val select = ids.any { it !in selected }
                ids.forEach { if (select) selected += it else selected -= it }
                refreshCards(); true
            } ?: false
            PadAction.Menu -> {
                val ids = selectableItems().map { it.item.id }
                val select = ids.any { it !in selected }
                selected.clear(); if (select) selected.addAll(ids)
                refreshCards(); true
            }
            PadAction.Primary -> { confirmSelection(); true }
            PadAction.Refresh -> { load(); true }
            else -> false
        }
    }

    override fun requestInitialFocus(): Boolean = cards.values.firstOrNull()?.view?.requestFocus() == true

    private fun load() {
        loadJob?.cancel()
        loading.visibility = View.VISIBLE
        status.setTextColor(colors.mutedText)
        status.text = "Loading available episodes…"
        loadJob = scope.launch {
            when (val result = api.offlineSelection(seriesId)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    loading.visibility = View.GONE
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Select retries"
                }
            }
            loadJob = null
        }
    }

    private fun render(value: OfflineSelectionResponse) {
        catalog = value
        loading.visibility = View.GONE
        content.removeAllViews(); cards.clear(); selected.clear()
        val shown = value.seasons.filter { seasonId.isEmpty() || it.season.id == seasonId }
        shown.forEach { season ->
            content.addView(LinearLayout(host.viewContext).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                val seasonPoster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(seasonPoster, LinearLayout.LayoutParams(dp(36), dp(52)).apply { marginEnd = dp(9) })
                addView(TextView(context).apply {
                    text = season.season.title.ifBlank {
                        if (season.season.seasonNumber == 0) "Specials" else "Season ${season.season.seasonNumber}"
                    }
                    textSize = 16f; setTextColor(colors.primaryText)
                })
                setPadding(dp(2), dp(7), 0, dp(3))
                loadImage(seasonPoster, season.season.poster.ifEmpty { season.season.thumb })
            })
            val recycler = RecyclerView(host.viewContext).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                clipChildren = false; clipToPadding = false
                adapter = EpisodeAdapter(season.season.id, season.episodes)
                layoutParams = LinearLayout.LayoutParams(MATCH, dp(190))
            }
            content.addView(recycler)
        }
        status.setTextColor(colors.mutedText)
        status.text = "Original files · embedded audio and subtitles included · external subtitles saved beside them"
        updateCounter()
        if (!initialMenuShown) {
            initialMenuShown = true
            showQuickChoices()
        } else requestInitialFocus()
    }

    private fun showQuickChoices() {
        val all = selectableItems().map { it.item }
        overlay.show(
            "What should be downloaded?",
            "You can review and change the selected episodes before the download starts.",
            listOf(
                ChoiceOverlay.Choice("all", "All available episodes", fileSize(all.sumOf(::sizeOf))),
                ChoiceOverlay.Choice("unwatched", "All unwatched episodes"),
                ChoiceOverlay.Choice("next", "Next unwatched episode"),
                ChoiceOverlay.Choice("next_x", "Next episodes…"),
                ChoiceOverlay.Choice("manual", "Choose episodes")
            ),
            onCancel = { requestInitialFocus(); host.refreshHints() }
        ) { choice ->
            when (choice) {
                "all" -> selectItems(all)
                "unwatched" -> selectItems(all.filter { !it.played })
                "next" -> nextItems(1).let { items ->
                    if (items.isEmpty()) host.notify("No unwatched episode is available")
                    else selectItems(items)
                }
                "next_x" -> if (nextItems(1).isEmpty()) {
                    host.notify("No unwatched episode is available")
                } else showNextCount()
                else -> Unit
            }
            requestInitialFocus(); host.refreshHints()
        }
        host.refreshHints()
    }

    private fun showNextCount() {
        val counts = listOf(2, 3, 5, 10, 20)
        overlay.show(
            "How many next episodes?", "Only available, unwatched episodes are counted.",
            counts.map { ChoiceOverlay.Choice(it.toString(), "$it episodes") },
            onCancel = { requestInitialFocus(); host.refreshHints() }
        ) { choice ->
            val items = nextItems(choice.toInt())
            selectItems(items); requestInitialFocus(); host.refreshHints()
        }
    }

    private fun selectItems(items: List<com.pocketds.hub.model.LibraryItem>) {
        selected.clear(); selected.addAll(items.map { it.id }); refreshCards()
    }

    private fun toggle(id: String) {
        if (alreadyStored(id)) {
            host.notify("This episode is already downloaded or queued")
            return
        }
        if (!selected.add(id)) selected.remove(id)
        refreshCards()
    }

    private fun refreshCards() {
        cards.values.forEach { it.render(it.item.item.id in selected) }
        updateCounter(); host.refreshHints()
    }

    private fun updateCounter() {
        if (!::counter.isInitialized) return
        val lookup = availableItems().associateBy { it.item.id }
        val bytes = selected.sumOf { id -> lookup[id]?.estimatedSizeBytes ?: 0L }
        counter.text = "${selected.size} selected · ${fileSize(bytes)}"
    }

    private fun confirmSelection() {
        if (selected.isEmpty()) {
            host.notify("Choose at least one episode")
            return
        }
        val lookup = availableItems().associateBy { it.item.id }
        val bytes = selected.sumOf { lookup[it]?.estimatedSizeBytes ?: 0L }
        val location = OfflineSettings.selectedStorage(host.viewContext)
        if (location == null) {
            host.notify("The selected download location is unavailable")
            return
        }
        val available = location.availableBytes
        val source = selected.firstOrNull()?.let { lookup[it]?.sources?.firstOrNull() }
        val quality = source?.name?.ifBlank { source.container.uppercase() }.orEmpty()
        overlay.show(
            "Download ${selected.size} episode${if (selected.size == 1) "" else "s"}?",
            "${fileSize(bytes)} selected · ${fileSize(available)} free\n" +
                "${location.label} · private app storage · Original" +
                    if (quality.isEmpty()) "" else " · $quality",
            listOf(
                ChoiceOverlay.Choice("download", "Add to download manager"),
                ChoiceOverlay.Choice("cancel", "Keep choosing")
            ),
            onCancel = host::refreshHints
        ) { if (it == "download") prepare() else requestInitialFocus() }
        host.refreshHints()
    }

    private fun prepare() {
        if (prepareJob?.isActive == true) return
        val value = catalog ?: return
        val chosen = availableItems().filter { it.item.id in selected }
        val batchKey = "offline-${System.currentTimeMillis()}-${seriesId.take(8)}"
        status.setTextColor(colors.mutedText); status.text = "Preparing secure download links…"
        prepareJob = scope.launch {
            val body = OfflinePrepareBody(
                batchKey, seriesId,
                chosen.map { OfflinePrepareItem("$batchKey-${it.item.id.take(12)}", it.item.id) }
            )
            when (val result = api.prepareOffline(body)) {
                is HubResult.Ok -> {
                    val count = OfflineRepository.get(host.viewContext).enqueue(
                        value.series.title.ifBlank { fallbackTitle }, seriesId, result.value.items
                    )
                    if (count > 0) {
                        OfflineDownloadService.start(host.viewContext)
                        host.notify("Added $count episode${if (count == 1) "" else "s"} to downloads")
                        host.back()
                    } else {
                        host.notify("Those episodes are already downloaded or queued")
                        status.text = "Nothing new was added"
                    }
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText); status.text = result.message
                    host.notify(result.message)
                }
            }
            prepareJob = null
        }
    }

    private fun focusedCard(): EpisodeCard? = cards.values.firstOrNull { it.view.hasFocus() }
    private fun sizeOf(item: com.pocketds.hub.model.LibraryItem) =
        availableItems().firstOrNull { it.item.id == item.id }?.estimatedSizeBytes ?: 0L
    private fun visibleSeasons() = catalog?.seasons?.filter { seasonId.isEmpty() || it.season.id == seasonId }.orEmpty()
    private fun availableItems() = visibleSeasons().flatMap { it.episodes }.filter { it.available }
    private fun selectableItems() = availableItems().filterNot { alreadyStored(it.item.id) }
    private fun alreadyStored(itemId: String) = OfflineRepository.get(host.viewContext).forItem(itemId) != null
    private fun nextItems(count: Int): List<com.pocketds.hub.model.LibraryItem> {
        val values = selectableItems().map { it.item }
        val target = catalog?.playTargetId.orEmpty()
        val start = values.indexOfFirst { it.id == target }.takeIf { it >= 0 }
            ?: values.indexOfFirst { !it.played }.coerceAtLeast(0)
        return values.drop(start).filter { !it.played || it.id == target }.take(count)
    }

    private inner class EpisodeAdapter(
        private val season: String,
        private val values: List<OfflineSelectionItem>
    ) : RecyclerView.Adapter<EpisodeHolder>() {
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeHolder {
            val card = EpisodeCard(parent, season)
            return EpisodeHolder(card.view, card)
        }
        override fun onBindViewHolder(holder: EpisodeHolder, position: Int) {
            holder.card.bind(values[position])
            cards[values[position].item.id] = holder.card
        }
        override fun onViewRecycled(holder: EpisodeHolder) {
            holder.card.item.item.id.takeIf { it.isNotEmpty() }?.let { if (cards[it] === holder.card) cards.remove(it) }
        }
    }

    private inner class EpisodeCard(parent: ViewGroup, val seasonId: String) {
        lateinit var item: OfflineSelectionItem
        private val image: ImageView
        private val marker: TextView
        private val title: TextView
        private val meta: TextView
        val view = FrameLayout(parent.context).apply {
            background = Styler.cardBackground(context, colors)
            Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = RecyclerView.LayoutParams(dp(224), dp(180)).apply { marginEnd = dp(9); topMargin = dp(3) }
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, FrameLayout.LayoutParams(MATCH, dp(118)))
            marker = TextView(context).apply {
                gravity = Gravity.CENTER; textSize = 16f; setTextColor(colors.primaryText)
                background = Styler.cardBackground(context, colors, cornerDp = 16f)
            }
            addView(marker, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(7); marginEnd = dp(7)
            })
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(5), dp(8), dp(5))
                title = TextView(context).apply { textSize = 13f; maxLines = 1; setTextColor(colors.primaryText) }
                addView(title)
                meta = TextView(context).apply { textSize = 10f; maxLines = 1; setTextColor(colors.mutedText) }
                addView(meta)
            }
            addView(labels, FrameLayout.LayoutParams(MATCH, dp(62), Gravity.BOTTOM))
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { focused, _ -> FocusDecorator.refresh(focused, ringVisible()); host.refreshHints() }
            activateOnTap { if (::item.isInitialized && item.available) toggle(item.item.id) }
        }

        fun bind(value: OfflineSelectionItem) {
            item = value
            title.text = if (value.item.indexNumber > 0) "E${value.item.indexNumber} · ${value.item.title}" else value.item.title
            val local = OfflineRepository.get(host.viewContext).forItem(value.item.id)
            meta.text = if (value.available) {
                buildList {
                    add(fileSize(value.estimatedSizeBytes))
                    if (local != null) add(local.state.wire.replaceFirstChar { it.uppercase() })
                    if (value.item.played) add("Watched") else if (value.item.progress > 0) add("${(value.item.progress * 100).toInt()}% watched")
                }.joinToString(" · ")
            } else "Unavailable"
            view.alpha = if (value.available) 1f else .45f
            view.isEnabled = value.available
            loadImage(image, value.item.thumb.ifEmpty { value.item.poster })
            render(value.item.id in selected)
        }

        fun render(isSelected: Boolean) {
            val stored = ::item.isInitialized && alreadyStored(item.item.id)
            marker.text = if (isSelected || stored) "✓" else "○"
            marker.setTextColor(if (isSelected || stored) colors.accent else colors.mutedText)
            view.contentDescription = "${title.text}, ${when { stored -> "already downloaded or queued"; isSelected -> "selected"; else -> "not selected" }}"
        }
    }

    private class EpisodeHolder(view: View, val card: EpisodeCard) : RecyclerView.ViewHolder(view)

    private fun loadImage(view: ImageView, path: String) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        if (path.isEmpty()) return
        ((api as? HubClient)?.imageLoader ?: ImageLoader(host.viewContext)).enqueue(
            ImageRequest.Builder(view.context).data(api.imageUrl(path)).target(view)
                .bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun fileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
