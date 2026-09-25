package com.pocketds.hub.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ActivityDiagnosisTest {
    @Test fun `old hub responses retain warning fallback`() {
        val item = Json.decodeFromString<ActivityItem>("""{"id":"old","warnings":["unmatched_download"]}""")
        assertTrue(item.isBroken)
        assertNull(item.diagnosis)
    }

    @Test fun `authoritative diagnosis avoids treating informational warnings as failures`() {
        val item = ActivityItem(warnings = listOf("usenet_no_client_detail"), diagnosis = ActivityDiagnosis(needsAttention = false))
        assertFalse(item.isBroken)
    }

    @Test fun `stalled download is included despite normalized queued stage`() {
        val item = Json.decodeFromString<ActivityItem>("""{"stage":"queued","diagnosis":{"code":"waiting_peers","needsAttention":true}}""")
        assertTrue(item.isBroken)
    }
}
