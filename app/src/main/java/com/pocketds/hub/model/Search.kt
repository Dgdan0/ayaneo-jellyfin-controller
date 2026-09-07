package com.pocketds.hub.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The hub's wire format.
 *
 * Every field has a default. The hub will grow fields and change shapes long
 * after a given APK is installed, and an old build must degrade rather than
 * crash on the first response it does not fully recognise.
 */

@Serializable
data class MediaIds(
    val tmdb: Int = 0,
    val tvdb: Int = 0,
    val imdb: String = ""
)

@Serializable
data class MediaRef(
    val key: String = "",
    val type: String = "movie",
    val title: String = "",
    val year: Int = 0,
    val ids: MediaIds = MediaIds(),
    /** Hub-relative, e.g. /v1/img/tmdb/w342/abc.jpg. Never a TMDB URL. */
    val poster: String = "",
    val backdrop: String = ""
)

/**
 * Availability, as the hub decides it.
 *
 * A string rather than an enum on the wire: an unknown value from a newer hub
 * has to render as something rather than fail to parse, and [Availability]
 * below maps it with a safe fallback.
 */
@Serializable
data class SearchHit(
    val media: MediaRef = MediaRef(),
    val subtitle: String = "",
    val overview: String = "",
    val availability: String = "not_in_library",
    val rating: Double = 0.0,
    @SerialName("jellyfinItemId") val jellyfinItemId: String = "",
    val progress: Double = 0.0,
    val eta: String = "",
    @SerialName("requestId") val requestId: Int = 0,
    /** Computed by the hub from availability and this token's scopes. */
    val actions: List<String> = emptyList()
) {
    val canRequest: Boolean get() = actions.contains("request")
    val canPlay: Boolean get() = actions.contains("play")
}

@Serializable
data class CacheInfo(
    val hit: Boolean = false,
    val ageSeconds: Int = 0,
    val stale: Boolean = false,
    val degraded: Boolean = false
)

@Serializable
data class PartialFailure(
    val service: String = "",
    val reason: String = "",
    val message: String = ""
)

@Serializable
data class SearchResponse(
    val query: String = "",
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val results: List<SearchHit> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ServiceHealth(
    val name: String = "",
    val state: String = "",
    val latencyMs: Long = 0,
    val version: String = "",
    val lastError: String = ""
)

@Serializable
data class HubInfo(
    val version: String = "",
    val uptimeSeconds: Long = 0,
    val tokenCount: Int = 0
)

@Serializable
data class HealthResponse(
    val hub: HubInfo = HubInfo(),
    val services: List<ServiceHealth> = emptyList()
) {
    val upCount: Int get() = services.count { it.state == "up" }
    val problems: List<ServiceHealth>
        get() = services.filter { it.state == "down" || it.state == "misconfigured" }
}

/**
 * The badge on a card. Pure mapping, with an explicit unknown case so a value
 * from a newer hub shows as something honest rather than as "not in library".
 */
enum class Availability(val wire: String, val label: String) {
    NOT_IN_LIBRARY("not_in_library", ""),

    /** Asked for, waiting on approval. */
    REQUESTED("requested", "Requested"),

    /**
     * Approved, and Radarr or Sonarr is working on it -- but nothing is moving
     * yet. "Processing" described the software rather than the thing you asked
     * for; this says what it means for you.
     */
    PROCESSING("processing", "On the way"),

    /** Bytes actually moving. The card also draws a progress bar. */
    DOWNLOADING("downloading", "Downloading"),
    PARTIAL("partially_available", "Partial"),
    AVAILABLE("available", "In library"),
    BLOCKED("blocked", "Blocked"),
    DELETED("deleted", "Deleted"),
    UNKNOWN("unknown", "");

    companion object {
        fun fromWire(value: String): Availability =
            entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** One step of the journey from "I want this" to "it is in the library". */
@Serializable
data class Stage(
    val id: String = "",
    val label: String = "",
    /** One or two words, for the horizontal strip. Falls back to [label]. */
    val short: String = "",
    val state: String = "pending",
    val detail: String = "",
    val source: String = "",
    val progress: Double = 0.0,
    val degraded: Boolean = false
) {
    val compactLabel: String get() = short.ifEmpty { label }

    /**
     * The glyph in front of the label.
     *
     * "stuck" and "unknown" are deliberately distinct from "pending": a step
     * that failed and a step we could not ask about need different reactions,
     * and showing both as an empty box is how an app quietly lies.
     */
    val glyph: String
        get() = when (state) {
            "done" -> "✓"
            "active" -> "▸"
            "failed" -> "✕"
            "stuck" -> "!"
            "unknown" -> "?"
            else -> "·"
        }
}

@Serializable
data class Pipeline(
    val summary: String = "",
    val stages: List<Stage> = emptyList()
)

@Serializable
data class MediaDetail(
    val media: MediaRef = MediaRef(),
    val overview: String = "",
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val rating: Double = 0.0,
    val seasons: Int = 0,
    val episodes: Int = 0,
    /**
     * The seasons themselves, for a series.
     *
     * Sent with the detail so the release picker can ask which season without a
     * second round trip: Sonarr has no "search the whole series" call, a season
     * number is required.
     */
    val seasonList: List<SeasonOption> = emptyList(),
    /** YouTube watch URL, or empty when TMDB knows of no trailer. */
    val trailerUrl: String = "",
    /** The bare video id, which is what the embedded player needs. */
    val trailerKey: String = "",
    val availability: String = "not_in_library",
    @SerialName("jellyfinItemId") val jellyfinItemId: String = "",
    val cast: List<CastMember> = emptyList(),
    val pipeline: Pipeline = Pipeline(),
    val actions: List<String> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
) {
    val canRequest: Boolean get() = actions.contains("request")
}

@Serializable
data class CreateRequestBody(
    val key: String,
    /**
     * The string "all", or a list of season numbers. A JsonElement because the
     * hub accepts both shapes and Jellyseerr rejects a series request that
     * names no seasons at all.
     */
    val seasons: kotlinx.serialization.json.JsonElement? = null,
    /**
     * Omitted rather than zero when the user did not choose. Jellyseerr reads a
     * profileId of 0 as an invalid profile, not as "use the default".
     */
    val profileId: Int? = null,
    val rootFolder: String? = null,
    val serverId: Int? = null,
    val is4k: Boolean = false
)

@Serializable
data class CreateRequestResponse(
    val requestId: Int = 0,
    val state: String = "",
    val message: String = "",
    val availability: String = "",
    val pipeline: Pipeline = Pipeline()
)

/** The hub's error envelope, so a refusal can be shown in its own words. */
@Serializable
data class HubErrorBody(
    val error: HubErrorDetail = HubErrorDetail(),
    val requestId: String = ""
)

@Serializable
data class HubErrorDetail(
    val code: String = "",
    val service: String = "",
    val message: String = "",
    val retryable: Boolean = false
)

@Serializable
data class CastMember(
    val id: Int = 0,
    val name: String = "",
    val character: String = "",
    val profile: String = ""
)

@Serializable
data class PersonResponse(
    val id: Int = 0,
    val name: String = "",
    val profile: String = "",
    val biography: String = "",
    val knownFor: String = "",
    val credits: List<SearchHit> = emptyList(),
    val sortedBy: String = "release",
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)
