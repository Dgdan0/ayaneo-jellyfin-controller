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
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.offline.OfflineCatalog
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

/** A network-independent series view containing only seasons and episodes stored on this device. */
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
    private lateinit var overlay: ChoiceOverlay
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
            setPadding(dp(18), dp(11), dp(18), 0)
            addView(TextView(context).apply {
                text = seriesTitle
                textSize = 23f
                maxLines = 1
                setTextColor(colors.primaryText)
            })
            summary = TextView(context).apply {
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(0, dp(3), 0, dp(5))
            }
            addView(summary)
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(ScrollView(context).apply {
                isFocusable = false
                clipToPadding = false
                setPadding(0, 0, 0, dp(78))
                addView(content)
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

    override fun requestInitialFocus(): Boolean =
        findEpisode(content, selectedId)?.requestFocus() == true || firstFocusable(content)?.requestFocus() == true

    override fun hints(): List<ButtonHint> = if (overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else {
        listOf(ButtonHint.activate("Play"), ButtonHint.primary("Remove"), ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (action == PadAction.Refresh) {
            render(force = true)
            return true
        }
        val row = focusedEpisode() ?: return false
        return when (action) {
            PadAction.Activate -> { host.playItem(row.manifest.item.id); true }
            PadAction.Primary -> { confirmRemove(row); true }
            else -> false
        }
    }

    private fun render(force: Boolean = false) {
        if (!::content.isInitialized || overlay.isOpen) return
        val seasons = OfflineCatalog.seasons(seriesId, repository.completed())
        val rows = seasons.flatMap { it.rows }
        val signature = rows.joinToString("|") { "${it.id}:${it.updatedAt}:${it.localPath}" }
        if (!force && signature == renderedSignature) return
        renderedSignature = signature
        val focused = (host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedEpisode
        if (focused != null) selectedId = focused.row.id
        content.removeAllViews()
        summary.text = "${rows.size} downloaded episode${if (rows.size == 1) "" else "s"} · ${fileSize(rows.sumOf { it.totalBytes })} · available offline"
        if (rows.isEmpty()) {
            content.addView(TextView(host.viewContext).apply {
                text = "No episodes from this series remain on the device."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(colors.mutedText)
                setPadding(dp(30), dp(80), dp(30), dp(30))
            })
            return
        }
        seasons.forEach { season ->
                val seasonRows = season.rows
                val seasonNumber = season.number
                content.addView(LinearLayout(host.viewContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    addView(TextView(context).apply {
                        text = if (seasonNumber == 0) "Specials" else "Season $seasonNumber"
                        textSize = 17f
                        setTextColor(colors.primaryText)
                    }, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(TextView(context).apply {
                        text = "${seasonRows.size} episode${if (seasonRows.size == 1) "" else "s"}"
                        textSize = 10f
                        setTextColor(colors.mutedText)
                    })
                    setPadding(dp(2), dp(7), dp(2), dp(3))
                })
                val row = LinearLayout(host.viewContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(2), dp(2), dp(16), dp(7))
                    seasonRows.forEach { addView(episodeCard(it)) }
                }
                content.addView(HorizontalScrollView(host.viewContext).apply {
                    isFocusable = false
                    isHorizontalScrollBarEnabled = false
                    clipToPadding = false
                    addView(row)
                }, LinearLayout.LayoutParams(MATCH, dp(166)))
            }
        content.post { findEpisode(content, selectedId)?.requestFocus() }
    }

    private fun episodeCard(row: OfflineDownload): View {
        val item = row.manifest.item
        lateinit var image: ImageView
        return LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 10f)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            tag = TaggedEpisode(row)
            Styler.makeFocusable(this)
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            addView(image, LinearLayout.LayoutParams(MATCH, dp(105)))
            addView(TextView(context).apply {
                text = "E${item.indexNumber} · ${item.title}"
                textSize = 12f
                maxLines = 1
                setTextColor(colors.primaryText)
                setPadding(dp(2), dp(4), dp(2), 0)
            })
            addView(TextView(context).apply {
                text = "${runtime(item.runtimeSeconds)} · ${fileSize(row.totalBytes)}"
                textSize = 9f
                maxLines = 1
                setTextColor(colors.mutedText)
                setPadding(dp(2), 0, dp(2), 0)
            })
            layoutParams = LinearLayout.LayoutParams(dp(214), dp(154)).apply { marginEnd = dp(8) }
            contentDescription = "Episode ${item.indexNumber}, ${item.title}, downloaded"
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, hasFocus ->
                FocusDecorator.refresh(view, ringVisible())
                if (hasFocus) {
                    selectedId = row.id
                    host.refreshHints()
                }
            }
            activateOnTap { host.playItem(item.id) }
            loadArtwork(image, row)
        }
    }

    private fun confirmRemove(row: OfflineDownload) {
        overlay.show(
            "Remove ${row.manifest.item.title}?",
            "The local episode and downloaded subtitles will be deleted.",
            listOf(
                ChoiceOverlay.Choice("keep", "Keep episode"),
                ChoiceOverlay.Choice("remove", "Remove", danger = true)
            ),
            onCancel = host::refreshHints
        ) {
            if (it == "remove") OfflineDownloadService.remove(host.viewContext, row.id)
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun focusedEpisode(): OfflineDownload? =
        ((host.viewContext as? android.app.Activity)?.currentFocus?.tag as? TaggedEpisode)?.row

    private fun findEpisode(root: ViewGroup, id: String): View? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if ((child.tag as? TaggedEpisode)?.row?.id == id) return child
            if (child is ViewGroup) findEpisode(child, id)?.let { return it }
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

    private fun loadArtwork(view: ImageView, row: OfflineDownload) {
        view.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val local = sequenceOf("thumb", "poster", "backdrop")
            .map { repository.artworkFile(row, it) }
            .firstOrNull { it.isFile && it.length() > 0 } ?: return
        ((api as? HubClient)?.imageLoader ?: ImageLoader(view.context)).enqueue(
            ImageRequest.Builder(view.context).data(local).target(view)
                .bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun runtime(seconds: Int): String = when {
        seconds >= 3_600 -> "%dh %02dm".format(seconds / 3_600, seconds % 3_600 / 60)
        seconds >= 60 -> "%dm".format(seconds / 60)
        else -> "${seconds}s"
    }

    private fun fileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private data class TaggedEpisode(val row: OfflineDownload)
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
