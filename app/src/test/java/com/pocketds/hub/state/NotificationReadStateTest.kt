package com.pocketds.hub.state

import com.pocketds.hub.model.NotificationSection
import com.pocketds.hub.model.ServiceNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReadStateTest {
    @Test fun firstObservationBaselinesExistingHistory() {
        val result = NotificationReadReducer.observe(NotificationReadSnapshot(), sections(notice("old", "2026-01-01T00:00:00Z")))
        assertTrue(result.unreadIds.isEmpty())
        assertTrue("old" in result.snapshot.seenIds)
    }

    @Test fun laterItemIsUnreadUntilMarkedSeen() {
        val baseline = NotificationReadReducer.observe(NotificationReadSnapshot(), sections(notice("old", "2026-01-01T00:00:00Z"))).snapshot
        val observed = NotificationReadReducer.observe(baseline, sections(
            notice("new", "2026-01-02T00:00:00Z"), notice("old", "2026-01-01T00:00:00Z")
        ))
        assertEquals(setOf("new"), observed.unreadIds)
        val marked = NotificationReadReducer.markSeen(observed.snapshot, "new")
        assertTrue(NotificationReadReducer.observe(marked, sections(notice("new", "2026-01-02T00:00:00Z"))).unreadIds.isEmpty())
    }

    @Test fun olderBackfillFromLargerLimitIsAlreadySeen() {
        val baseline = NotificationReadReducer.observe(NotificationReadSnapshot(), sections(notice("old", "2026-01-02T00:00:00Z"))).snapshot
        val result = NotificationReadReducer.observe(baseline, sections(
            notice("old", "2026-01-02T00:00:00Z"), notice("backfill", "2026-01-01T00:00:00Z")
        ))
        assertTrue(result.unreadIds.isEmpty())
    }

    @Test fun newHealthIssueWithoutTimestampIsUnread() {
        val baseline = NotificationReadReducer.observe(
            NotificationReadSnapshot(),
            listOf(NotificationSection(service = "sonarr", state = "up"))
        ).snapshot
        val result = NotificationReadReducer.observe(baseline, sections(ServiceNotice(id = "health", service = "sonarr", active = true)))
        assertEquals(setOf("health"), result.unreadIds)
    }

    private fun notice(id: String, at: String) = ServiceNotice(id = id, service = "sonarr", occurredAt = at)
    private fun sections(vararg notices: ServiceNotice) = listOf(NotificationSection(service = "sonarr", items = notices.toList()))
}
