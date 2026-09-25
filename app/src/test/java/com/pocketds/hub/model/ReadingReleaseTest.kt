package com.pocketds.hub.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReadingReleaseTest {
    @Test fun fractionalIndexerScoreDecodes() {
        val response = Json.decodeFromString<ReadingReleasesResponse>(
            """{"seriesId":18,"releases":[{"id":"br_choice","title":"Book EPUB","score":12.5}]}"""
        )
        assertEquals(12.5, response.releases.single().score, 0.0)
    }

    @Test fun knownWrongFormatCannotBeChosen() {
        val release = Json.decodeFromString<ReadingRelease>(
            """{"id":"br_wrong","title":"Light Bringer EPUB","rejected":true,"format":"EPUB","formatStatus":"incompatible"}"""
        )
        assertFalse(release.canGrab())
        assertEquals("EPUB · wrong format", release.formatLabel())
    }

    @Test fun unknownFormatIsClearlyMarkedForReview() {
        val release = ReadingRelease(id = "br_unknown", title = "Light Bringer")
        assertEquals("Format unverified", release.formatLabel())
    }
}
