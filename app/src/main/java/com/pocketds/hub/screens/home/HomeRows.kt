package com.pocketds.hub.screens.home

import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.ui.EpisodeLabel
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Which Home rows show, in what order, and how their cards are shaped.
 *
 * The default is ordered by how soon you would act on a row: what you are in
 * the middle of, what comes next, then what is new, what you keep, and what is
 * coming. Settings › Home rearranges and hides them, and adds a row of the
 * newest titles from any one library ("From Anime"). A row the order does not
 * name (one a newer hub adds) goes at the end rather than disappearing.
 */
object HomeRows {
    const val UPCOMING = "upcoming"
    val BUILT_IN = listOf("continue", "nextup", "latest", "favourites", UPCOMING)
    val DEFAULT_ORDER = BUILT_IN
    private const val LIBRARY_PREFIX = "library:"

    fun libraryRowId(viewId: String) = LIBRARY_PREFIX + viewId
    fun libraryViewId(rowId: String): String? = rowId.takeIf { it.startsWith(LIBRARY_PREFIX) }?.removePrefix(LIBRARY_PREFIX)
    fun libraryRowTitle(libraryName: String) = "From $libraryName"

    fun builtInTitle(rowId: String): String = when (rowId) {
        "continue" -> "Continue watching"
        "nextup" -> "Next up"
        "latest" -> "Recently added"
        "favourites" -> "Favourites"
        UPCOMING -> "Coming up"
        else -> rowId
    }

    /** A stored order from an older build gains any built-in row it never knew about. */
    fun complete(order: List<String>): List<String> = order.distinct() + BUILT_IN.filter { it !in order }

    fun ordered(rows: List<DiscoverRow>, order: List<String> = DEFAULT_ORDER, hidden: Set<String> = emptySet()): List<DiscoverRow> {
        val byId = rows.associateBy { it.id }
        val named = order.mapNotNull(byId::get)
        val rest = rows.filter { it.id !in order }
        return (named + rest).filter { it.id !in hidden && it.items.isNotEmpty() }
    }

    /** The library rows Home should fetch: named in the order and not hidden. */
    fun wantedLibraries(order: List<String>, hidden: Set<String>): List<String> =
        order.filter { it !in hidden }.mapNotNull(::libraryViewId)

    /**
     * After a partial failure, keep a row the hub could not refresh rather than
     * dropping it; [ordered] then puts it back in its place.
     */
    fun merge(next: List<DiscoverRow>, previous: List<DiscoverRow>): List<DiscoverRow> =
        next + previous.filter { kept -> next.none { it.id == kept.id } }

    /** Things you are partway through show their frame; titles show their poster. */
    fun landscape(rowId: String) = rowId == "continue" || rowId == "nextup"

    /**
     * Coming up: each monitored series or film once, at its next release that
     * is not already on disk, soonest first. The card's subtitle starts with
     * the day ("Fri · S2E6"), which is also its corner tag.
     */
    fun upcoming(items: List<CalendarItem>, today: LocalDate): DiscoverRow {
        val next = items.filter { !it.hasFile }
            .sortedWith(compareBy({ it.date }, { it.at }, { it.episode }))
            .distinctBy { it.media.key.ifBlank { it.id } }
        val hits = next.mapNotNull { item ->
            val date = runCatching { LocalDate.parse(item.date) }.getOrNull() ?: return@mapNotNull null
            val what = if (item.media.type == "series") EpisodeLabel.code(item.season, item.episode).ifBlank { item.releaseType }
                else item.releaseType
            SearchHit(
                media = item.media,
                subtitle = listOf(dayLabel(date, today), what).filter(String::isNotBlank).joinToString(" · "),
                overview = listOf(item.episodeTitle, item.overview).filter(String::isNotBlank).joinToString(" — ")
            )
        }
        return DiscoverRow(id = UPCOMING, title = builtInTitle(UPCOMING), items = hits)
    }

    /** "Today", "Tomorrow", a weekday this week, otherwise "9 Oct". */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (val days = ChronoUnit.DAYS.between(today, date)) {
        0L -> "Today"
        1L -> "Tomorrow"
        in 2L..6L -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        else -> if (days < 0) "Out now" else "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
    }
}
