package com.pocketds.hub.net

/**
 * A request, reduced to plain values.
 *
 * Separate from the code that performs it so that "which path, which query
 * parameters, how are they encoded" is decided by a pure function and pinned by
 * unit tests. With an annotation-driven HTTP library this logic lives in
 * metadata and can only be tested by standing up a fake server.
 */
data class HubRequest(
    val url: String,
    val method: String = "GET",
    val body: String? = null
)

/**
 * Every URL the app knows how to build.
 *
 * The base URL is normalised here rather than at every call site: people type
 * it with a trailing slash, without a scheme, with the port on the end, and all
 * of those should work.
 */
object HubEndpoints {
    fun removalPreview(base: String) = HubRequest(join(base, "/v1/media/removal-preview"), method = "POST")
    fun mediaRemove(base: String) = HubRequest(join(base, "/v1/media/remove"), method = "POST")
    fun subtitles(base: String,itemId: String,action: String=""): HubRequest = HubRequest(join(base,"/v1/library/items/"+encode(itemId)+"/subtitles"+(if(action.isEmpty()) "" else "/"+encode(action))))
    fun refreshSubtitles(base: String,itemId: String): HubRequest = HubRequest(join(base,"/v1/library/items/"+encode(itemId)+"/subtitles/refresh"),method="POST")
    fun bandwidth(base: String): HubRequest = HubRequest(join(base, "/v1/downloads/bandwidth"))
    fun calendar(base: String, start: String, end: String, timezone: String): HubRequest =
        HubRequest(join(base, "/v1/calendar") + "?start=" + encode(start) +
            "&end=" + encode(end) + "&timezone=" + encode(timezone))

    fun health(base: String): HubRequest = HubRequest(join(base, "/v1/health"))

    fun scanJellyfinLibrary(base: String): HubRequest =
        HubRequest(join(base, "/v1/manage/jellyfin/scan"), method = "POST")

    fun scanReadingLibrary(base: String, service: String): HubRequest =
        HubRequest(
            join(base, "/v1/manage/reading/scan") + "?service=" + encode(service),
            method = "POST"
        )

    fun users(base: String): HubRequest = HubRequest(join(base, "/v1/users"))

    fun home(base: String): HubRequest = HubRequest(join(base, "/v1/home"))

    fun library(base: String): HubRequest = HubRequest(join(base, "/v1/library"))

    /** Saves one side's library order for the profile (#15); the body is a [com.pocketds.hub.model.LibraryOrderRequest]. */
    fun libraryOrder(base: String): HubRequest = HubRequest(join(base, "/v1/library/order"), method = "PUT")

    fun libraryItems(
        base: String,
        viewId: String,
        page: Int = 1,
        sort: String = "name",
        order: String = "asc"
    ): HubRequest {
        val query = buildList {
            if (sort != "name") add("sort=" + encode(sort))
            if (order != "asc") add("order=" + encode(order))
            if (page > 1) add("page=$page")
        }
        return HubRequest(
            join(base, "/v1/library/" + encode(viewId) + "/items") +
                if (query.isEmpty()) "" else "?" + query.joinToString("&")
        )
    }

    fun libraryItem(base: String, itemId: String): HubRequest =
        HubRequest(join(base, "/v1/library/items/" + encode(itemId)))

    fun librarySeasons(base: String, seriesId: String): HubRequest =
        HubRequest(join(base, "/v1/library/series/" + encode(seriesId) + "/seasons"))

    fun libraryEpisodes(
        base: String,
        seriesId: String,
        seasonId: String,
        page: Int = 1
    ): HubRequest = HubRequest(
        join(base, "/v1/library/series/" + encode(seriesId) + "/episodes") +
            "?seasonId=" + encode(seasonId) + if (page > 1) "&page=$page" else ""
    )

    /** [viewId], when set, keeps the search inside that library (#14); a hub before it ignores it and searches all. */
    fun librarySearch(base: String, query: String, page: Int = 1, viewId: String = ""): HubRequest =
        HubRequest(
            join(base, "/v1/library/search") + "?q=" + encode(query) +
                (if (page > 1) "&page=$page" else "") +
                if (viewId.isNotBlank()) "&viewId=" + encode(viewId) else ""
        )

    fun libraryFavorites(base: String, page: Int = 1): HubRequest =
        HubRequest(join(base, "/v1/library/favorites") + if (page > 1) "?page=$page" else "")

