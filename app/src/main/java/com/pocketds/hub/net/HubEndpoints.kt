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

    fun health(base: String): HubRequest = HubRequest(join(base, "/v1/health"))

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

    fun requestOptions(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/requests/options") + "?key=" + encode(key))

    /**
     * Interactive search. Slow by nature -- it asks every indexer -- which is
     * why the app gives it a screen of its own rather than a spinner in a menu.
     */
    fun releases(base: String, key: String, season: Int = 0): HubRequest =
        HubRequest(
            join(base, "/v1/media/" + encode(key) + "/releases") +
                if (season > 0) "?season=$season" else ""
        )

    fun grab(base: String, key: String): HubRequest =
        HubRequest(join(base, "/v1/media/" + encode(key) + "/grab"), method = "POST")

    fun activity(base: String, includeFinished: Boolean = false): HubRequest =
        HubRequest(join(base, "/v1/activity") + if (includeFinished) "?all=true" else "")

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
