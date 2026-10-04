package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.HostDisk
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.ServiceHealth
import com.pocketds.hub.model.ServiceNames
import com.pocketds.hub.model.Stages
import com.pocketds.hub.screens.discover.UpcomingPresentation
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.EpisodeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Activity dashboard says, decided away from the views.
 *
 * The page answers "is anything wrong, what is moving, what is coming, and is
 * every service up" in one glance, so each card is a short, ordered selection
 * from a larger feed: the full transfer list, calendar and service screens are
 * one press away.
 */
object ActivityDashboard {
    /** "1 transfer needs attention", "3 transfers need attention". */
    fun needAttention(count: Int): String =
        if (count == 1) "1 transfer needs attention" else "$count transfers need attention"


    /**
     * The line under Activity's heading on Glass: "Nothing downloading · 1
     * thing needs attention · all 11 services up". A part not yet loaded says
     * nothing rather than a guess.
     */
    fun headline(activity: ActivityResponse?, attention: Int, health: HealthResponse?): String = listOfNotNull(
        activity?.let { body ->
            val moving = body.items.count { it.stage == Stages.DOWNLOADING && !it.isBroken }
            if (moving == 0) "Nothing downloading" else "$moving downloading"
        },
        attention.takeIf { it > 0 }?.let { if (it == 1) "1 thing needs attention" else "$it things need attention" },
        health?.let { value ->
            val shown = value.services.filter { it.state != "disabled" }
            when (val notUp = shown.count { it.state != "up" }) {
                0 -> "all ${shown.size} services up"
                1 -> "1 service not responding"
                else -> "$notUp services not responding"
            }
        }
    ).joinToString(" · ")

    /** Under this share free, a disk is worth a warning. The server monitor uses it too. */
    const val LOW_SPACE_FRACTION = 0.10

    fun lowSpace(disk: HostDisk): Boolean =
        disk.totalBytes > 0 && disk.availableBytes.toDouble() / disk.totalBytes < LOW_SPACE_FRACTION

    /** The transfers worth a row: moving or waiting, not seeding or finished, not broken (those are listed as problems). */
    fun transfers(activity: ActivityResponse, limit: Int = 3): List<ActivityItem> =
        activity.items.filter { !it.isBroken && it.stage in MOVING }.take(limit)

    private val MOVING = setOf(Stages.DOWNLOADING, Stages.IMPORTING, Stages.QUEUED, Stages.STOPPED)

    /** "1.2 of 1.9 GB · 4m left", or what the transfer is waiting on. */
    fun transferLine(item: ActivityItem): String = buildString {
        if (item.sizeBytes > 0) {
            if (item.remainingBytes in 1 until item.sizeBytes) append(Fmt.bytes(item.sizeBytes - item.remainingBytes)).append(" of ")
            append(Fmt.bytes(item.sizeBytes))
        }
        val tail = when {
            item.stage == Stages.DOWNLOADING && item.etaSeconds >= 0 -> Fmt.eta(item.etaSeconds) + " left"
            item.stage == Stages.DOWNLOADING -> Fmt.speed(item.speedBps)
            else -> Stages.label(item.stage)
        }
        if (isNotEmpty()) append(" · ")
        append(tail)
    }

    data class Attention(
        val id: String,
        val title: String,
        val detail: String,
        /** Set for a transfer, whose page explains what is wrong. */
        val transferId: String = ""
    )

    /** Broken transfers first, then services that are down, then nearly full disks. */
    fun attention(activity: ActivityResponse?, health: HealthResponse?, disks: List<HostDisk>): List<Attention> {
        val transfers = activity?.items.orEmpty().filter { it.isBroken }.map {
            Attention(
                id = "transfer:${it.id}",
                title = it.headline,
                detail = it.diagnosis?.title?.takeIf(String::isNotBlank) ?: it.arr?.problem?.takeIf(String::isNotBlank)
                    ?: it.warnings.firstOrNull() ?: Stages.label(it.stage),
                transferId = it.id
            )
        }
        val services = health?.services.orEmpty().filter { it.state == "down" || it.state == "misconfigured" }
            .sortedBy { ServiceNames.rank(it.name) }.map {
                val name = ServiceNames.display(it.name)
                Attention(
                    id = "service:${it.name}",
                    title = if (it.state == "down") "$name isn't responding" else "$name needs setting up",
                    detail = if (it.name == "qbittorrent") "Nothing can download, and transfers can't be shown, until it's running again."
                        else it.lastError.ifBlank { "The hub can't reach it." }
                )
            }
        val full = disks.filter(::lowSpace).map {
            Attention(
                id = "disk:${it.name}",
                title = "${it.name.trimEnd('\\', '/')} is nearly full",
                detail = "${Fmt.bytes(it.availableBytes)} free of ${Fmt.bytes(it.totalBytes)}. New downloads may fail."
            )
        }
        return transfers + services + full
    }

