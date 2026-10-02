package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.ui.EpisodeLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * How the calendar reads: weeks, days, releases grouped per title and day,
 * and whether each has arrived. Upcoming and the Activity dashboard both use
 * it. Pure, so tested.
 */
object UpcomingPresentation {
    data class Range(val start: LocalDate, val endExclusive: LocalDate)

    data class Group(val items: List<CalendarItem>) {
        val first get() = items.first()
        val id get() = items.joinToString("|") { it.id }
        val label get() = if (first.media.type != "series") first.releaseType
            else if (items.size > 1) "Season ${first.season} · ${items.size} episodes"
            else EpisodeLabel.code(first.season, first.episode)
    }

    /** Whether a release has arrived. A day's grace before "Missing": Sonarr has barely looked. */
    enum class ReleaseState(val label: String) {
        MISSING("Missing"),
        AIRED("Aired"),
        IN_LIBRARY("In library"),
        SOON("Soon")
    }

    fun state(item: CalendarItem, now: Instant, zone: ZoneId): ReleaseState {
        if (item.hasFile) return ReleaseState.IN_LIBRARY
        val at = runCatching { Instant.parse(item.at) }.getOrNull()
            ?: runCatching { LocalDate.parse(item.date).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: return ReleaseState.SOON
        return when {
            at.isAfter(now) -> ReleaseState.SOON
            ChronoUnit.HOURS.between(at, now) < 24 -> ReleaseState.AIRED
            else -> ReleaseState.MISSING
        }
    }

    /** A day's episodes of one series: in library only when all are; otherwise the first one not yet here. */
    fun state(group: Group, now: Instant, zone: ZoneId): ReleaseState =
        group.items.firstOrNull { !it.hasFile }?.let { state(it, now, zone) } ?: ReleaseState.IN_LIBRARY

    fun range(today: LocalDate, week: Int): Range {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week.toLong())
        return Range(monday, monday.plusWeeks(1))
    }

    /** "28 Sep – 4 Oct", "5 – 11 Oct", or with years when it crosses one. */
    fun rangeLabel(range: Range): String {
        val start = range.start
        val end = range.endExclusive.minusDays(1)
        return when {
            start.year != end.year -> "${start.format(DAY_MONTH_YEAR)} – ${end.format(DAY_MONTH_YEAR)}"
            start.month == end.month -> "${start.dayOfMonth} – ${end.format(DAY_MONTH)}"
            else -> "${start.format(DAY_MONTH)} – ${end.format(DAY_MONTH)}"
        }
    }

    /** The week switch: "Last week", "This week · 28 Sep – 4 Oct", "Next week · 5 – 11 Oct". */
    fun weekLabel(today: LocalDate, week: Int): String = when (week) {
        -1 -> "Last week"
        0 -> "This week · " + rangeLabel(range(today, 0))
        1 -> "Next week · " + rangeLabel(range(today, 1))
        else -> rangeLabel(range(today, week))
    }

    fun days(range: Range): List<LocalDate> =
        generateSequence(range.start) { it.plusDays(1) }.takeWhile { it < range.endExclusive }.toList()

    fun groups(items: List<CalendarItem>): List<Group> =
        items.groupBy {
            if (it.media.type == "series" && it.media.key.isNotBlank()) "${it.date}:${it.media.key}:${it.season}"
            else it.id
        }.values.map { Group(it.sortedWith(compareBy({ it.episode }, { it.id }))) }
            .sortedWith(compareBy({ it.first.date }, { it.items.map { e -> e.at }.filter { t -> t.isNotBlank() }.minOrNull() ?: "~" }, { it.first.media.title }))

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
}