    fun librarySimilar(base: String, itemId: String): HubRequest =
        HubRequest(join(base, "/v1/library/items/" + encode(itemId) + "/similar"))

    fun libraryState(base: String, itemId: String): HubRequest =
        HubRequest(join(base, "/v1/library/items/" + encode(itemId) + "/state"), method = "POST")

    fun seriesPlayTarget(base: String, seriesId: String): HubRequest =
        HubRequest(join(base, "/v1/library/series/" + encode(seriesId) + "/play-target"))

    fun preparePlayback(base: String, itemId: String): HubRequest =
        HubRequest(join(base, "/v1/playback/items/" + encode(itemId) + "/prepare"), method = "POST")

    fun selectPlayback(base: String, sessionId: String): HubRequest =
        HubRequest(join(base, "/v1/playback/sessions/" + encode(sessionId) + "/select"), method = "POST")

    fun castGrant(base: String, sessionId: String): HubRequest =
        HubRequest(join(base, "/v1/playback/sessions/" + encode(sessionId) + "/cast-grant"), method = "POST")

    fun playbackEvent(base: String, sessionId: String): HubRequest =
        HubRequest(join(base, "/v1/playback/sessions/" + encode(sessionId) + "/events"), method = "POST")

    fun deletePlayback(base: String, sessionId: String): HubRequest =
        HubRequest(join(base, "/v1/playback/sessions/" + encode(sessionId)), method = "DELETE")

    fun offlineSelection(base: String, seriesId: String): HubRequest =
        HubRequest(join(base, "/v1/offline/series/" + encode(seriesId) + "/selection"))

    fun prepareOffline(base: String): HubRequest =
        HubRequest(join(base, "/v1/offline/prepare"), method = "POST")

    fun renewOffline(base: String, grantId: String): HubRequest =
        HubRequest(join(base, "/v1/offline/grants/" + encode(grantId) + "/renew"), method = "POST")

    fun syncOfflineProgress(base: String): HubRequest =
        HubRequest(join(base, "/v1/offline/progress/sync"), method = "POST")

    fun playbackResource(base: String, hubPath: String): String = join(base, hubPath)

    fun search(base: String, query: String, page: Int = 1): HubRequest {
        val params = buildString {
            append("q=").append(encode(query))
            if (page > 1) append("&page=").append(page)
        }
        return HubRequest(join(base, "/v1/search") + "?" + params)
    }

