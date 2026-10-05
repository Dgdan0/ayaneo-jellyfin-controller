package com.pocketds.hub.screens.downloads

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.Stages
import com.pocketds.hub.state.Fmt
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.textWeight

/**
 * One transfer, as the redesign's queue row: a tile saying what it is doing,
 * the title in words, a slim bar, and one quiet line of figures.
 *
 * Full width rather than a card in a grid, because the useful information here
 * is all text and the interesting part -- why something is stuck -- is a
 * sentence, not a number.
 *
 * It is the prototype's row (`.trow`): glass with 13dp corners and the white
 * ring on focus, the stage as a state chip by the title
 * ([DashboardParts.chip]) and the bar in white.
 */
class DownloadRowView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {

    private val tile: TextView
    private val titleView: TextView
    private val trailing: TextView
    private val bar: ProgressBar
    private val stats: TextView
    private val release: TextView
    private val problem: TextView
    /** The stage, as a chip beside the title. */
    private val chipSlot = android.widget.FrameLayout(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, CORNER_DP))
        foreground = Styler.focusOutline(context, colors, CORNER_DP, 3f)
        setPadding(dp(10), dp(8), dp(12), dp(8))
        Styler.makeFocusable(this)

        tile = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            textWeight(700)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(tile, LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })

        val words = LinearLayout(context).apply { orientation = VERTICAL }
        addView(words, LayoutParams(0, WRAP, 1f))
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        words.addView(header, LayoutParams(MATCH, WRAP))
        titleView = TextView(context).apply {
            textSize = 12.5f
            textWeight(700)
            setTextColor(colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(titleView, LayoutParams(0, WRAP, 1f))
        header.addView(chipSlot, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        trailing = TextView(context).apply {
            textSize = 12f
            textWeight(600)
            setTextColor(colors.mutedText)
            textDirection = TEXT_DIRECTION_LTR
        }
        header.addView(trailing, LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })

        bar = ProgressLine.create(context, colors)
        words.addView(bar, LayoutParams(MATCH, dp(5)).apply { topMargin = dp(5) })

        stats = TextView(context).apply {
            textSize = 11f
            setTextColor(com.pocketds.hub.ui.SettingsCard.GLASS_QUIET)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(stats, LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })

        // The release name under an *arr title: what qBittorrent calls it.
        release = TextView(context).apply {
            textSize = 10.5f
            setTextColor(colors.mutedText)
            alpha = .8f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        words.addView(release, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })

        problem = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.dangerText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = GONE
        }
        words.addView(problem, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
    }

    fun bind(item: ActivityItem) {
        val stageColor = stageColor(item.stage)
        tile.text = glyph(item.stage)
        tile.setTextColor(stageColor)
        tile.background = ThemeGradientDrawable.rounded(dp(9).toFloat(), ColorUtils.setAlphaComponent(stageColor, 0x2E))
        chipSlot.removeAllViews()
        chipSlot.addView(com.pocketds.hub.ui.DashboardParts.chip(context, Stages.label(item.stage), stageTone(item.stage)))

        titleView.text = item.headline
        release.text = item.subline
        release.visibility = if (item.subline.isEmpty()) GONE else VISIBLE

        // A seeding torrent sits at 100% forever; showing its ratio-free percent
        // is noise, so the trailing slot carries the upload rate instead.
        trailing.text = when {
            item.stage == Stages.SEEDING -> "↑ " + Fmt.speed(item.uploadBps)
            item.progress > 0 -> Fmt.percent(item.progress)
            else -> ""
        }

        bar.progress = (item.progress * ProgressLine.MAX).toInt().coerceIn(0, ProgressLine.MAX)
        // The bar is white, as every progress bar on the glass page; its stage is the chip.
        bar.progressTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
        bar.visibility = if (item.stage == Stages.STUCK && item.progress <= 0.0) GONE else VISIBLE

        stats.text = statsLine(item).takeIf { it != "—" }.orEmpty()

        // The *arr's own words, verbatim. Paraphrasing "Found executable file
        // with extension: '.exe'" into "import failed" would have hidden the
        // single most useful thing this screen has ever said.
        val note = buildString {
            item.diagnosis?.takeIf { it.needsAttention }?.title?.takeIf(String::isNotBlank)?.let { append(it) }
            item.arr?.problem?.takeIf { it.isNotEmpty() && !contains(it) }?.let { if(isNotEmpty()) append(" · "); append(it) }
            for (warning in item.warnings) {
                if (isNotEmpty()) append(" · ")
                append(warningLabel(warning))
            }
        }
        contentDescription = listOf(item.headline, stats.text, note).filter { it.isNotEmpty() }.joinToString(", ")
        problem.text = note
        problem.visibility = if (note.isEmpty()) GONE else VISIBLE
    }

    /** What the tile says at a glance. */
    private fun glyph(stage: String): String = when (stage) {
        Stages.DOWNLOADING -> "↓"
        Stages.SEEDING -> "↑"
        Stages.IMPORTING -> "⇢"
        Stages.STUCK -> "!"
        Stages.DONE -> "✓"
        Stages.STOPPED -> "‖"
        else -> "…"
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

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

    /** The stage's [DashboardParts.chip] tone: moving or waiting quiet, importing amber, done green, stuck red. */
    private fun stageTone(stage: String): com.pocketds.hub.ui.DashboardParts.Tone = when (stage) {
        Stages.IMPORTING -> com.pocketds.hub.ui.DashboardParts.Tone.WAITING
        Stages.SEEDING, Stages.DONE -> com.pocketds.hub.ui.DashboardParts.Tone.GOOD
        Stages.STUCK -> com.pocketds.hub.ui.DashboardParts.Tone.BAD
        else -> com.pocketds.hub.ui.DashboardParts.Tone.QUIET
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
        /** The prototype's Pocket transfer row corner. */
        const val CORNER_DP = 13f
    }
}
