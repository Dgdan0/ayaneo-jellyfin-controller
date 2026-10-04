package com.pocketds.hub.screens.downloads

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.model.ReadingDownloadItem
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.textWeight

/**
 * A BookKeeprr transfer without exposing torrent or indexer identities, drawn
 * like a media transfer (DownloadRowView): a tile for what it is doing, the
 * book's title, a slim bar while it moves, and one line in words. It used to
 * lead with a "READY IN LIBRARY" chip on every row.
 *
 * On the Glass page it is the media transfer's glass row (the prototype's
 * `.trow`): 13dp glass with the white ring on focus, the state as a chip by
 * the title, the bar white, and a kind's heading (BOOKS, COMICS) above its
 * first row rather than inside it.
 */
class ReadingDownloadRowView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {
    private val category: TextView
    private val tile: TextView
    private val title: TextView
    private val trailing: TextView
    private val progress: ProgressBar
    private val line: TextView
    private val release: TextView
    private val glass = com.pocketds.hub.ui.Theme.onGlass(colors)
    /** Glass: the state, as a chip beside the title. */
    private val chipSlot = android.widget.FrameLayout(context)

    init {
        orientation = VERTICAL
        if (!glass) {
            background = Styler.cardBackground(context, colors, cornerDp = 16f)
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }
        Styler.makeFocusable(this)

        category = TextView(context).apply {
            textSize = if (glass) 12f else 10.5f
            textWeight(if (glass) 800 else 700)
            letterSpacing = if (glass) 0.12f else 0.1f
            setTextColor(if (glass) GLASS_GROUP else colors.mutedText)
            setPadding(if (glass) dp(4) else 0, if (glass) dp(10) else 0, 0, dp(8))
            visibility = GONE
        }
        addView(category)

        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (glass) row.apply {
            // The card is the row; the heading above it stays on the page.
            com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, GLASS_CORNER_DP))
            foreground = Styler.focusOutline(context, colors, GLASS_CORNER_DP, 3f)
            isDuplicateParentStateEnabled = true
            setPadding(dp(10), dp(8), dp(12), dp(8))
        }
        addView(row, LayoutParams(MATCH, WRAP))
        tile = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            textWeight(700)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(tile, if (glass) LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) } else LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })

        val words = LinearLayout(context).apply { orientation = VERTICAL }
        row.addView(words, LayoutParams(0, WRAP, 1f))
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        words.addView(header, LayoutParams(MATCH, WRAP))
        title = TextView(context).apply {
            textSize = if (glass) 12.5f else 14f
            textWeight(if (glass) 700 else 600)
            setTextColor(colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LayoutParams(0, WRAP, 1f))
        if (glass) header.addView(chipSlot, LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        trailing = TextView(context).apply {
            textSize = 12f
            textWeight(600)
            setTextColor(colors.mutedText)
        }
        header.addView(trailing, LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
        progress = ProgressLine.create(context, colors)
        words.addView(progress, LayoutParams(MATCH, dp(5)).apply { topMargin = dp(if (glass) 5 else 7) })
        line = TextView(context).apply {
            textSize = if (glass) 11f else 11.5f
            setTextColor(if (glass) com.pocketds.hub.ui.glass.GlassColors.QUIET else colors.mutedText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        words.addView(line, LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        release = TextView(context).apply {
            textSize = 10.5f
            setTextColor(colors.mutedText)
            alpha = .8f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        words.addView(release, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
    }

    fun bind(item: ReadingDownloadItem, sectionStart: Boolean = false) {
        category.text = ReadingTransferSummary.groupLabel(item.contentType).uppercase()
        category.visibility = if (sectionStart) VISIBLE else GONE
        val color = if (item.failed) colors.badgeFailed else when (item.status) {
            "downloading" -> colors.accent
            "importing" -> colors.badgePending
            "completed", "imported" -> colors.badgeAvailable
            else -> colors.mutedText
        }
        tile.text = when {
            item.failed -> "!"
            item.status == "downloading" -> "↓"
            item.status == "importing" -> "⇢"
            item.status == "completed" || item.status == "imported" -> "✓"
            else -> "…"
        }
        tile.setTextColor(color)
        tile.background = ThemeGradientDrawable.rounded(dp(if (glass) 9 else 12).toFloat(), ColorUtils.setAlphaComponent(color, 0x2E))
        if (glass) {
            chipSlot.removeAllViews()
            chipSlot.addView(com.pocketds.hub.ui.DashboardParts.chip(context, colors,
                ReadingTransferSummary.chipLabel(item.status, item.failed), tone(item)))
        }

        title.text = item.title
        release.text = item.releaseTitle
        release.visibility = if (item.releaseTitle.isBlank() || item.releaseTitle == item.title) GONE else VISIBLE
        val moving = ReadingTransferSummary.showProgress(item.status, item.failed)
        trailing.text = if (moving && item.progressPercent > 0) "${item.progressPercent}%" else ""
        progress.progress = (item.progressPercent.coerceIn(0, 100) * ProgressLine.MAX) / 100
        // Glass: the bar is white, as every bar on the glass page; the chip says the state.
        progress.progressTintList = ColorStateList.valueOf(if (glass) android.graphics.Color.WHITE else color)
        progress.visibility = if (moving) VISIBLE else GONE
        val stage = ReadingTransferSummary.stageLabel(item.status, item.failed)
        line.text = buildList {
            // Glass: the chip names the state; the line says it in full only when there is nothing else.
            if (!glass) add(stage)
            if (item.sizeBytes > 0) add(Fmt.bytes(item.sizeBytes))
            if (item.downloadSpeedBytesPerSecond > 0) add(Fmt.speed(item.downloadSpeedBytesPerSecond))
            if (item.etaSeconds > 0 && item.isActive) add(Fmt.eta(item.etaSeconds) + " left")
            if (glass && isEmpty()) add(stage)
            ReadingTransferSummary.fallback(item.status, item.failed).takeIf { size == 1 && !it.equals(stage, ignoreCase = true) }?.let(::add)
        }.joinToString(" · ")
        contentDescription = listOfNotNull(item.title, ReadingTransferSummary.chipLabel(item.status, item.failed).takeIf { glass }, line.text)
            .joinToString(", ")
    }

    /** Glass: the chip's colour, as a media transfer's: failed red, in the library green, on its way amber. */
    private fun tone(item: ReadingDownloadItem) = when {
        item.failed || item.status == "failed" -> com.pocketds.hub.ui.DashboardParts.Tone.BAD
        item.status == "imported" -> com.pocketds.hub.ui.DashboardParts.Tone.GOOD
        item.status in setOf("completed", "importing", "retry_pending") -> com.pocketds.hub.ui.DashboardParts.Tone.WAITING
        else -> com.pocketds.hub.ui.DashboardParts.Tone.QUIET
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Glass, the prototype's transfer row: 13dp corners; a kind's heading (`.gh`) in white at 55%. */
        const val GLASS_CORNER_DP = 13f
        const val GLASS_GROUP = 0x8CFFFFFF.toInt()
    }
}
