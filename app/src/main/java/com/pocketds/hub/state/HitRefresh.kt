package com.pocketds.hub.state

/**
 * Newer copies of cards already on screen: where each one sits and what it
 * becomes, only where something changed.
 *
 * Coming back from playing something, a Library grid still showed the old
 * watched badge and progress bar, because a folder grid only reloaded when it
 * was a search or Favourites. Reloading from page one would also have thrown
 * away a scroll position 150 posters deep. Patching the page the user was on
 * keeps both.
 */
object HitRefresh {
    fun <T> changes(current: List<T>, fresh: List<T>, id: (T) -> String): List<IndexedValue<T>> {
        val index = HashMap<String, Int>(current.size * 2)
        current.forEachIndexed { position, value -> index.putIfAbsent(id(value), position) }
        return fresh.mapNotNull { value ->
            val key = id(value).takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val position = index[key] ?: return@mapNotNull null
            if (current[position] == value) null else IndexedValue(position, value)
        }
    }

    /** The page that loaded [position], given where each page began. */
    fun pageOf(position: Int, pageStarts: Map<Int, Int>): Int? =
        pageStarts.filterValues { it <= position }.maxByOrNull { it.value }?.key
}