    fun mediaDetail(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/media/" + encode(key)))

    fun person(base: String, id: Int, sort: String = "release"): HubRequest =
        HubRequest(join(base, "/v1/person/$id") + "?sort=" + encode(sort))

    fun requests(base: String): HubRequest =
        HubRequest(join(base, "/v1/requests"), method = "POST")

    /** Every Discover row in one request, so the screen is one round trip. */
    fun discover(base: String): HubRequest = HubRequest(join(base, "/v1/discover"))

    fun discoverRow(base: String, row: String, page: Int): HubRequest =
        HubRequest(join(base, "/v1/discover/" + encode(row)) + "?page=" + page)

    fun readingDiscover(base: String, type: String): HubRequest =
        HubRequest(join(base, "/v1/reading/discover") + "?type=" + encode(type))

    fun readingDiscoverRow(base: String, row: String, type: String, page: Int): HubRequest =
        HubRequest(
            join(base, "/v1/reading/discover/" + encode(row)) +
                "?type=" + encode(type) + "&page=" + page
        )

    fun readingSearch(base: String, query: String, type: String): HubRequest =
        HubRequest(
            join(base, "/v1/reading/search") +
                "?q=" + encode(query.trim()) + "&type=" + encode(type)
        )

    fun readingLibraries(base: String): HubRequest =
        HubRequest(join(base, "/v1/reading/libraries"))

    fun serverReadingLists(base: String, id: Int? = null): HubRequest =
        HubRequest(join(base, "/v1/reading/lists" + if (id == null) "" else "/$id"))

    /**
     * Storyteller books always come as series: the library's Series view groups
     * them whatever the sort, and Books Home builds its rows from series. Sorting
     * by title used to ask for loose books, so Mistborn fell apart into three cards.
     */
    fun readingLibraryItems(
        base: String,
        libraryId: String,
        page: Int = 1,
        sort: String = "title",
        direction: String = "asc",
        /** "works" lists every book on its own; otherwise Storyteller's books come grouped into series. */
        view: String = ""
    ): HubRequest = HubRequest(
        join(base, "/v1/reading/libraries/" + encode(libraryId) + "/items") +
            "?page=$page&sort=" + encode(sort) + "&direction=" + encode(direction) + when {
                view.isNotBlank() -> "&view=" + encode(view)
                libraryId == "storyteller:books" -> "&view=collections"
                else -> ""
            }
    )

    fun readingAuthors(base:String,libraryId:String,page:Int,direction:String,authorId:String=""):HubRequest =
        HubRequest(join(base,"/v1/reading/libraries/"+encode(libraryId)+"/authors")+"?page=$page&direction="+encode(direction)+"&authorId="+encode(authorId))
    fun readingResolve(base:String,source:String,sourceId:String,isbn:String):HubRequest =
        HubRequest(join(base,"/v1/reading/resolve")+"?source="+encode(source)+"&sourceId="+encode(sourceId)+"&isbn="+encode(isbn))

    fun readingWork(base: String, workId: String): HubRequest =
        HubRequest(join(base, "/v1/reading/works/" + encode(workId)))

    fun readingPublication(base: String, workId: String, sourceItemId: String): HubRequest =
        HubRequest(
            join(base, "/v1/reading/works/" + encode(workId) +
                "/publications/" + encode(sourceItemId))
        )

    fun readingPublicationPage(
        base: String,
        workId: String,
        sourceItemId: String,
        pageIndex: Int
    ): String = join(
        base,
        "/v1/reading/works/" + encode(workId) + "/publications/" +
            encode(sourceItemId) + "/pages/$pageIndex"
    )

    /**
     * A page's thumbnail beside its page (#16, C4): `.../pages/{page}/thumb?w=`,
     * scaled in the hub, which clamps [width] to 64-512 and keeps each size.
     */
    fun readingPublicationThumb(pageUrl: String, width: Int): String =
        pageUrl.substringBefore('?') + "/thumb?w=" + width.coerceIn(64, 512)

    fun readingPublicationProgress(base: String, workId: String, sourceItemId: String): HubRequest =
        HubRequest(
            join(base, "/v1/reading/works/" + encode(workId) +
                "/publications/" + encode(sourceItemId) + "/progress"),
            method = "POST"
        )

    /**
     * A book's EPUB; [readAlong] the read-along edition, and [omitAudio] that
     * edition without its audio (#19): its words and SMIL, about a megabyte,
     * while the narration streams from the audiobook's tracks.
     */
    fun readingEpubFile(base: String, workId: String, sourceItemId: String, readAlong: Boolean = false, omitAudio: Boolean = false): String =
        join(
            base,
            "/v1/reading/works/" + encode(workId) + "/publications/" +
                encode(sourceItemId) + "/file"
        ) + when {
            readAlong && omitAudio -> "?format=readaloud&audio=omit"
            readAlong -> "?format=readaloud"
            else -> ""
        }

    fun readingAudiobookFile(base: String, workId: String, sourceItemId: String): String =
        join(base, "/v1/reading/works/" + encode(workId) +
            "/publications/" + encode(sourceItemId) + "/file?format=audiobook")

    /** An audiobook's tracks, chapters and read-along map (#19, A3). */
    fun readingAudioManifest(base: String, workId: String, sourceItemId: String): HubRequest =
        HubRequest(join(base, readingAudioPath(workId, sourceItemId)))

    /**
     * One track's bytes, with Range: the manifest's [index] under its
     * [revision], which the hub checks so a rescan cannot play another file.
     */
    fun readingAudioTrack(base: String, workId: String, sourceItemId: String, index: Int, revision: String): String {
        require(index >= 0) { "A track number is not negative" }
        return join(base, readingAudioPath(workId, sourceItemId) + "/tracks/$index") + "?rev=" + encode(revision)
    }

    /** The listening place, read and written (#19, A4). */
    fun readingAudioPosition(base: String, workId: String, sourceItemId: String): HubRequest =
        HubRequest(join(base, readingAudioPath(workId, sourceItemId) + "/position"))

    private fun readingAudioPath(workId: String, sourceItemId: String) =
        "/v1/reading/works/" + encode(workId) + "/publications/" + encode(sourceItemId) + "/audio"

    fun readingEpubPosition(base: String, workId: String, sourceItemId: String): HubRequest =
        HubRequest(
            join(base, "/v1/reading/works/" + encode(workId) +
                "/publications/" + encode(sourceItemId) + "/position")
        )

    fun readingRequestOptions(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/reading/requests/options") + "?key=" + encode(key))

    fun readingSeriesPreview(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/reading/requests/series-preview") + "?key=" + encode(key))

    fun readingRequests(base: String): HubRequest =
        HubRequest(join(base, "/v1/reading/requests"), method = "POST")

    fun readingReleases(base: String, seriesId: Int): HubRequest =
        HubRequest(join(base, "/v1/reading/requests/$seriesId/releases"))

    fun searchReadingReleases(base: String, seriesId: Int): HubRequest =
        HubRequest(join(base, "/v1/reading/requests/$seriesId/search"), method = "POST")

    fun grabReadingRelease(base: String, seriesId: Int): HubRequest =
        HubRequest(join(base, "/v1/reading/requests/$seriesId/grab"), method = "POST")

    fun readingDownloads(base: String): HubRequest =
        HubRequest(join(base, "/v1/reading/downloads"))

    fun retryReadingDownload(base: String, id: String): HubRequest =
        HubRequest(join(base, "/v1/reading/downloads/" + encode(id) + "/retry"), method = "POST")

    fun cancelReadingDownload(base: String, id: String): HubRequest =
        HubRequest(join(base, "/v1/reading/downloads/" + encode(id)), method = "DELETE")

    fun requestOptions(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/requests/options") + "?key=" + encode(key))

    /**
     * Interactive search. Slow by nature -- it asks every indexer -- which is
     * why the app gives it a screen of its own rather than a spinner in a menu.
     */
    fun releases(base: String, key: String, season: Int = 0, episode: Int = 0): HubRequest =
        HubRequest(
            join(base, "/v1/media/" + encode(key) + "/releases") +
                buildString {
                    if (season > 0 || episode > 0) append("?season=$season")
                    if (episode > 0) append("&episode=$episode")
                }
        )

    fun releaseTargets(base: String, key: String, season: Int): HubRequest =
        HubRequest(
            join(base, "/v1/media/" + encode(key) + "/release-targets") + "?season=$season"
        )

    fun grab(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/media/" + encode(key) + "/grab"), method = "POST")

    fun activity(base: String, includeFinished: Boolean = false): HubRequest =
        HubRequest(join(base, "/v1/activity") + if (includeFinished) "?all=true" else "")

    fun notifications(
        base: String,
        limits: com.pocketds.hub.settings.NotificationLimits = com.pocketds.hub.settings.NotificationLimits()
    ): HubRequest = HubRequest(
        join(base, "/v1/notifications") +
            "?sonarrLimit=${limits.sonarr}&radarrLimit=${limits.radarr}&bazarrLimit=${limits.bazarr}"
    )

    /**
     * Start or stop a transfer.
     *
     * The id is percent-encoded even though a colon is legal in a path segment,
     * because it is the one value on this screen that came off the wire and
     * goes straight back into a URL.
     */
    fun downloadAction(base: String, id: String, action: String): HubRequest =
        HubRequest(join(base, "/v1/downloads/" + encode(id) + "/" + action), method = "POST")

    /**
     * Delete a transfer.
     *
     * [deleteFiles] is always written out, never left to a server default: the
     * difference between the two is whether the user's data still exists
     * afterwards, and that must be explicit at the call site.
     */
    fun downloadDelete(base: String, id: String, deleteFiles: Boolean): HubRequest =
        HubRequest(
            join(base, "/v1/downloads/" + encode(id)) + "?deleteFiles=" + deleteFiles,
            method = "DELETE"
        )

    /**
     * Drop an *arr queue row.
     *
     * All three switches are sent explicitly for the same reason -- in
     * particular removeFromClient, whose server-side default is true.
     */
    fun queueRemove(
        base: String,
        service: String,
        queueId: Int,
        removeFromClient: Boolean = true,
        blocklist: Boolean = false,
        search: Boolean = false
    ): HubRequest = HubRequest(
        join(base, "/v1/queue/" + encode(service) + "/" + queueId + "/remove") +
            "?removeFromClient=" + removeFromClient +
            "&blocklist=" + blocklist +
            "&search=" + search,
        method = "POST"
    )

    /**
     * Poster and backdrop URLs arrive from the hub already prefixed with `/v1/`,
     * so they only need the base attached. Returned as a plain string because
     * this feeds an image loader rather than the JSON client.
     */
    fun image(base: String, hubPath: String): String = join(base, hubPath)

    /** The Glass colours of up to 60 hub image paths, each sent exactly as a screen shows it (#10). */
    fun artworkColors(base: String, sources: List<String>): HubRequest =
        HubRequest(join(base, "/v1/img/colors") + "?" + sources.joinToString("&") { "src=" + encode(it) })

    /**
     * A hub image path asking for [width] pixels, which the hub snaps to its
     * nearest size. Unsized, a Jellyfin image comes back 360 wide: right for a
     * poster, soft across a whole screen.
     */
    fun sized(hubPath: String, width: Int): String {
        // A TMDB image names its size in the path, from the hub's fixed set; w1280 is the
        // largest worth fetching (original runs to 4K and several megabytes).
        if (hubPath.startsWith(TMDB_IMAGES)) {
            val rest = hubPath.removePrefix(TMDB_IMAGES)
            val slash = rest.indexOf('/')
            if (slash <= 0) return hubPath
            return TMDB_IMAGES + (if (width > 780) "w1280" else "w780") + rest.substring(slash)
        }
        return if (hubPath.isBlank() || "w=" in hubPath) hubPath else hubPath + (if ('?' in hubPath) "&" else "?") + "w=$width"
    }

    /**
     * The smallest picture the hub serves of [hubPath], for artwork drawn tiny
     * or blurred: the Glass page behind every screen decodes 64 pixels. A TMDB
     * image at w92 rather than the w780 [sized] would ask for, a Jellyfin one
     * at w=180 (its smallest bucket), replacing any width already asked for.
     * Anything else, a reading cover, comes in the one size its server has.
     */
    fun smallest(hubPath: String): String = when {
        hubPath.startsWith(TMDB_IMAGES) -> {
            val rest = hubPath.removePrefix(TMDB_IMAGES)
            val slash = rest.indexOf('/')
            if (slash <= 0) hubPath else TMDB_IMAGES + "w92" + rest.substring(slash)
        }
        hubPath.startsWith(JELLYFIN_IMAGES) -> {
            val query = hubPath.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotEmpty() && !it.startsWith("w=") }
            hubPath.substringBefore('?') + "?" + (params + "w=$SMALLEST_JELLYFIN_WIDTH").joinToString("&")
        }
        else -> hubPath
    }

    /**
     * The hub path of a Jellyfin item's picture of [type] ("Primary",
     * "Backdrop"), untagged: the hub fills in the current one. Built here only,
     * so the same picture is always the same string, and so one entry in the
     * hub's and the Pocket's colour caches.
     */
    fun jellyfinImage(itemId: String, type: String): String = "$JELLYFIN_IMAGES$itemId/$type"

    private const val TMDB_IMAGES = "/v1/img/tmdb/"
    private const val JELLYFIN_IMAGES = "/v1/img/jf/"
    /** The hub's smallest width bucket for a Jellyfin image. */
    private const val SMALLEST_JELLYFIN_WIDTH = 180

    /**
     * Trims a trailing slash from the base and guarantees exactly one between
     * the two halves.
     */
    fun join(base: String, path: String): String {
        val trimmed = base.trimEnd('/')
        return if (path.startsWith("/")) trimmed + path else "$trimmed/$path"
    }

    /**
     * Normalises what a person types into something OkHttp will accept.
     *
     * A bare host is assumed to be http, because the realistic case is a LAN
     * address or a loopback forward. Once it is behind Caddy the user pastes a
     * full https URL and this leaves it alone.
     */
    fun normaliseBase(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    /**
     * Percent-encoding for a query value.
     *
     * Hand-rolled rather than `URLEncoder`, which is a JVM class that behaves
     * subtly differently on Android and -- more to the point -- encodes a space
     * as `+`, which is correct for form bodies and wrong in a path query where
     * the server may read it literally.
     */
    fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            when {
                c.isLetterOrDigit() && byte.toInt() in 0..127 -> append(c)
                c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                else -> append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
