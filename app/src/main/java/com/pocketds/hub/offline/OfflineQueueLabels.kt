package com.pocketds.hub.offline

import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.DashboardParts

/**
 * How a download reads on its row in the Offline manager (#22), as a transfer
 * reads on Activity: the state as a chip ([DashboardParts.chip]) beside the
 * title, then one quiet line of figures. The words used to lead the figures
 * ("Paused · 90.8 MB / 3.9 GB"), in the muted colour of the old look.
 */
object OfflineQueueLabels {

    /**
     * The chip's word and tone: moving, queued and paused quiet, waiting amber
     * (it will try again by itself), failed red, downloaded green.
     */
    fun chip(state: OfflineState): Pair<String, DashboardParts.Tone> = when (state) {
        OfflineState.DOWNLOADING -> "Downloading" to DashboardParts.Tone.QUIET
        OfflineState.QUEUED -> "Queued" to DashboardParts.Tone.QUIET
        OfflineState.PAUSED -> "Paused" to DashboardParts.Tone.QUIET
        OfflineState.WAITING -> "Waiting" to DashboardParts.Tone.WAITING
        OfflineState.FAILED -> "Failed" to DashboardParts.Tone.BAD
        OfflineState.COMPLETE -> "Downloaded" to DashboardParts.Tone.GOOD
    }

    /**
     * "90.8 MB of 3.9 GB · 2.1 MB/s · 4 min left": how much has arrived, and,
     * while it moves, how fast and how long. A finished download is its size.
     */
    fun figures(row: OfflineDownload): String = buildList {
        if (row.state == OfflineState.COMPLETE || row.bytesDownloaded <= 0) add(Fmt.bytes(row.totalBytes))
        else add("${Fmt.bytes(row.bytesDownloaded)} of ${Fmt.bytes(row.totalBytes)}")
        if (row.state == OfflineState.DOWNLOADING && row.speedBytesPerSecond > 0) {
            add(Fmt.speed(row.speedBytesPerSecond))
            add("${Fmt.eta((row.totalBytes - row.bytesDownloaded).coerceAtLeast(0) / row.speedBytesPerSecond)} left")
        }
    }.joinToString(" · ")
}
