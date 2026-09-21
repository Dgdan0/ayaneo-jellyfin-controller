package com.pocketds.hub.screens.downloads

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pocketds.hub.model.ReadingDownloadItem
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/** A BookKeeprr transfer without exposing torrent or indexer identities. */
class ReadingDownloadRowView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {
    private val statusChip: TextView
    private val title: TextView
    private val trailing: TextView
    private val release: TextView
    private val progress: ProgressBar
    private val stats: TextView

    init {
        orientation = VERTICAL
        background = Styler.cardBackground(context, colors)
        val h = Styler.dpInt(context, 12f)
        val v = Styler.dpInt(context, 10f)
        setPadding(h, v, h, v)
        Styler.makeFocusable(this)

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(header, LayoutParams(MATCH, WRAP))
        statusChip = TextView(context).apply {
            textSize = 10f
            setPadding(Styler.dpInt(context, 8f), Styler.dpInt(context, 2f), Styler.dpInt(context, 8f), Styler.dpInt(context, 2f))
        }
        header.addView(statusChip, LayoutParams(WRAP, WRAP).apply { rightMargin = Styler.dpInt(context, 8f) })
        title = TextView(context).apply {
            textSize = 15f
            setTextColor(colors.primaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LayoutParams(0, WRAP, 1f))
        trailing = TextView(context).apply {
            textSize = 14f
            setTextColor(colors.primaryText)
        }
        header.addView(trailing)
        release = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        addView(release, LayoutParams(MATCH, WRAP))
        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(colors.posterPlaceholder)
        }
        addView(progress, LayoutParams(MATCH, Styler.dpInt(context, 5f)).apply {
            topMargin = Styler.dpInt(context, 7f)
            bottomMargin = Styler.dpInt(context, 5f)
        })
        stats = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
        }
        addView(stats)
    }

    fun bind(item: ReadingDownloadItem) {
        val color = if (item.failed) colors.badgeFailed else when (item.status) {
            "downloading" -> colors.accent
            "importing" -> colors.badgePending
            "completed", "imported" -> colors.badgeAvailable
            else -> colors.mutedText
        }
        statusChip.text = item.status.replace('_', ' ').ifEmpty { "unknown" }.uppercase()
        statusChip.setTextColor(colors.accentText)
        statusChip.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 9f)
            setColor(color)
        }
        title.text = item.title
        release.text = item.releaseTitle
        release.visibility = if (item.releaseTitle.isBlank() || item.releaseTitle == item.title) GONE else VISIBLE
        trailing.text = if (item.progressPercent > 0) "${item.progressPercent}%" else ""
        progress.progress = item.progressPercent.coerceIn(0, 100)
        progress.progressTintList = android.content.res.ColorStateList.valueOf(color)
        progress.visibility = if (item.failed && item.progressPercent == 0) GONE else VISIBLE
        stats.text = buildString {
            if (item.sizeBytes > 0) append(Fmt.bytes(item.sizeBytes))
            if (item.downloadSpeedBytesPerSecond > 0) {
                if (isNotEmpty()) append(" · ")
                append(Fmt.speed(item.downloadSpeedBytesPerSecond))
            }
            if (item.etaSeconds > 0 && item.isActive) {
                if (isNotEmpty()) append(" · ")
                append(Fmt.eta(item.etaSeconds)).append(" left")
            }
            if (isEmpty()) append(if (item.failed) "Needs attention" else "Waiting for BookKeeprr")
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
