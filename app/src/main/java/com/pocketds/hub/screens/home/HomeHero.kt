package com.pocketds.hub.screens.home

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.playback.ResumeRules
import com.pocketds.hub.state.Fmt
import java.util.Locale

/**
 * What the big area at the top of Home says about the focused card.
 *
 * Built first from the card alone, so the hero changes the instant focus moves,
 * and again when the item's details arrive with the runtime and certification
 * the Home rows do not carry.
 */
data class HeroContent(
    val itemId: String,
    val type: String,
    val eyebrow: String,
    val title: String,
    val meta: List<String>,
    val progress: Double,
    val progressLabel: String,
    val playLabel: String,
    /** Hub-relative; empty when there is no artwork at all. */
    val backdrop: String,
    /** Coming-up titles are not in the library yet: Details only. */
    val canPlay: Boolean = true
)

object HomeHero {
    private val jellyfinPoster = Regex("^/v1/img/jf/([0-9a-f]{32})/Primary")
    /** What EpisodeLabel.code writes: "S1E4". */
    private val EPISODE_CODE = Regex("""S\d+E\d+""")

    /** The series behind an episode card's poster, or null when the poster is the episode's own. */
    fun seriesIdFromPoster(poster: String, itemId: String): String? =
        jellyfinPoster.find(poster)?.groupValues?.get(1)?.takeIf { it != itemId }

    /** The row a card came from names the eyebrow. Library rows pass the library's own name. */
    fun eyebrowFor(rowId: String, rowTitle: String): String = when (rowId) {
        "continue" -> "CONTINUE WATCHING"
        "nextup" -> "NEXT UP"
        "latest" -> "RECENTLY ADDED"
        "favourites" -> "FAVOURITE"
        HomeRows.UPCOMING -> "COMING UP"
        else -> rowTitle.uppercase(Locale.ROOT)
    }

    fun from(rowId: String, rowTitle: String, hit: SearchHit, detail: LibraryItem? = null): HeroContent {
        val episode = hit.media.type == "episode"
        // The hub writes an episode's card subtitle as "S1E4 · Title", or just the title when the
        // episode has no numbers; then there is no code, and the title belongs in the facts, once.
        val code = if (episode) hit.subtitle.substringBefore(" · ").trim().takeIf(EPISODE_CODE::matches).orEmpty() else ""
        val episodeTitle = if (!episode) "" else detail?.title?.takeIf(String::isNotBlank)
            ?: if (code.isEmpty()) hit.subtitle.trim() else hit.subtitle.substringAfter(" · ", "").trim()
        val runtime = detail?.runtimeSeconds ?: 0
        val watching = hit.progress > 0 && !ResumeRules.showsWatched(hit.played, hit.progress)
        val meta = listOf(
            episodeTitle,
            (detail?.year ?: hit.media.year).takeIf { it > 0 }?.toString().orEmpty(),
            detail?.officialRating.orEmpty(),
            Fmt.runtime(runtime.toLong()),
            hit.rating.takeIf { it > 0 }?.let { String.format(Locale.US, "★ %.1f", it) }.orEmpty()
        ).filter(String::isNotBlank)
        val left = if (watching && runtime > 0) Fmt.runtime((runtime * (1 - hit.progress)).toLong()).let { "$it left" } else ""
        return HeroContent(
            itemId = hit.jellyfinItemId,
            type = hit.media.type,
            eyebrow = listOf(eyebrowFor(rowId, rowTitle), if (rowId == HomeRows.UPCOMING) hit.subtitle.uppercase(Locale.ROOT) else code)
                .filter(String::isNotBlank).joinToString(" · "),
            title = hit.media.title,
            meta = meta,
            progress = if (watching) hit.progress else 0.0,
            progressLabel = left,
            playLabel = if (watching) "Resume" else "Play",
            backdrop = backdrop(hit, detail),
            canPlay = hit.jellyfinItemId.isNotEmpty()
        )
    }

    /**
     * The hero's artwork for a card, which in Glass is also the page's: hub-
     * relative, empty when there is none. On its own so a row can ask for the
     * page's colours of every card it binds, before focus reaches one.
     */
    fun backdrop(hit: SearchHit, detail: LibraryItem? = null): String {
        // An episode shows its series' backdrop from the first frame: the card's
        // poster is the series' own, so its id is known before any details
        // arrive, and the hero never starts on the still and then swaps.
        val seriesId = if (hit.media.type == "episode") detail?.seriesId?.takeIf(String::isNotBlank)
            ?: seriesIdFromPoster(hit.media.poster, hit.jellyfinItemId) else null
        return when {
            seriesId != null -> "/v1/img/jf/$seriesId/Backdrop"
            else -> detail?.backdrop?.takeIf(String::isNotBlank) ?: hit.media.backdrop.ifBlank { hit.media.poster }
        }
    }
}
