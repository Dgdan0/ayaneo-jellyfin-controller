package com.pocketds.hub.screens.home

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.HorizontalMode
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.HomeResponse
import com.pocketds.hub.model.JellyfinUser
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.LibraryDetailScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
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

/** Personal Jellyfin rows: favourites, resume, next episode, and recent additions. */
class HomeScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title = "Home"
    override val horizontalMode = HorizontalMode.CONFINED

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = RowsAdapter()
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var heading: TextView
    private lateinit var rows: RecyclerView
    private lateinit var userOverlay: ChoiceOverlay
    private var host: ScreenHost? = null
    private var loadJob: Job? = null
    private var userJob: Job? = null
    private var returnRefreshJob: Job? = null
    private var users: List<JellyfinUser> = emptyList()
    private var loadGeneration = 0
    private var wantsFocus = false
    private var selectedRowId = ""
    private var selectedItemId = ""
    private var selectedItemPosition = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val frame = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val content = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }
        frame.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        heading = TextView(host.viewContext).apply {
            text = HubSettings.userName(context).takeIf { it.isNotEmpty() }
                ?.let { "Home · $it" } ?: "Home"
            textSize = 18f
            setTextColor(colors.primaryText)
            setPadding(dp(14), dp(8), dp(14), 0)
        }
        content.addView(heading)
        status = TextView(host.viewContext).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(dp(14), dp(2), dp(14), dp(2))
        }
        content.addView(status)

        rows = RecyclerView(host.viewContext).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HomeScreen.adapter
            clipToPadding = false
            clipChildren = false
            setItemViewCacheSize(HOME_ROW_ORDER.size)
            setPadding(0, dp(28), 0, dp(84))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        if (!wantsFocus) return
                        val target = this@HomeScreen.adapter.rowIndex(selectedRowId)
                            .takeIf { it >= 0 } ?: 0
                        if (getChildAdapterPosition(view) != target) return
                        wantsFocus = false
                        (view as? PosterRowView)?.focusItem(selectedItemId, selectedItemPosition)
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                }
            )
        }
        content.addView(rows)

        userOverlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        frame.addView(userOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    override fun onShow() {
        if (users.isEmpty() && userJob?.isActive != true) loadUsers(openWhenReady = false)
        if (adapter.itemCount == 0 && loadJob?.isActive != true) load()
        else {
            requestInitialFocus()
            returnRefreshJob?.cancel()
            returnRefreshJob = scope.launch {
                delay(RETURN_REFRESH_DELAY_MILLIS)
                load(force = true)
                returnRefreshJob = null
            }
        }
    }

    override fun onHide() {
        loadGeneration++
        if (::userOverlay.isInitialized && userOverlay.isOpen) userOverlay.dismiss()
        scope.coroutineContext.cancelChildren()
        loadJob = null
        userJob = null
        returnRefreshJob = null
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun hints() = if (::userOverlay.isInitialized && userOverlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else {
        listOf(
            ButtonHint.activate("Details"),
            ButtonHint.secondary("Users"),
            ButtonHint("⟳", "Refresh (Select)", PadAction.Refresh)
        )
    }

    override fun onPad(action: PadAction): Boolean {
        if (::userOverlay.isInitialized && userOverlay.onPad(action)) return true
        return when (action) {
        PadAction.Activate -> focusedHit()?.let(::open) != null
        PadAction.Secondary -> {
            if (users.isEmpty()) loadUsers(openWhenReady = true) else showUsers()
            true
        }
        PadAction.Refresh -> {
            load(force = true)
            true
        }
        else -> false
        }
    }

    override fun requestInitialFocus(): Boolean {
        if (!::rows.isInitialized || adapter.itemCount == 0) {
            wantsFocus = true
            return false
        }
        val rowIndex = adapter.rowIndex(selectedRowId).takeIf { it >= 0 } ?: 0
        wantsFocus = true
        rows.scrollToPosition(rowIndex)
        rows.post {
            val row = rows.findViewHolderForAdapterPosition(rowIndex)?.itemView as? PosterRowView
            if (row != null) {
                wantsFocus = false
                row.focusItem(selectedItemId, selectedItemPosition)
            }
        }
        return true
    }

    private fun loadUsers(openWhenReady: Boolean) {
        userJob?.cancel()
        if (openWhenReady) {
            status.setTextColor(colors.mutedText)
            status.text = "Loading Jellyfin users…"
        }
        userJob = scope.launch {
            when (val result = api.users()) {
                is HubResult.Ok -> {
                    users = result.value.users
                    val selected = users.firstOrNull { it.selected }
                        ?: users.firstOrNull { it.id == HubSettings.userId(heading.context) }
                    heading.text = selected?.name?.let { "Home · $it" } ?: "Home · Choose user"
                    if (openWhenReady) showUsers()
                }
                is HubResult.Failed -> if (openWhenReady) {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                    host?.notify(result.message)
                }
            }
            userJob = null
            host?.refreshHints()
        }
    }

    private fun showUsers() {
        if (users.isEmpty()) {
            host?.notify("No enabled Jellyfin users were found")
            return
        }
        val selectedIndex = users.indexOfFirst { it.selected }.coerceAtLeast(0)
        userOverlay.show(
            title = "Who is watching?",
            subtitle = "Continue Watching, Next Up and progress use this profile.",
            choices = users.map {
                ChoiceOverlay.Choice(
                    it.id,
                    it.name,
                    if (it.selected) "Current profile" else ""
                )
            },
            startIndex = selectedIndex,
            onCancel = { host?.refreshHints() }
        ) { pickedID ->
            val picked = users.firstOrNull { it.id == pickedID } ?: return@show
            host?.selectJellyfinUser(picked.id, picked.name)
        }
        host?.refreshHints()
    }

    private fun load(force: Boolean = false) {
        loadGeneration++
        val generation = loadGeneration
        loadJob?.cancel()
        status.setTextColor(colors.mutedText)
        status.text = if (force) "Refreshing your Jellyfin home…" else "Loading your Jellyfin home…"
        wantsFocus = adapter.itemCount == 0
        loadJob = scope.launch {
            when (val result = api.home()) {
                is HubResult.Ok -> {
                    if (generation != loadGeneration) return@launch
                    render(result.value)
                }
                is HubResult.Failed -> {
                    if (generation != loadGeneration) return@launch
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + if (adapter.itemCount == 0) {
                        " · Select retries"
                    } else {
                        " · showing previous rows · Select retries"
                    }
                    host?.refreshHints()
                }
            }
            loadJob = null
        }
    }

    private fun render(body: HomeResponse) {
        adapter.submit(body.rows, retainMissing = body.partial.isNotEmpty())
        status.setTextColor(if (body.partial.isEmpty()) colors.mutedText else colors.badgePending)
        status.text = when {
            body.rows.isEmpty() && adapter.itemCount == 0 -> "Nothing to continue or show yet."
            body.partial.isNotEmpty() -> buildString {
                append(adapter.itemCount).append(" rows · ")
                append(body.partial.joinToString(" · ") { it.message })
            }
            body.cache.stale -> "${adapter.itemCount} rows · cached"
            else -> "${adapter.itemCount} personal rows"
        }
        requestInitialFocus()
        host?.refreshHints()
    }

    private fun focusedHit(): SearchHit? =
        if (::rows.isInitialized) rows.findFocus()?.getTag(TAG_HIT) as? SearchHit else null

    private fun open(hit: SearchHit) {
        if (hit.jellyfinItemId.isEmpty()) {
            host?.notify("This Jellyfin item is no longer available")
            return
        }
        host?.push(
            LibraryDetailScreen(
                api,
                hit.jellyfinItemId,
                hit.media.title,
                hit.media.type,
                ringVisible
            )
        )
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowHolder>() {
        private val values = mutableListOf<DiscoverRow>()

        init {
            setHasStableIds(true)
        }

        fun rowIndex(rowId: String): Int = values.indexOfFirst { it.id == rowId }

        fun submit(next: List<DiscoverRow>, retainMissing: Boolean) {
            val incoming = next.associateBy { it.id }
            val previous = values.associateBy { it.id }
            val merged = if (retainMissing) {
                HOME_ROW_ORDER.mapNotNull { incoming[it] ?: previous[it] }
            } else {
                next
            }
            values.clear()
            values.addAll(merged)
            notifyDataSetChanged()
        }

        override fun getItemId(position: Int): Long = values[position].id.hashCode().toLong()
        override fun getItemCount() = values.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(PosterRowView(parent.context))

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            (holder.itemView as PosterRowView).bind(values[position])
        }
    }

    private inner class PosterRowView(context: android.content.Context) : LinearLayout(context) {
        private val label: TextView
        private val strip: RecyclerView
        private val stripAdapter = StripAdapter()
        private var row: DiscoverRow? = null
        private var pendingFocus = -1

        init {
            orientation = VERTICAL
            clipChildren = false
            label = TextView(context).apply {
                textSize = 13f
                setTextColor(colors.primaryText)
                setPadding(dp(12), dp(6), dp(12), dp(1))
            }
            addView(label)
            strip = RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
                adapter = stripAdapter
                isFocusable = false
                clipToPadding = false
                clipChildren = false
                setItemViewCacheSize(8)
                setPadding(dp(8), 0, dp(8), 0)
                addOnChildAttachStateChangeListener(
                    object : RecyclerView.OnChildAttachStateChangeListener {
                        override fun onChildViewAttachedToWindow(view: View) {
                            if (pendingFocus < 0 || getChildAdapterPosition(view) != pendingFocus) return
                            pendingFocus = -1
                            view.post { view.requestFocus() }
                        }

                        override fun onChildViewDetachedFromWindow(view: View) = Unit
                    }
                )
            }
            addView(strip, LayoutParams(MATCH, WRAP))
        }

        fun bind(next: DiscoverRow) {
            val changedRow = row?.id != next.id
            row = next
            label.text = next.title
            stripAdapter.submit(next.items)
            if (changedRow) strip.scrollToPosition(0)
        }

        fun focusItem(itemId: String, fallbackPosition: Int) {
            if (stripAdapter.itemCount == 0) return
            val target = stripAdapter.indexOf(itemId).takeIf { it >= 0 }
                ?: fallbackPosition.coerceIn(0, stripAdapter.itemCount - 1)
            pendingFocus = target
            strip.scrollToPosition(target)
            strip.post {
                strip.findViewHolderForAdapterPosition(target)?.itemView?.let {
                    pendingFocus = -1
                    it.requestFocus()
                }
            }
        }

        private inner class StripAdapter : RecyclerView.Adapter<CardHolder>() {
            private val items = mutableListOf<SearchHit>()

            init {
                setHasStableIds(true)
            }

            fun indexOf(itemId: String) = items.indexOfFirst { it.jellyfinItemId == itemId }

            fun submit(next: List<SearchHit>) {
                items.clear()
                items.addAll(next)
                notifyDataSetChanged()
            }

            override fun getItemId(position: Int): Long =
                items[position].jellyfinItemId.hashCode().toLong()

            override fun getItemCount() = items.size

            override fun getItemViewType(position: Int): Int =
                if (row?.id == "continue" || row?.id == "nextup") CARD_LANDSCAPE else CARD_POSTER

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
                val card: View = if (viewType == CARD_LANDSCAPE) {
                    HomeMediaCardView(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(dp(LANDSCAPE_CARD_DP), WRAP).apply {
                            val margin = dp(4)
                            setMargins(margin, margin, margin, margin)
                        }
                    }
                } else {
                    PosterCardView(parent.context, colors, POSTER_DP).apply {
                        layoutParams = RecyclerView.LayoutParams(dp(POSTER_CARD_DP), WRAP).apply {
                            val margin = dp(4)
                            setMargins(margin, margin, margin, margin)
                        }
                    }
                }
                card.apply {
                    if (layoutParams == null) layoutParams = RecyclerView.LayoutParams(WRAP, WRAP).apply {
                        val margin = dp(4)
                        setMargins(margin, margin, margin, margin)
                    }
                    FocusDecorator.attach(this, ringVisible)
                }
                return CardHolder(card)
            }

            override fun onBindViewHolder(holder: CardHolder, position: Int) {
                val hit = items[position]
                val card = holder.itemView
                val client = api as? HubClient
                val loader = client?.imageLoader ?: ImageLoader(card.context)
                when (card) {
                    is PosterCardView -> card.bind(hit, loader, api::imageUrl, showAvailability = false)
                    is HomeMediaCardView -> card.bind(hit, loader)
                }
                card.setTag(TAG_HIT, hit)
                card.setOnClickListener { open(hit) }
                card.setOnFocusChangeListener { _, focused ->
                    FocusDecorator.refresh(card, ringVisible())
                    if (focused) {
                        selectedRowId = row?.id.orEmpty()
                        selectedItemId = hit.jellyfinItemId
                        selectedItemPosition = holder.bindingAdapterPosition
                            .takeIf { it != RecyclerView.NO_POSITION }
                            ?: items.indexOfFirst { it.jellyfinItemId == hit.jellyfinItemId }.coerceAtLeast(0)
                        host?.refreshHints()
                    }
                }
            }
        }
    }

    /** Findroid-style landscape card for a concrete episode or movie action. */
    private inner class HomeMediaCardView(context: android.content.Context) : LinearLayout(context) {
        private val image: ImageView
        private val progress: View
        private val titleView: TextView
        private val subtitleView: TextView
        private var boundProgress = 0.0

        init {
            orientation = VERTICAL
            background = Styler.cardBackground(context, colors)
            Styler.makeFocusable(this)
            isClickable = true
            descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(5), dp(5), dp(5), dp(7))

            val artwork = FrameLayout(context)
            image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(colors.posterPlaceholder)
            }
            artwork.addView(image, FrameLayout.LayoutParams(MATCH, dp(LANDSCAPE_IMAGE_DP)))
            progress = View(context).apply {
                setBackgroundColor(colors.accent)
                visibility = GONE
            }
            artwork.addView(
                progress,
                FrameLayout.LayoutParams(0, dp(4), Gravity.BOTTOM or Gravity.START)
            )
            addView(artwork, LayoutParams(MATCH, dp(LANDSCAPE_IMAGE_DP)))
            image.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateProgress() }

            titleView = TextView(context).apply {
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colors.primaryText)
                setPadding(dp(2), dp(6), dp(2), 0)
            }
            addView(titleView, LayoutParams(MATCH, WRAP))
            subtitleView = TextView(context).apply {
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(colors.mutedText)
                setPadding(dp(2), dp(2), dp(2), 0)
            }
            addView(subtitleView, LayoutParams(MATCH, WRAP))
        }

        fun bind(hit: SearchHit, loader: ImageLoader) {
            titleView.text = hit.media.title
            subtitleView.text = hit.subtitle
            boundProgress = if (hit.played) 0.0 else hit.progress.coerceIn(0.0, 1.0)
            progress.visibility = if (boundProgress > 0) VISIBLE else GONE
            updateProgress()
            contentDescription = buildString {
                append(hit.media.title)
                if (hit.subtitle.isNotBlank()) append(", ").append(hit.subtitle)
                if (boundProgress > 0) append(", ").append((boundProgress * 100).toInt()).append(" percent watched")
            }
            image.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
            val path = hit.media.backdrop.ifEmpty { hit.media.poster }
            val url = api.imageUrl(path)
            if (url.isNotEmpty()) {
                loader.enqueue(
                    ImageRequest.Builder(context).data(url).target(image)
                        .bitmapConfig(Bitmap.Config.RGB_565).build()
                )
            }
        }

        private fun updateProgress() {
            if (boundProgress <= 0 || image.width <= 0) return
            val params = progress.layoutParams as FrameLayout.LayoutParams
            params.width = (image.width * boundProgress).toInt().coerceAtLeast(dp(2))
            progress.layoutParams = params
        }
    }

    private class RowHolder(view: View) : RecyclerView.ViewHolder(view)
    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    private fun dp(value: Int) = Styler.dpInt(requireNotNull(host).viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val POSTER_DP = 150f
        const val POSTER_CARD_DP = 104
        const val LANDSCAPE_CARD_DP = 236
        const val LANDSCAPE_IMAGE_DP = 127
        const val CARD_POSTER = 0
        const val CARD_LANDSCAPE = 1
        const val TAG_HIT = -0x7fffffe0
        const val RETURN_REFRESH_DELAY_MILLIS = 450L
        val HOME_ROW_ORDER = listOf("favourites", "continue", "nextup", "latest")
    }
}
