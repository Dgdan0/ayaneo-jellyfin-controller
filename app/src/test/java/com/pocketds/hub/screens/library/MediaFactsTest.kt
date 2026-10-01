package com.pocketds.hub.screens.library

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryMediaTrack
import com.pocketds.hub.model.LibraryMediaVersion
import com.pocketds.hub.model.LibraryPerson
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFactsTest {
    private val item = LibraryItem(
        id = "m", type = "movie", title = "UNABOMBER", originalTitle = "Unabomber",
        premiereDate = "2026-09-24T21:00:00.0000000Z", rating = 7.05, officialRating = "R",
        genres = listOf("Drama", "Crime"), studios = listOf("MRC"),
        people = listOf(
            LibraryPerson("1", "Russell Crowe", "Henry Murray", "Actor", "/img/1"),
            LibraryPerson("2", "Jane Doe", "", "Director"),
            LibraryPerson("1", "Russell Crowe", "Henry Murray", "Actor", "/img/1"),
            LibraryPerson("3", "Pat", "Guard", "GuestStar")
        ),
        mediaVersions = listOf(LibraryMediaVersion(
            name = "UNABOMBER (2026) WEBDL-1080p", container = "mkv", sizeBytes = 4_190_878_023, bitrate = 6_350_216,
            tracks = listOf(
                LibraryMediaTrack(type = "video", title = "1080p H264 SDR"),
                LibraryMediaTrack(type = "audio"), LibraryMediaTrack(type = "audio"),
                LibraryMediaTrack(type = "subtitle")
            )
        ))
    )

    @Test
    fun `cast is the actors once each, in billing order`() {
        assertEquals(listOf("Russell Crowe", "Pat"), MediaFacts.cast(item).map { it.name })
        assertEquals("Henry Murray", MediaFacts.cast(item).first().role)
    }

    @Test
    fun `facts name the people behind it, the release and the file`() {
        val facts = MediaFacts.facts(item).associate { it.label to it.value }
        assertEquals("Jane Doe", facts["Directed by"])
        assertEquals("MRC", facts["Studio"])
        assertEquals("Drama, Crime", facts["Genres"])
        assertEquals("24 Sep 2026", facts["Released"])
        assertEquals("R · ★ 7.1", facts["Rated"])
        assertEquals(null, facts["Original title"])
        assertEquals("UNABOMBER (2026) WEBDL-1080p\nMKV · 3.9 GB · 6.4 Mbps\n1080p H264 SDR · 2 audio tracks · 1 subtitle", facts["File"])
    }
}
