package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ActivityDiagnosis
import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ActivityResponse
import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.HostDisk
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.ServiceHealth
import com.pocketds.hub.model.Stages
import com.pocketds.hub.screens.discover.UpcomingPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ActivityDashboardTest {

    private val zone = ZoneId.of("Asia/Jerusalem")
    // Friday 2 October 2026, 09:00 in Jerusalem.
    private val now = Instant.parse("2026-10-02T06:00:00Z")

    private fun episode(date: String, at: String, key: String, title: String, s: Int, e: Int, hasFile: Boolean) =
        CalendarItem(id = "$key:$s:$e", media = MediaRef(key = key, type = "series", title = title), date = date, at = at,
            releaseType = "Episode", season = s, episode = e, hasFile = hasFile)

    @Test
    fun `the agenda starts with what never arrived, then runs from yesterday`() {
        val items = listOf(
            episode("2026-09-24", "2026-09-24T19:00:00Z", "tv:1", "Last Seen", 1, 3, hasFile = false),
            episode("2026-09-30", "2026-09-30T04:00:00Z", "tv:2", "Ted Lasso", 4, 9, hasFile = true),
            episode("2026-10-01", "2026-10-01T18:00:00Z", "tv:1", "Last Seen", 1, 4, hasFile = true),
            episode("2026-10-02", "2026-10-02T04:00:00Z", "tv:3", "Dark Matter", 2, 6, hasFile = false),
            episode("2026-10-03", "2026-10-03T17:00:00Z", "tv:4", "Shogun", 2, 1, hasFile = false)
        )
        val agenda = ActivityDashboard.agenda(items, now, zone)
        assertEquals(listOf("Last Seen", "Last Seen", "Dark Matter", "Shogun"), agenda.map { it.media.title })
        assertEquals("Missed · Thu 24 Sep", agenda[0].heading)
        assertEquals(UpcomingPresentation.ReleaseState.MISSING, agenda[0].state)
        assertEquals("Yesterday · Thu 1 Oct", agenda[1].heading)
        assertEquals(UpcomingPresentation.ReleaseState.IN_LIBRARY, agenda[1].state)
        // Aired three hours ago: not missing yet, Sonarr has barely had time to look.
        assertEquals(UpcomingPresentation.ReleaseState.AIRED, agenda[2].state)
        assertEquals("S2E6 · 07:00", agenda[2].line)
        assertEquals("Tomorrow · Sat 3 Oct", agenda[3].heading)
        assertEquals(UpcomingPresentation.ReleaseState.SOON, agenda[3].state)
    }

    @Test
    fun `a title appears once per day however many episodes air`() {
        val items = (1..3).map { episode("2026-10-05", "2026-10-05T01:00:00Z", "tv:9", "Lanterns", 1, it, hasFile = false) }
        val agenda = ActivityDashboard.agenda(items, now, zone)
        assertEquals(1, agenda.size)
        assertEquals("S1E1 · 04:00", agenda[0].line)
        assertEquals("Mon 5 Oct", agenda[0].heading)
    }

    @Test
    fun `the agenda is capped`() {
        val items = (3..12).map { episode("2026-10-%02d".format(it), "", "tv:$it", "Show $it", 1, 1, hasFile = false) }
        assertEquals(5, ActivityDashboard.agenda(items, now, zone).size)
    }

    @Test
    fun `attention lists broken transfers, then services that are down, then full disks`() {
        val activity = ActivityResponse(items = listOf(
            ActivityItem(id = "qbit:aa", mediaTitle = "Severance", stage = Stages.STUCK,
                diagnosis = ActivityDiagnosis(title = "Sonarr can't import it", needsAttention = true)),
            ActivityItem(id = "qbit:bb", mediaTitle = "Dune", stage = Stages.DOWNLOADING)
        ))
        val health = HealthResponse(services = listOf(
            ServiceHealth(name = "radarr", state = "up"),
            ServiceHealth(name = "qbittorrent", state = "down")
        ))
        val disks = listOf(
            HostDisk("C:\\", totalBytes = 500, availableBytes = 100),
            HostDisk("E:\\", totalBytes = 4_000_000_000_000, availableBytes = 38_000_000_000)
        )
        val attention = ActivityDashboard.attention(activity, health, disks)
        assertEquals(listOf("Severance", "qBittorrent isn't responding", "E: is nearly full"), attention.map { it.title })
        assertEquals("Sonarr can't import it", attention[0].detail)
        assertEquals("qbit:aa", attention[0].transferId)
        assertEquals("", attention[1].transferId)
    }

    @Test
    fun `only moving transfers get a row`() {
        val activity = ActivityResponse(items = listOf(
            ActivityItem(id = "1", stage = Stages.SEEDING),
            ActivityItem(id = "2", stage = Stages.DOWNLOADING),
            ActivityItem(id = "3", stage = Stages.STUCK),
            ActivityItem(id = "4", stage = Stages.QUEUED)
        ))
        assertEquals(listOf("2", "4"), ActivityDashboard.transfers(activity).map { it.id })
    }

    @Test
    fun `a transfer line says how far and how long`() {
        val item = ActivityItem(stage = Stages.DOWNLOADING, sizeBytes = 2L shl 30, remainingBytes = 1L shl 30, etaSeconds = 240)
        assertEquals("1.0 GB of 2.0 GB · 4m left", ActivityDashboard.transferLine(item))
        assertEquals("2.0 GB · Queued", ActivityDashboard.transferLine(ActivityItem(stage = Stages.QUEUED, sizeBytes = 2L shl 30)))
    }

    @Test
    fun `service lines are short`() {
        assertEquals("6.4.4 · 3 ms", ActivityDashboard.serviceMeta(ServiceHealth(name = "radarr", state = "up", version = "6.4.4.10685", latencyMs = 3)))
        assertEquals("5.0.4 · 1 ms", ActivityDashboard.serviceMeta(ServiceHealth(name = "qbittorrent", state = "up", version = "v5.0.4", latencyMs = 1)))
        assertEquals("21 ms", ActivityDashboard.serviceMeta(ServiceHealth(name = "kavita", state = "up", latencyMs = 21)))
        assertEquals("Not responding", ActivityDashboard.serviceMeta(ServiceHealth(name = "qbittorrent", state = "down")))
        assertEquals("All 2 up", ActivityDashboard.servicesSummary(HealthResponse(services = listOf(
            ServiceHealth(name = "a", state = "up"), ServiceHealth(name = "b", state = "up")))))
        assertEquals("1 not responding", ActivityDashboard.servicesSummary(HealthResponse(services = listOf(
            ServiceHealth(name = "a", state = "up"), ServiceHealth(name = "b", state = "down")))))
    }

    @Test
    fun `a loopback dashboard address cannot be opened from the Pocket`() {
        assertTrue(ActivityDashboard.reachableFromPocket("https://ayaneo-media-pc.tail737e96.ts.net:8920"))
        assertTrue(ActivityDashboard.reachableFromPocket("http://100.95.23.41:9696"))
        assertFalse(ActivityDashboard.reachableFromPocket("http://127.0.0.1:5000"))
        assertFalse(ActivityDashboard.reachableFromPocket("http://localhost:3000"))
        assertFalse(ActivityDashboard.reachableFromPocket(""))
    }

    @Test
    fun `day headings`() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals("Today · Fri 2 Oct", ActivityDashboard.heading(today, today))
        assertEquals("Wed 7 Oct", ActivityDashboard.heading(today.plusDays(5), today))
    }

    @Test
    fun `the heading's line says what is moving, what needs a look and how the services are`() {
        val idle = ActivityResponse(items = listOf(ActivityItem(id = "a", stage = Stages.SEEDING)))
        val health = HealthResponse(services = List(11) { ServiceHealth(name = "s$it", state = "up") } +
            ServiceHealth(name = "off", state = "disabled"))
        assertEquals("Nothing downloading · 1 thing needs attention · all 11 services up", ActivityDashboard.headline(idle, 1, health))
        val busy = ActivityResponse(items = listOf(ActivityItem(id = "a", stage = Stages.DOWNLOADING), ActivityItem(id = "b", stage = Stages.DOWNLOADING)))
        val down = HealthResponse(services = listOf(ServiceHealth(name = "a", state = "up"), ServiceHealth(name = "b", state = "down")))
        assertEquals("2 downloading · 1 service not responding", ActivityDashboard.headline(busy, 0, down))
        // Before anything has loaded there is nothing to say.
        assertEquals("", ActivityDashboard.headline(null, 0, null))
        assertEquals("3 things need attention", ActivityDashboard.headline(null, 3, null))
    }

    @Test
    fun `one transfer needs attention, several need it`() {
        assertEquals("1 transfer needs attention", ActivityDashboard.needAttention(1))
        assertEquals("3 transfers need attention", ActivityDashboard.needAttention(3))
    }
}
