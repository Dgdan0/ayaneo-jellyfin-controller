package com.pocketds.hub.ui

import android.widget.TextView
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusTone

/**
 * Shows a [StatusMessage], text and colour together.
 *
 * Setting both in one call is the point: screens that set only the text left
 * "Refreshing…" in red after an earlier failure.
 */
fun TextView.showStatus(message: StatusMessage, colors: PocketColors) {
    text = message.text
    setTextColor(
        when (message.tone) {
            StatusTone.NORMAL -> colors.mutedText
            StatusTone.WARNING -> colors.badgePending
            StatusTone.ERROR -> colors.dangerText
        }
    )
}
