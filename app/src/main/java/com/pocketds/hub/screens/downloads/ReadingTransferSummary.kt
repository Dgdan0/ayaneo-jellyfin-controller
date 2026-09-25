package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ReadingDownloadItem

object ReadingTransferSummary {
    fun stageLabel(status: String, failed: Boolean): String = when {
        failed || status == "failed" -> "Needs attention"
        status == "imported" -> "Ready in Library"
        status == "completed" || status == "importing" -> "Downloaded · adding to Library"
        status == "downloading" -> "Downloading"
        status == "queued" -> "Queued"
        status == "retry_pending" -> "Retry available"
        status == "retrying" -> "Retrying"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Status unavailable" }
    }

    fun showProgress(status: String, failed: Boolean): Boolean =
        !failed && status in setOf("downloading", "retrying")

    fun groupLabel(contentType: String): String = when (contentType) {
        "ebook" -> "Books"
        "audiobook" -> "Audiobooks"
        "comic" -> "Comics"
        "manga" -> "Manga"
        else -> "Other reading"
    }

    /** The upstream queue provides content type, but not a target library ID. */
    fun grouped(items: List<ReadingDownloadItem>): List<ReadingDownloadItem> =
        items.sortedWith(compareBy { listOf("ebook", "audiobook", "comic", "manga")
            .indexOf(it.contentType).let { index -> if (index < 0) 4 else index } })

    fun fallback(status: String, failed: Boolean): String = when {
        failed || status == "failed" -> "Needs attention"
        status == "imported" -> "Ready in Library"
        status == "completed" || status == "importing" -> "Adding to Library"
        else -> "Waiting for BookKeeprr"
    }
}
