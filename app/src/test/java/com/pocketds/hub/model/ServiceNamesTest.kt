package com.pocketds.hub.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceNamesTest {
    private val all = listOf("qbittorrent", "kavita", "jellyfin", "readarr", "sonarr", "storyteller", "bookkeeprr", "mystery")

    @Test
    fun `services are listed playback first, then requests, the arrs, the client and books`() {
        assertEquals(listOf("jellyfin", "sonarr", "readarr", "qbittorrent", "bookkeeprr", "kavita", "storyteller", "mystery"),
            all.sortedBy { ServiceNames.rank(it) })
    }

    @Test
    fun `in Books the reading services come first, in the same order among themselves`() {
        assertEquals(listOf("readarr", "bookkeeprr", "kavita", "storyteller", "jellyfin", "sonarr", "qbittorrent", "mystery"),
            all.sortedBy { ServiceNames.rank(it, books = true) })
    }

    @Test
    fun `names are spelled as the projects spell them`() {
        assertEquals("qBittorrent", ServiceNames.display("qbittorrent"))
        assertEquals("BookKeeprr", ServiceNames.display("BOOKKEEPRR"))
        assertEquals("Mystery", ServiceNames.display("mystery"))
    }
}
