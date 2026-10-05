package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineManifest
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.DashboardParts
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineQueueLabelsTest {

    @Test fun `each state is a chip, only trouble and a finished file in colour`() {
        assertEquals("Downloading" to DashboardParts.Tone.QUIET, OfflineQueueLabels.chip(OfflineState.DOWNLOADING))
        assertEquals("Queued" to DashboardParts.Tone.QUIET, OfflineQueueLabels.chip(OfflineState.QUEUED))
        assertEquals("Paused" to DashboardParts.Tone.QUIET, OfflineQueueLabels.chip(OfflineState.PAUSED))
        assertEquals("Waiting" to DashboardParts.Tone.WAITING, OfflineQueueLabels.chip(OfflineState.WAITING))
        assertEquals("Failed" to DashboardParts.Tone.BAD, OfflineQueueLabels.chip(OfflineState.FAILED))
        assertEquals("Downloaded" to DashboardParts.Tone.GOOD, OfflineQueueLabels.chip(OfflineState.COMPLETE))
    }

    @Test fun `the figures say how much has arrived, and how fast only while it moves`() {
        val moving = row(OfflineState.DOWNLOADING, done = 50L shl 20, total = 200L shl 20, speed = 5L shl 20)
        assertEquals("${Fmt.bytes(50L shl 20)} of ${Fmt.bytes(200L shl 20)} · ${Fmt.speed(5L shl 20)} · ${Fmt.eta(30)} left",
            OfflineQueueLabels.figures(moving))
        val paused = moving.copy(state = OfflineState.PAUSED)
        assertEquals("${Fmt.bytes(50L shl 20)} of ${Fmt.bytes(200L shl 20)}", OfflineQueueLabels.figures(paused))
    }

    @Test fun `nothing arrived yet, or all of it, is the size alone`() {
        assertEquals(Fmt.bytes(200L shl 20), OfflineQueueLabels.figures(row(OfflineState.QUEUED, done = 0, total = 200L shl 20)))
        assertEquals(Fmt.bytes(200L shl 20),
            OfflineQueueLabels.figures(row(OfflineState.COMPLETE, done = 200L shl 20, total = 200L shl 20)))
    }

    private fun row(state: OfflineState, done: Long, total: Long, speed: Long = 0) = OfflineDownload(
        id = "row",
        batchId = "batch",
        userId = "user",
        manifest = OfflineManifest(clientItemKey = "row", item = LibraryItem(id = "item", type = "movie", title = "Zodiac")),
        state = state,
        bytesDownloaded = done,
        totalBytes = total,
        localPath = "",
        error = "",
        attempts = 0,
        speedBytesPerSecond = speed,
        sortOrder = 0,
        updatedAt = 0
    )
}
