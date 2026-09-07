package com.pocketds.hub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HubEndpointsTest {

    private val base = "http://127.0.0.1:8791"

    @Test
    fun `builds a search url`() {
        assertEquals(
            "$base/v1/search?q=dune",
            HubEndpoints.search(base, "dune").url
        )
    }

    @Test
    fun `page one is omitted, later pages are not`() {
        // Keeps the cache key stable: /v1/search?q=dune and ?q=dune&page=1 are
        // the same request and should not be two entries.
        assertEquals("$base/v1/search?q=dune", HubEndpoints.search(base, "dune", 1).url)
        assertEquals("$base/v1/search?q=dune&page=3", HubEndpoints.search(base, "dune", 3).url)
    }

    @Test
    fun `a trailing slash on the base never doubles`() {
        assertEquals(
            "$base/v1/health",
            HubEndpoints.health("$base/").url
        )
    }

    @Test
    fun `spaces are percent-encoded, not turned into plus`() {
        // URLEncoder would produce '+', which is correct for a form body and
        // wrong here -- the hub reads the query value literally.
        assertEquals(
            "$base/v1/search?q=dune%20part%20two",
            HubEndpoints.search(base, "dune part two").url
        )
    }

    @Test
    fun `characters that would break the query are escaped`() {
        val url = HubEndpoints.search(base, "rock & roll?").url
        assertEquals("$base/v1/search?q=rock%20%26%20roll%3F", url)
    }

    @Test
    fun `non-ascii survives as utf-8`() {
        // Titles with accents are extremely common and must not corrupt.
        assertEquals("Am%C3%A9lie", HubEndpoints.encode("Amélie"))
    }

    @Test
    fun `unreserved characters are left alone`() {
        assertEquals("abc-XYZ_123.~", HubEndpoints.encode("abc-XYZ_123.~"))
    }

    @Test
    fun `a bare host is assumed to be http`() {
        // The realistic case is a LAN address or an adb-reverse loopback.
        assertEquals("http://192.168.1.20:8791", HubEndpoints.normaliseBase("192.168.1.20:8791"))
    }

    @Test
    fun `an explicit scheme is respected`() {
        assertEquals(
            "https://myjellydan.duckdns.org",
            HubEndpoints.normaliseBase("https://myjellydan.duckdns.org/")
        )
    }

    @Test
    fun `whitespace and trailing slashes are trimmed`() {
        assertEquals("http://host:1", HubEndpoints.normaliseBase("  host:1/  "))
    }

    @Test
    fun `an empty base stays empty rather than becoming a bare scheme`() {
        assertEquals("", HubEndpoints.normaliseBase("   "))
    }

    @Test
    fun `image paths from the hub are made absolute`() {
        assertEquals(
            "$base/v1/img/tmdb/w342/abc.jpg",
            HubEndpoints.image(base, "/v1/img/tmdb/w342/abc.jpg")
        )
    }

    @Test
    fun `activity asks for running items only by default`() {
        val request = HubEndpoints.activity("http://hub:8791")
        assertEquals("http://hub:8791/v1/activity", request.url)
        assertEquals("GET", request.method)
    }

    @Test
    fun `activity can include finished items`() {
        assertEquals(
            "http://hub:8791/v1/activity?all=true",
            HubEndpoints.activity("http://hub:8791", includeFinished = true).url
        )
    }

    @Test
    fun `a torrent id survives the round trip into a path`() {
        // The colon is legal in a path segment but encoded anyway: this is the
        // one value on the screen that came off the wire and goes straight back
        // out in a URL.
        val request = HubEndpoints.downloadAction("http://hub:8791", "qbit:a1b2c3", "stop")
        assertEquals("http://hub:8791/v1/downloads/qbit%3Aa1b2c3/stop", request.url)
        assertEquals("POST", request.method)
    }

    @Test
    fun `delete always states its intent about the files`() {
        // Never left to a server default -- the difference between the two is
        // whether the user's data still exists afterwards.
        val keep = HubEndpoints.downloadDelete("http://hub:8791", "qbit:ff", false)
        assertEquals("http://hub:8791/v1/downloads/qbit%3Aff?deleteFiles=false", keep.url)
        assertEquals("DELETE", keep.method)

        val wipe = HubEndpoints.downloadDelete("http://hub:8791", "qbit:ff", true)
        assertTrue(wipe.url.endsWith("?deleteFiles=true"))
    }

    @Test
    fun `queue remove sends all three switches explicitly`() {
        // removeFromClient defaults to true server-side; sending it means the
        // call site decides rather than inheriting.
        val request = HubEndpoints.queueRemove("http://hub:8791", "sonarr", 42)
        assertEquals(
            "http://hub:8791/v1/queue/sonarr/42/remove" +
                "?removeFromClient=true&blocklist=false&search=false",
            request.url
        )
        assertEquals("POST", request.method)
    }

    @Test
    fun `blocklist and search ride together on the fix-it action`() {
        val request = HubEndpoints.queueRemove(
            "http://hub:8791", "radarr", 7, removeFromClient = true,
            blocklist = true, search = true
        )
        assertTrue(request.url.contains("blocklist=true"))
        assertTrue(request.url.contains("search=true"))
    }

    @Test
    fun `a trailing slash on the base does not double up`() {
        assertEquals(
            "http://hub:8791/v1/activity",
            HubEndpoints.activity("http://hub:8791/").url
        )
    }
}
