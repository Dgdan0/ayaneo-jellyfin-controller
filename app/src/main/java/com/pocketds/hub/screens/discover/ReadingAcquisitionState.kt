package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingDownloadItem

/** Presents a request's current transfer state, rather than its original POST confirmation. */
data class ReadingAcquisitionState(val stage: Stage, val message: String) {
    enum class Stage { AWAITING_CHOICE, SEARCHING, QUEUED, DOWNLOADING, IMPORTING, IMPORTED, FAILED }

    val terminal: Boolean get() = stage == Stage.IMPORTED || stage == Stage.FAILED
    val nextAction: String get() = when (stage) {
        Stage.AWAITING_CHOICE -> "Choose release"
        Stage.SEARCHING, Stage.QUEUED, Stage.DOWNLOADING, Stage.IMPORTING, Stage.FAILED -> "Open Transfers"
        Stage.IMPORTED -> "Open Library"
    }

    companion object {
        /** Existing requests predate the saved series id; do not guess when titles collide. */
        fun findSeriesId(title: String, author: String, contentType: String, downloads: List<ReadingDownloadItem>): Int {
            if (author.isBlank()) return 0
            val ids = downloads.filter {
                it.title.equals(title, ignoreCase = true) &&
                    it.contentType.equals(contentType, ignoreCase = true) &&
                    it.releaseTitle.contains(author.trim(), ignoreCase = true) && it.seriesId > 0
            }.map { it.seriesId }.distinct()
            return ids.singleOrNull() ?: 0
        }

        fun from(seriesId: Int, downloads: List<ReadingDownloadItem>, manualSelection: Boolean = false): ReadingAcquisitionState {
            val rows = downloads.filter { seriesId > 0 && it.seriesId == seriesId }
            if (rows.isEmpty() && manualSelection) return ReadingAcquisitionState(Stage.AWAITING_CHOICE,
                "Choose a release to start downloading.")
            if (rows.isEmpty()) return ReadingAcquisitionState(Stage.SEARCHING,
                "Request accepted. No transfer has started yet. Open Transfers for the latest status.")
            if (rows.any { it.failed || it.status == "failed" })
                return ReadingAcquisitionState(Stage.FAILED, "Download needs attention in Transfers")
            if (rows.all { it.status == "imported" })
                return ReadingAcquisitionState(Stage.IMPORTED, "Imported · check Library for your book")
            val downloading = rows.firstOrNull { it.status == "downloading" }
            if (downloading != null) return ReadingAcquisitionState(
                Stage.DOWNLOADING,
                "Downloading · ${downloading.progressPercent.coerceIn(0, 100)}%"
            )
            if (rows.any { it.status == "importing" || it.status == "completed" })
                return ReadingAcquisitionState(Stage.IMPORTING, "Downloaded · adding to Library")
            return ReadingAcquisitionState(Stage.QUEUED, "Queued in Transfers")
        }
    }
}
