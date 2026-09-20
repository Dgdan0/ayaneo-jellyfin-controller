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
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineCatalogPlayTarget
import com.pocketds.hub.offline.OfflineCatalogSeason
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

/** Offline series detail: a local Continue/Next action followed by downloaded seasons only. */
class OfflineSeriesScreen(
    private val api: HubApi,
    private val seriesId: String,
    private val seriesTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = seriesTitle
    override val focusOnShow = true

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var repository: OfflineRepository
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private var selectedKey = ""
    private var renderedSignature = ""
    private var receiverRegistered = false
    private val changedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = render()
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        repository = OfflineRepository.get(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(11), dp(18), 0)
                addView(TextView(context).apply {
                    text = seriesTitle; textSize = 23f; maxLines = 1; setTextColor(colors.primaryText)
                })
                summary = TextView(context).apply {
                    textSize = 11f; setTextColor(colors.mutedText); setPadding(0, dp(3), 0, dp(7))
                }
                addView(summary)
                content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                addView(ScrollView(context).apply {
                    isFocusable = false; clipToPadding = false; setPadding(0, 0, 0, dp(78)); addView(content)
                }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }.also { render(force = true) }
    }

    override fun onShow() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(host.viewContext, changedReceiver,
                IntentFilter(OfflineRepository.ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        render(force = true)
    }

    override fun onHide() = unregister()
    override fun onDestroyView() = unregister()

    override fun requestInitialFocus(): Boolean =
        findTagged(content, selectedKey)?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true

    override fun hints(): List<ButtonHint> = buildList {
        when ((host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedTarget -> add(ButtonHint.activate("Play"))
            is TaggedSeason -> add(ButtonHint.activate("Open season"))
        }
        add(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (action == PadAction.Refresh) { render(force = true); return true }
        return when (val tag = (host.viewContext as? android.app.Activity)?.currentFocus?.tag) {
            is TaggedTarget -> if (action == PadAction.Activate) {
                host.playItem(tag.target.row.manifest.item.id, resumeMode(tag.target)); true
            } else false
            is TaggedSeason -> if (action == PadAction.Activate) {
                host.push(OfflineSeasonScreen(api, seriesTitle, tag.season, ringVisible)); true
            } else false
            else -> false
        }
    }

    private fun render(force: Boolean = false) {
        if (!::content.isInitialized) return
        val seasons = OfflineCatalog.seasons(seriesId, repository.completed())
        val rows = seasons.flatMap { it.rows }
        val progress = repository.playbackProgress(rows.map { it.manifest.item.id })
        val signature = rows.joinToString("|") { row ->
            val value = progress[row.manifest.item.id]
            "${row.id}:${row.updatedAt}:${value?.positionMillis}:${value?.durationMillis}"
        }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedKey)?.key?.let { selectedKey = it }
        content.removeAllViews()
        summary.text = "${rows.size} downloaded episode${if (rows.size == 1) "" else "s"} · " +
            "${fileSize(rows.sumOf { it.totalBytes })} · available offline"
        if (rows.isEmpty()) {
            content.addView(emptyMessage("No episodes from this series remain on the device."))
            return
        }
        OfflineCatalog.playTarget(rows, progress)?.let { content.addView(playTargetCard(it)) }
        content.addView(TextView(host.viewContext).apply {
            text = "Downloaded seasons"; textSize = 17f; setTextColor(colors.primaryText)
            setPadding(dp(2), dp(16), dp(2), dp(5))
        })
        val seasonRow = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(2), dp(16), dp(12))
            seasons.forEach { addView(seasonCard(it)) }
        }
        content.addView(HorizontalScrollView(host.viewContext).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(seasonRow)
        }, LinearLayout.LayoutParams(MATCH, dp(230)))
        content.post { findTagged(content, selectedKey)?.requestFocus() }
    }

    private fun playTargetCard(target: OfflineCatalogPlayTarget): View {
        val item = target.row.manifest.item
        val heading = when (target.kind) {
            OfflineCatalogPlayTarget.Kind.RESUME -> "Resume ${episodeCode(item)}"
            OfflineCatalogPlayTarget.Kind.NEXT -> "Play next ${episodeCode(item)}"
            OfflineCatalogPlayTarget.Kind.START -> "Start series · ${episodeCode(item)}"
        }
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 11f); setPadding(dp(14), dp(10), dp(14), dp(10))
            tag = TaggedTarget(target); Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(TextView(context).apply {
                text = "▶"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(colors.accent)
            }, LinearLayout.LayoutParams(dp(34), dp(42)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = heading; textSize = 15f; maxLines = 1; setTextColor(colors.primaryText) })
                addView(TextView(context).apply {
                    text = buildList { add(item.title); if (target.kind == OfflineCatalogPlayTarget.Kind.RESUME) add(time(target.positionMillis)) }.joinToString(" · ")
                    textSize = 11f; maxLines = 1; setTextColor(colors.mutedText)
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(5) }
            contentDescription = "$heading, ${item.title}"
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { host.playItem(item.id, resumeMode(target)) }
        }
    }

    private fun seasonCard(season: OfflineCatalogSeason): View {
        val first = season.rows.first()
        lateinit var image: ImageView
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL; background = Styler.cardBackground(context, colors, cornerDp = 10f)
            setPadding(dp(5), dp(5), dp(5), dp(5)); tag = TaggedSeason(season)
            Styler.makeFocusable(this); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(MATCH, dp(152)))
            addView(TextView(context).apply {
                text = seasonName(season.number); textSize = 13f; maxLines = 1; setTextColor(colors.primaryText)
                setPadding(dp(2), dp(5), dp(2), 0)
            })
            addView(TextView(context).apply {
                text = "${season.rows.size} downloaded episode${if (season.rows.size == 1) "" else "s"}"
                textSize = 9f; maxLines = 1; setTextColor(colors.mutedText); setPadding(dp(2), 0, dp(2), 0)
            })
            layoutParams = LinearLayout.LayoutParams(dp(166), dp(214)).apply { marginEnd = dp(9) }
            contentDescription = "${seasonName(season.number)}, ${season.rows.size} downloaded episodes"
            FocusDecorator.attach(this, ringVisible)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) { selectedKey = (view.tag as TaggedKey).key; host.refreshHints() }
            }
            activateOnTap { host.push(OfflineSeasonScreen(api, seriesTitle, season, ringVisible)) }
            loadArtwork(image, first)
        }
    }

    private fun loadArtwork(view: ImageView, row: OfflineDownload) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val local = sequenceOf("poster", "thumb", "backdrop").map { repository.artworkFile(row, it) }
            .firstOrNull { it.isFile && it.length() > 0 } ?: return
        ((api as? HubClient)?.imageLoader ?: ImageLoader(view.context)).enqueue(
            ImageRequest.Builder(view.context).data(local).target(view).bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun emptyMessage(message: String) = TextView(host.viewContext).apply {
        text = message; textSize = 14f; gravity = Gravity.CENTER; setTextColor(colors.mutedText)
        setPadding(dp(30), dp(80), dp(30), dp(30))
    }

    private fun unregister() {
        if (receiverRegistered) { host.viewContext.unregisterReceiver(changedReceiver); receiverRegistered = false }
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
    private fun resumeMode(target: OfflineCatalogPlayTarget) = if (target.kind == OfflineCatalogPlayTarget.Kind.RESUME) "resume" else "restart"
    private fun episodeCode(item: com.pocketds.hub.model.LibraryItem) =
        if (item.seasonNumber > 0 && item.indexNumber > 0) "S${item.seasonNumber}E${item.indexNumber}" else item.title
    private fun seasonName(number: Int) = if (number == 0) "Specials" else "Season $number"
    private fun time(millis: Long) = "%d:%02d".format(millis / 60_000L, millis / 1_000L % 60L)
    private fun fileSize(bytes: Long) = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private sealed interface TaggedKey { val key: String }
    private data class TaggedTarget(val target: OfflineCatalogPlayTarget) : TaggedKey { override val key = "play" }
    private data class TaggedSeason(val season: OfflineCatalogSeason) : TaggedKey { override val key = "season:${season.key}" }
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT; const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}
