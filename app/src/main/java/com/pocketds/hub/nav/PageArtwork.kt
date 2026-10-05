package com.pocketds.hub.nav

import com.pocketds.hub.net.HubEndpoints

/**
 * Which artwork the Glass page shows (GLASS_PLAN.md, #11).
 *
 * The screen in front reports its artwork ([Screen.pageArtwork]): Home the focused
 * card, a title page its backdrop, Books Home the cover in focus. A screen that
 * reports none keeps the page as it is, so Settings or a dashboard stays the
 * colour of whatever you were just looking at instead of going grey.
 */
object PageArtwork {

    /** The artwork to switch the page to, or null to leave the page as it is. */
    fun next(shown: String?, reported: String?): String? =
        reported?.takeIf { it.isNotBlank() && it != shown }

    /**
     * A title page's artwork: its backdrop, else an episode's still, else its
     * poster; the picture across the top of the page when there is one.
     */
    fun title(backdrop: String, poster: String, still: String = ""): String? =
        listOf(backdrop, still, poster).firstOrNull(String::isNotBlank)

    /**
     * A title's backdrop by id alone: an episode's series' backdrop, else the
     * title's own. What is playing, and a downloaded title whose page has no
     * fresher path, ask for it this way, so the same picture is one string and
     * one entry in the colour caches whichever of them showed it first (#22).
     */
    fun backdrop(itemId: String, seriesId: String = ""): String? =
        seriesId.ifBlank { itemId }.takeIf(String::isNotBlank)?.let { HubEndpoints.jellyfinImage(it, "Backdrop") }
}
