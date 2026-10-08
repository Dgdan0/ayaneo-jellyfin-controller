package com.pocketds.hub.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.offline.StorageBar
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * The bar at the foot of a series page (#48): the [storage] bar, which rises as a download starts and fades about
 * three seconds after the last finishes, and, in select mode, a line of what is ticked beside the Download pill.
 * A tap on the bar goes to Downloads. Drawn over the page on a glass panel of the page's own colours.
 */
class DownloadDockView(context: Context, colors: PocketColors, ringVisible: () -> Boolean) : LinearLayout(context) {
    val storage = StorageBarView(context, colors)
    private val sizeLine = TextView(context).apply {
        textSize = 12.5f; textWeight(600); setTextColor(colors.primaryText); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    /** Select mode's Download: a pill to tap, and Start on the pad. */
    val download: TextView = PillButton.create(context, colors, "Download", AppIcon.DOWNLOAD, primary = true, heightDp = 31f)
    private val selectRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
    var onDownload: (() -> Unit)? = null
    var onOpenDownloads: (() -> Unit)? = null
    private var visibleNow = false

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(12))
        GlassPanelDrawable.attach(this, dp(16).toFloat())
        selectRow.addView(sizeLine, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        selectRow.addView(download, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = -dp(PillButton.RING_DP.toInt()) })
        addView(selectRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        addView(storage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
        download.isFocusable = false
        FocusDecorator.attach(download, ringVisible, scale = false)
        download.activateOnTap { onDownload?.invoke() }
        // The bar is a link to Downloads, unless the select row is up and the pill is what is wanted.
        storage.isClickable = true
        storage.setOnClickListener { if (selectRow.visibility != VISIBLE) onOpenDownloads?.invoke() }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Whether the bar is up (or on its way up). */
    val raised: Boolean get() = visibleNow

    /** The select row: [text] is what is ticked, [ready] whether Download can be pressed yet. */
    fun selecting(text: String?, ready: Boolean = true) {
        selectRow.visibility = if (text == null) GONE else VISIBLE
        if (text != null) {
            sizeLine.text = text
            download.alpha = if (ready) 1f else .5f
            download.isEnabled = ready
        }
    }

    /** Rises from the foot of the page, or fades away. */
    fun show(visible: Boolean) {
        if (visible == visibleNow) return
        visibleNow = visible
        animate().cancel()
        if (visible) {
            visibility = VISIBLE
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                alpha = 0f; translationY = dp(24).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(220).start()
            } else { alpha = 1f; translationY = 0f }
        } else if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            animate().alpha(0f).setDuration(380).withEndAction { if (!visibleNow) visibility = GONE }.start()
        } else visibility = GONE
    }

    fun bind(model: StorageBar.Model, line: String) = storage.bind(model, line)

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

}
