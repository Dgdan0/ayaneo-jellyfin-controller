package com.pocketds.hub.model

import kotlinx.serialization.Serializable

/**
 * The download queue, normalised by the hub across qBittorrent, Radarr and
 * Sonarr.
 *
 * Same rule as the rest of the wire format: every field has a default, because
 * an installed APK long outlives the hub version it was built against.
 */

@Serializable
data class ArrRef(
    val service: String = "",
    val queueId: Int = 0,
    val movieId: Int = 0,
    val seriesId: Int = 0,
    val tmdbId: Int = 0,
    val tvdbId: Int = 0,
    val trackedDownloadState: String = "",
    val trackedDownloadStatus: String = "",
    /** The *arr's own words for what is wrong. Passed through verbatim. */
    val problem: String = ""
)

@Serializable
data class ActivityItem(
    val id: String = "",
    val title: String = "",
    val mediaTitle: String = "",
    val stage: String = "",
    val progress: Double = 0.0,
    val sizeBytes: Long = 0,
    val remainingBytes: Long = 0,
    val speedBps: Long = 0,
    val uploadBps: Long = 0,
    /** -1 when there is no meaningful estimate, rather than a fake number. */
    val etaSeconds: Long = -1,
    val seeds: Int = 0,
    val peers: Int = 0,
    val protocol: String = "",
    val client: String = "",
    val clientStage: String = "",
    val category: String = "",
    val indexer: String = "",
    val arr: ArrRef? = null,
    val torrentHash: String = "",
    val matchConfidence: String = "none",
    val warnings: List<String> = emptyList(),
    /** Number of Sonarr episode rows represented by this one client transfer. */
    val queueItems: Int = 0,
    /** Computed by the hub from this token's scopes. Never inferred here. */
    val actions: List<String> = emptyList()
) {
    /** The heading. Falls back to the release name when there is no *arr row. */
    val headline: String get() = mediaTitle.ifEmpty { title }

    /** The line under it -- empty when it would just repeat the heading. */
    val subline: String get() = if (mediaTitle.isEmpty()) "" else title

    fun can(action: String): Boolean = actions.contains(action)

    val isBroken: Boolean get() = stage == Stages.STUCK || warnings.isNotEmpty()

    /**
     * Whether this item is moving.
     *
     * Drives the poll interval, so it deliberately counts importing: an import
     * finishes in seconds and is exactly the transition worth catching live.
     */
    val isActive: Boolean
        get() = stage == Stages.DOWNLOADING || stage == Stages.IMPORTING
}

/**
 * The hub's stage vocabulary.
 *
 * Constants rather than an enum because these arrive off the wire and a value
 * from a newer hub has to survive being unknown.
 */
object Stages {
    const val DOWNLOADING = "downloading"
    const val QUEUED = "queued"
    const val SEEDING = "seeding"
    const val IMPORTING = "importing"
    const val STOPPED = "stopped"
    const val STUCK = "stuck"
    const val DONE = "done"

    fun label(stage: String): String = when (stage) {
        DOWNLOADING -> "Downloading"
        QUEUED -> "Queued"
        SEEDING -> "Seeding"
        IMPORTING -> "Importing"
        STOPPED -> "Stopped"
        STUCK -> "Stuck"
        DONE -> "Done"
        // Honest rather than tidy: a stage this build has never heard of is
        // shown as-is, not silently folded into one of the above.
        else -> stage.ifEmpty { "Unknown" }
    }
}

@Serializable
data class ActivitySummary(
    val downloading: Int = 0,
    val queued: Int = 0,
    val seeding: Int = 0,
    val stuck: Int = 0,
    val downSpeedBytes: Long = 0,
    val upSpeedBytes: Long = 0
)

@Serializable
data class ActivityResponse(
    val generatedAt: String = "",
    val summary: ActivitySummary = ActivitySummary(),
    val items: List<ActivityItem> = emptyList(),
    val partial: List<PartialFailure> = emptyList()
) {
    val anyActive: Boolean get() = items.any { it.isActive }
}

/** What a mutating endpoint answers with. Only `ok` is load-bearing. */
@Serializable
data class ActionAck(
    val ok: Boolean = false,
    val action: String = "",
    val deletedFiles: Boolean = false,
    val blocklist: Boolean = false,
    val search: Boolean = false
)
