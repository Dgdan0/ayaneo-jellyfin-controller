package com.pocketds.hub.screens.downloads

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.Stages
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/**
 * One transfer.
 *
 * Full width rather than a card in a grid, because the useful information here
 * is all text and the interesting part -- why something is stuck -- is a
 * sentence, not a number.
 */
class DownloadRowView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {

    private val stageChip: TextView
    private val titleView: TextView
    private val trailing: TextView
    private val subline: TextView
    private val bar: ProgressBar
    private val stats: TextView
    private val problem: TextView

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

        stageChip = TextView(context).apply {
            textSize = 10f
            setPadding(
                Styler.dpInt(context, 8f), Styler.dpInt(context, 2f),
                Styler.dpInt(context, 8f), Styler.dpInt(context, 2f)
            )
        }
        header.addView(stageChip, LayoutParams(WRAP, WRAP).apply {
            rightMargin = Styler.dpInt(context, 8f)
        })

        titleView = TextView(context).apply {
            textSize = 15f
            setTextColor(colors.primaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(titleView, LayoutParams(0, WRAP, 1f))

        trailing = TextView(context).apply {
            textSize = 14f
            setTextColor(colors.primaryText)
            gravity = Gravity.END
        }
        header.addView(trailing, LayoutParams(WRAP, WRAP).apply {
            leftMargin = Styler.dpInt(context, 8f)
        })

        subline = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        addView(subline, LayoutParams(MATCH, WRAP))

        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
        }
        addView(bar, LayoutParams(MATCH, Styler.dpInt(context, 5f)).apply {
            topMargin = Styler.dpInt(context, 7f)
            bottomMargin = Styler.dpInt(context, 5f)
        })

        stats = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            maxLines = 1
        }
        addView(stats, LayoutParams(MATCH, WRAP))

        problem = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.dangerText)
            maxLines = 3
            visibility = GONE
        }
        addView(problem, LayoutParams(MATCH, WRAP).apply {
            topMargin = Styler.dpInt(context, 4f)
        })
    }

    fun bind(item: ActivityItem) {
        val stageColor = stageColor(item.stage)
        stageChip.text = Stages.label(item.stage).uppercase()
        stageChip.setTextColor(colors.accentText)
        stageChip.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 9f)
            setColor(stageColor)
        }

        titleView.text = item.headline
        subline.text = item.subline
        subline.visibility = if (item.subline.isEmpty()) GONE else VISIBLE

        // A seeding torrent sits at 100% forever; showing its ratio-free percent
        // is noise, so the trailing slot carries the upload rate instead.
        trailing.text = when {
            item.stage == Stages.SEEDING -> "↑ " + Fmt.speed(item.uploadBps)
            item.progress > 0 -> Fmt.percent(item.progress)
            else -> ""
        }

        bar.progress = (item.progress * 1000).toInt().coerceIn(0, 1000)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(stageColor)
        bar.progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(colors.posterPlaceholder)
        bar.visibility = if (item.stage == Stages.STUCK && item.progress <= 0.0) GONE else VISIBLE

        stats.text = statsLine(item)

        // The *arr's own words, verbatim. Paraphrasing "Found executable file
        // with extension: '.exe'" into "import failed" would have hidden the
        // single most useful thing this screen has ever said.
        val note = buildString {
            item.arr?.problem?.takeIf { it.isNotEmpty() }?.let { append(it) }
            for (warning in item.warnings) {
                if (isNotEmpty()) append(" · ")
                append(warningLabel(warning))
            }
        }
        problem.text = note
        problem.visibility = if (note.isEmpty()) GONE else VISIBLE
    }

    private fun statsLine(item: ActivityItem): String = buildString {
        if (item.queueItems > 1) {
            append(item.queueItems).append(" episodes")
        }
        if (item.sizeBytes > 0) {
            if (isNotEmpty()) append(" · ")
            if (item.remainingBytes in 1 until item.sizeBytes) {
                append(Fmt.bytes(item.sizeBytes - item.remainingBytes)).append(" of ")
            }
            append(Fmt.bytes(item.sizeBytes))
        }
        if (item.speedBps > 0) {
            if (isNotEmpty()) append(" · ")
            append(Fmt.speed(item.speedBps))
        }
        if (item.etaSeconds >= 0 && item.stage == Stages.DOWNLOADING) {
            if (isNotEmpty()) append(" · ")
            append(Fmt.eta(item.etaSeconds)).append(" left")
        }
        if (item.seeds > 0 || item.peers > 0) {
            if (isNotEmpty()) append(" · ")
            append(item.seeds).append("s/").append(item.peers).append("p")
        }
        if (item.indexer.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(item.indexer)
        }
        if (isEmpty()) append(item.protocol.ifEmpty { "—" })
    }

    /**
     * The hub forwards qBittorrent's own state word for a broken transfer, so
     * "missingFiles" and "error" arrive here alongside the hub's own warnings.
     * Both get plain English; anything unrecognised is shown as-is rather than
     * dropped, because an unexplained word beats a silently missing one.
     */
    private fun warningLabel(warning: String): String = when (warning) {
        "no_client_item" -> "no download-client item — the grab never landed"
        "unmatched_download" -> "running, but no matching queue row"
        "usenet_no_client_detail" -> "usenet — no client-level detail"
        "missingFiles" -> "qBittorrent cannot find the files on disk"
        "error" -> "qBittorrent reports an error on this transfer"
        else -> warning
    }

    private fun stageColor(stage: String): Int = when (stage) {
        Stages.DOWNLOADING -> colors.accent
        Stages.IMPORTING -> colors.badgePending
        Stages.SEEDING, Stages.DONE -> colors.badgeAvailable
        Stages.STUCK -> colors.badgeFailed
        else -> colors.mutedText
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
