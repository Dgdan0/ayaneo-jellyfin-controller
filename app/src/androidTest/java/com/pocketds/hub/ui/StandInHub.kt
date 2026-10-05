package com.pocketds.hub.ui

import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * A local stand-in for the hub's audiobook routes (#19), with the hub's rules
 * and generated silence: the manifest under a revision, each track's bytes by
 * Range (a stale revision answers 412 audio_changed), and the listening place,
 * read and written, a write checked against `expected` and refused with 409
 * when the place has moved. Nothing here reaches a real server, book or place.
 */
class StandInHub(
    private val work: String,
    private val book: String,
    /** Each track's length in seconds; the bytes are generated silence. */
    seconds: List<Int>,
    private val chapters: List<Triple<String, Int, Long>> = emptyList(),
    /** What the manifest route answers instead, a status and a code (409 audio_not_streamable). */
    var refuse: Pair<Int, String>? = null,
    /** A read-along edition's audio files mapped onto the tracks: its path in the EPUB, the track, where it begins. */
    private val alignment: List<Triple<String, Int, Long>> = emptyList(),
    /** The read-along edition whole, and without its audio (`audio=omit`). */
    private val whole: ByteArray? = null,
    private val slim: ByteArray? = null
) {
    data class Track(val index: Int, val id: String, val bytes: ByteArray, val durationMs: Long)
    data class Place(val trackId: String, val offsetMs: Long, val completed: Boolean = false, val exact: Boolean = true)
    data class Request(val path: String, val query: String, val range: String?, val authorization: String?, val atMs: Long)

    val tracks = seconds.mapIndexed { index, length ->
        Track(index, "t_%012x".format(index + 1), ReaderFixtures.silence(length), length * 1000L)
    }
    @Volatile var revision = "aaaaaaaaaaaa"
    @Volatile var held: Place? = null
    val requests = CopyOnWriteArrayList<Request>()
    val writes = CopyOnWriteArrayList<Pair<JSONObject, Long>>()
    private val publication = "/v1/reading/works/$work/publications/$book"

    val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        start()
    }

    fun shutdown() = server.shutdown()

    fun manifestReads() = requests.count { it.path == "$publication/audio" }
    fun trackReads(index: Int) = requests.filter { it.path == "$publication/audio/tracks/$index" }
    fun fileReads() = requests.count { it.path.endsWith("/file") }
    fun fileQueries() = requests.filter { it.path.endsWith("/file") }.map { it.query }

    private fun json(body: Any, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun error(status: Int, code: String, reason: String = "") = json(JSONObject().put("error",
        JSONObject().put("code", code).put("reason", reason).put("message", code).put("retryable", false)).put("requestId", "stand-in"), status)

    private fun answer(request: RecordedRequest): MockResponse {
        val url = request.requestUrl!!
        requests += Request(url.encodedPath, url.encodedQuery.orEmpty(), request.getHeader("Range"),
            request.getHeader("Authorization"), System.currentTimeMillis())
        val path = url.encodedPath
        return when {
            path == "$publication/audio" -> refuse?.let { (status, code) -> error(status, code, "unmapped_root") } ?: manifest()
            path.startsWith("$publication/audio/tracks/") -> track(path.substringAfterLast('/').toInt(), url.queryParameter("rev"), request.getHeader("Range"))
            path == "$publication/audio/position" && request.method == "GET" -> position()
            path == "$publication/audio/position" && request.method == "POST" -> write(JSONObject(request.body.readUtf8()))
            path == "$publication/file" && url.queryParameter("audio") == "omit" ->
                slim?.let { MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(it)) } ?: error(409, "audio_not_streamable", "unreadable")
            path == "$publication/file" -> whole?.let { MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(it)) }
                ?: MockResponse().setResponseCode(404)
            // A reader's place in the text: none kept, and a write taken (the stand-in keeps no text places).
            path == "$publication/position" && request.method == "GET" -> json(JSONObject().put("locator", JSONObject.NULL))
            path == "$publication/position" -> json(JSONObject().put("ok", true))
            else -> json(JSONObject(), 404)
        }
    }

    private fun manifest(): MockResponse = json(JSONObject()
        .put("workId", work).put("sourceItemId", book).put("revision", revision).put("narrator", "A generated voice")
        .put("totalMs", tracks.sumOf { it.durationMs }).put("aligned", alignment.isNotEmpty())
        .apply { if (alignment.isNotEmpty()) put("alignment", JSONObject().put("audio", JSONArray(alignment.map { (href, track, start) ->
            JSONObject().put("href", href).put("track", track).put("startMs", start) }))) }
        .put("tracks", JSONArray(tracks.map { track ->
            JSONObject().put("index", track.index).put("id", track.id).put("title", "Track %02d".format(track.index + 1))
                .put("durationMs", track.durationMs).put("bytes", track.bytes.size).put("mime", "audio/wav").put("etag", "\"e${track.index}\"")
        }))
        .put("chapters", JSONArray(chapters.map { (title, track, start) -> JSONObject().put("title", title).put("track", track).put("startMs", start) }))
        .put("cache", JSONObject()))

    /** The bytes by one Range, as http.ServeContent answers it. */
    private fun track(index: Int, rev: String?, range: String?): MockResponse {
        if (rev != revision) return error(412, "audio_changed")
        val bytes = tracks.getOrNull(index)?.bytes ?: return error(404, "not_found")
        val (from, to) = range?.removePrefix("bytes=")?.split('-')?.let { (a, b) ->
            a.toLong() to (b.toLongOrNull() ?: (bytes.size - 1L)).coerceAtMost(bytes.size - 1L)
        } ?: (0L to bytes.size - 1L)
        val body = Buffer().write(bytes, from.toInt(), (to - from + 1).toInt())
        return MockResponse().setResponseCode(if (range == null) 200 else 206)
            .setHeader("Content-Type", "audio/wav").setHeader("Accept-Ranges", "bytes").setHeader("ETag", "\"e$index\"")
            .apply { if (range != null) setHeader("Content-Range", "bytes $from-$to/${bytes.size}") }
            .setBody(body)
    }

    private fun position(): MockResponse = json(JSONObject().put("workId", work).put("sourceItemId", book).put("position",
        held?.let { place ->
            val track = tracks.indexOfFirst { it.id == place.trackId }
            JSONObject().put("trackId", place.trackId).put("track", track).put("offsetMs", place.offsetMs)
                .put("globalMs", tracks.take(track).sumOf { it.durationMs } + place.offsetMs).put("completed", place.completed)
                .put("exact", place.exact).put("form", if (place.exact) "audio" else "text").put("timestamp", 1_764_000_000_000L)
        } ?: JSONObject.NULL))

    /** The hub's write: checked against `expected`, then kept as the hub reads it back. */
    private fun write(body: JSONObject): MockResponse {
        writes += body to System.currentTimeMillis()
        if (body.has("expected")) {
            val expected = body.opt("expected")
            val holds = if (expected == null || expected == JSONObject.NULL) held == null else {
                val want = expected as JSONObject
                held?.trackId == want.getString("trackId") && held?.offsetMs == want.getLong("offsetMs")
            }
            if (!holds) return error(409, "reading_position_conflict")
        }
        val last = tracks.last()
        held = if (body.optBoolean("completed")) Place(last.id, last.durationMs, completed = true) else {
            val track = tracks.first { it.id == body.getString("trackId") }
            val offset = body.getLong("offsetMs").coerceAtMost(track.durationMs)
            Place(track.id, offset, completed = track == last && offset >= track.durationMs - 2_000)
        }
        return json(JSONObject().put("ok", true).put("action", "save_audio_position").put("timestamp", 1_764_000_000_001L))
    }
}