    /** "10.11.8 · 12 ms" when up; otherwise what is wrong. */
    fun serviceMeta(service: ServiceHealth): String = when (service.state) {
        "up" -> listOf(shortVersion(service.version), "${service.latencyMs} ms").filter(String::isNotBlank).joinToString(" · ")
        "down" -> "Not responding"
        "misconfigured" -> "Needs setup"
        "disabled" -> "Off"
        else -> service.state.replaceFirstChar { it.uppercase() }
    }

    /** Radarr's "6.4.4.10685" is "6.4.4" and qBittorrent's "v5.0.4" is "5.0.4": a row reads one way. */
    fun shortVersion(version: String): String = version.removePrefix("v").split('.').take(3).joinToString(".")

    /** "All 11 up", or how many are not. */
    fun servicesSummary(health: HealthResponse): String {
        val shown = health.services.filter { it.state != "disabled" }
        val notUp = shown.count { it.state != "up" }
        return if (notUp == 0) "All ${shown.size} up" else "$notUp not responding"
    }

    /**
     * A dashboard address is useless on the handheld when it names the media
     * PC's loopback: the browser would look for the service on the Pocket.
     */
    fun reachableFromPocket(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host.isNotBlank() && host != "localhost" && host != "::1" && host != "[::1]" && !host.startsWith("127.")
    }

    data class AgendaEntry(
        val id: String,
        val media: MediaRef,
        /** "Today · Fri 2 Oct", "Missed · Thu 24 Sep". */
        val heading: String,
        /** "S2E6 · 07:00". */
        val line: String,
        val state: UpcomingPresentation.ReleaseState
    )

    /**
     * A short agenda around today: anything that aired in the last two weeks and
     * never arrived, then the next releases from yesterday on, one per title
     * per day, soonest first.
     */
    fun agenda(
        items: List<CalendarItem>,
        now: Instant,
        zone: ZoneId,
        limit: Int = 5,
        missedDays: Long = 14
    ): List<AgendaEntry> {
        val today = now.atZone(zone).toLocalDate()
        fun date(item: CalendarItem) = runCatching { LocalDate.parse(item.date) }.getOrNull()
        val dated = items.filter { date(it) != null }
            .sortedWith(compareBy({ it.date }, { it.at }, { it.season }, { it.episode }))
            .distinctBy { "${it.date}:${it.media.key.ifBlank { it.id }}" }
        fun state(item: CalendarItem) = UpcomingPresentation.state(item, now, zone)
        val missed = dated.filter {
            val d = date(it)!!
            d.isBefore(today.minusDays(1)) && !d.isBefore(today.minusDays(missedDays)) && state(it) == UpcomingPresentation.ReleaseState.MISSING
        }.takeLast(2)
        val coming = dated.filter { !date(it)!!.isBefore(today.minusDays(1)) }
        return (missed + coming).take(limit).map { item ->
            val d = date(item)!!
            val st = state(item)
            val what = if (item.media.type == "series") EpisodeLabel.code(item.season, item.episode).ifBlank { item.releaseType } else item.releaseType
            val time = runCatching { Instant.parse(item.at).atZone(zone).format(TIME) }.getOrNull().orEmpty()
            AgendaEntry(
                id = item.id.ifBlank { "${item.date}:${item.media.key}" },
                media = item.media,
                heading = heading(d, today, missed = st == UpcomingPresentation.ReleaseState.MISSING && d.isBefore(today.minusDays(1))),
                line = listOf(what, time).filter(String::isNotBlank).joinToString(" · "),
                state = st
            )
        }
    }

    /** "Today · Fri 2 Oct", "Yesterday · Thu 1 Oct", "Missed · Thu 24 Sep", or just "Wed 7 Oct". */
    fun heading(date: LocalDate, today: LocalDate, missed: Boolean = false): String {
        val day = date.format(DAY)
        val relative = when {
            missed -> "Missed"
            date == today -> "Today"
            date == today.minusDays(1) -> "Yesterday"
            date == today.plusDays(1) -> "Tomorrow"
            else -> ""
        }
        return if (relative.isEmpty()) day else "$relative · $day"
    }

    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
}
