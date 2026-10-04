package com.pocketds.hub.screens.library

import com.pocketds.hub.model.LibraryView

/**
 * The Glass Library root's tiles (GLASS_PLAN.md, #11 and #13): what each one
 * fans and says. Pure, so the fan's geometry and the counts are tested on the
 * JVM; `LibraryTileView` draws them.
 */
object LibraryTiles {
    /**
     * Where a poster of the fan sits in its stack: [fromEnd] of the stack's
     * width in from its right edge, turned [degrees] clockwise.
     */
    data class Slot(val fromEnd: Float, val degrees: Float)

    /**
     * The prototype's stack, back to front: the day's pick at the right and
     * behind, turned a little clockwise; the next two lean in front of it to
     * the left. A library of one title is the back poster alone.
     */
    private val STACK = listOf(Slot(0f, 7f), Slot(.26f, -1f), Slot(.52f, -8f))

    fun slots(count: Int): List<Slot> = STACK.take(count.coerceIn(0, STACK.size))

    /**
     * The posters to fan, the hub's order kept. A hub from before #13 sends
     * none, and the tile shows the library's own picture instead: fetching a
     * page of titles for five tiles would cost more than the root is worth.
     */
    fun fan(view: LibraryView): List<String> = view.fan.filter(String::isNotBlank).take(STACK.size)

    /** "5 libraries · 249 titles"; the titles left out when the hub does not count them. */
    fun summary(views: List<LibraryView>): String {
        val libraries = "${views.size} ${if (views.size == 1) "library" else "libraries"}"
        val titles = views.sumOf { it.total }
        return if (titles <= 0) libraries else "$libraries · $titles ${if (titles == 1) "title" else "titles"}"
    }

    /**
     * The line under "Your reading libraries": how many, and the servers they
     * come from, as the prototype says it ("3 libraries from Storyteller and
     * Kavita, and Kavita's reading lists").
     */
    fun readingSummary(libraries: List<com.pocketds.hub.model.ReadingLibrary>): String {
        val shelves = libraries.filter { it.kind != "reading_list" }
        val servers = shelves.map { it.source.lowercase() }.distinct()
            .map { com.pocketds.hub.model.ServiceNames.display(it) }
        val count = "${shelves.size} ${if (shelves.size == 1) "library" else "libraries"}"
        val from = when (servers.size) {
            0 -> ""
            1 -> " from ${servers[0]}"
            else -> " from ${servers.dropLast(1).joinToString(", ")} and ${servers.last()}"
        }
        // The hub lists Kavita's reading lists only as a capability; the screen adds their tile.
        val lists = if (servers.any { it == "Kavita" }) ", and Kavita's reading lists" else ""
        return count + from + lists
    }

    /** The small capitals over a Books library tile's name, from the hub's reading kind. */
    fun readingKindLabel(kind: String): String = when (kind) {
        "comic" -> "Comics"
        "manga" -> "Manga"
        "reading_list" -> "Kavita"
        else -> "Books & audio"
    }

    /** The small capitals over a tile's name, from Jellyfin's collection type. */
    fun kindLabel(kind: String): String = when (kind) {
        "movies" -> "Movie library"
        "tvshows" -> "TV library"
        "boxsets" -> "Collections"
        "homevideos" -> "Home videos"
        "music" -> "Music library"
        else -> "Library"
    }
}
