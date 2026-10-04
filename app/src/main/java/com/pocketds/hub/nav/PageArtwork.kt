package com.pocketds.hub.nav

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
}
