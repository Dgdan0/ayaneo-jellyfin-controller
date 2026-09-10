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
    fun `home is one top-level request`() {
        assertEquals("$base/v1/home", HubEndpoints.home(base).url)
    }

    @Test
    fun `jellyfin scan is a scoped mutation`() {
        val request = HubEndpoints.scanJellyfinLibrary(base)
        assertEquals("$base/v1/manage/jellyfin/scan", request.url)
        assertEquals("POST", request.method)
    }

    @Test
    fun `users is one authenticated top-level request`() {
        assertEquals("$base/v1/users", HubEndpoints.users(base).url)
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
    fun `an https address keeps its public custom port`() {
        val publicBase = "https://myjellydan.duckdns.org:55886"
        assertEquals(publicBase, HubEndpoints.normaliseBase("$publicBase/"))
        assertEquals("$publicBase/v1/health", HubEndpoints.health(publicBase).url)
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
    fun `library urls preserve opaque jellyfin ids`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals("$base/v1/library", HubEndpoints.library(base).url)
        assertEquals("$base/v1/library/$id/items", HubEndpoints.libraryItems(base, id).url)
        assertEquals("$base/v1/library/$id/items?page=3", HubEndpoints.libraryItems(base, id, 3).url)
        assertEquals("$base/v1/library/items/$id", HubEndpoints.libraryItem(base, id).url)
        assertEquals("$base/v1/library/series/$id/seasons", HubEndpoints.librarySeasons(base, id).url)
    }

    @Test
    fun `library sort and direction are explicit when changed`() {
        val id = "0123456789abcdef0123456789abcdef"
        assertEquals(
            "$base/v1/library/$id/items?sort=rating&order=desc&page=3",
            HubEndpoints.libraryItems(base, id, 3, "rating", "desc").url
        )
        assertEquals(
            "$base/v1/library/$id/items?order=desc",
            HubEndpoints.libraryItems(base, id, 1, "name", "desc").url
        )
    }

    @Test
    fun `episode url names the season and later page`() {
        val series = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val season = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        assertEquals(
            "$base/v1/library/series/$series/episodes?seasonId=$season&page=2",
            HubEndpoints.libraryEpisodes(base, series, season, 2).url
        )
    }

    @Test
    fun `release urls distinguish a season from one episode`() {
        val key = "tmdb:series:258230"
        assertEquals(
            "$base/v1/media/tmdb%3Aseries%3A258230/release-targets?season=1",
            HubEndpoints.releaseTargets(base, key, 1).url
        )
        assertEquals(
            "$base/v1/media/tmdb%3Aseries%3A258230/releases?season=1",
            HubEndpoints.releases(base, key, 1).url
        )
        assertEquals(
            "$base/v1/media/tmdb%3Aseries%3A258230/releases?season=1&episode=2",
            HubEndpoints.releases(base, key, 1, 2).url
        )
    }

    @Test
    fun `library search favourites and state use their dedicated routes`() {
        val item = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertEquals(
            "$base/v1/library/search?q=star%20wars",
            HubEndpoints.librarySearch(base, "star wars").url
        )
        assertEquals(
            "$base/v1/library/search?q=star%20wars&page=2",
            HubEndpoints.librarySearch(base, "star wars", 2).url
        )
        assertEquals("$base/v1/library/favorites", HubEndpoints.libraryFavorites(base).url)
        assertEquals("$base/v1/library/favorites?page=4", HubEndpoints.libraryFavorites(base, 4).url)
        val state = HubEndpoints.libraryState(base, item)
        assertEquals("$base/v1/library/items/$item/state", state.url)
        assertEquals("POST", state.method)
    }

    @Test
    fun `playback urls preserve opaque item and session ids`() {
        val item = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val session = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        assertEquals(
            "$base/v1/library/series/$item/play-target",
            HubEndpoints.seriesPlayTarget(base, item).url
        )
        assertEquals(
            "$base/v1/playback/items/$item/prepare",
            HubEndpoints.preparePlayback(base, item).url
        )
        assertEquals(
            "$base/v1/playback/sessions/$session/select",
            HubEndpoints.selectPlayback(base, session).url
        )
        assertEquals(
            "$base/v1/playback/sessions/$session/events",
            HubEndpoints.playbackEvent(base, session).url
        )
        assertEquals(
            "$base/v1/playback/sessions/$session",
            HubEndpoints.deletePlayback(base, session).url
        )
        assertEquals(
            "$base/v1/playback/sessions/$session/stream",
            HubEndpoints.playbackResource(base, "/v1/playback/sessions/$session/stream")
        )
    }

    @Test
    fun `offline urls keep series and grant capabilities in path segments`() {
        val series = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val grant = "grant:one"
        assertEquals(
            "$base/v1/offline/series/$series/selection",
            HubEndpoints.offlineSelection(base, series).url
        )
        assertEquals("$base/v1/offline/prepare", HubEndpoints.prepareOffline(base).url)
        assertEquals("POST", HubEndpoints.prepareOffline(base).method)
        assertEquals(
            "$base/v1/offline/grants/grant%3Aone/renew",
            HubEndpoints.renewOffline(base, grant).url
        )
        assertEquals("$base/v1/offline/progress/sync", HubEndpoints.syncOfflineProgress(base).url)
    }

    @Test
    fun `activity asks for running items only by default`() {
        val request = HubEndpoints.activity("http://hub:8791")
        assertEquals("http://hub:8791/v1/activity", request.url)
        assertEquals("GET", request.method)
    }

    @Test
    fun `notifications use their own read-only feed`() {
        val request = HubEndpoints.notifications("http://hub:8791/")
        assertEquals(
            "http://hub:8791/v1/notifications?sonarrLimit=60&radarrLimit=20&bazarrLimit=40",
            request.url
        )
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
