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
    private val slim: ByteArray? = null,
    /** Where the chapters come from, as the hub says it (#31): "book" or "marks"; none, as an older hub sends them. */
    private val chapterSource: String? = null
) {
    data class Track(val index: Int, val id: String, val bytes: ByteArray, val durationMs: Long)
    data class Place(val trackId: String, val offsetMs: Long, val completed: Boolean = false, val exact: Boolean = true)
    data class Request(val path: String, val query: String, val range: String?, val authorization: String?, val atMs: Long)

    val tracks = seconds.mapIndexed { index, length ->
        Track(index, "t_%012x".format(index + 1), ReaderFixtures.silence(length), length * 1000L)
    }
    @Volatile var revision = "aaaaaaaaaaaa"
    @Volatile var held: Place? = null
    /** The device that wrote the held place, and whether it is the one asking (#62): what the hub says with a place. */
    @Volatile var device = ""
    @Volatile var byThisDevice = false
    /** When the held place was written, on the hub's clock. */
    @Volatile var writtenAt = 1_764_000_000_000L
    val requests = CopyOnWriteArrayList<Request>()
    /**
     * The work's own page (#39): `GET /v1/reading/works/{work}` answers it, its "you" as `PATCH …/you` has left
     * it. The patch is the hub's: a key present sets, `null` clears, absent keeps; a rating is a number from 1
     * to 5, a month `YYYY-MM`, a count 1 to 99; a month finished with no count is count 1.
     */
    @Volatile var page: JSONObject? = null
    val you = JSONObject()
    val youWrites = CopyOnWriteArrayList<JSONObject>()
    /** A status and a code the patch answers instead (a hub that cannot save). */
    @Volatile var youRefused: Pair<Int, String>? = null
    /**
     * Start over (#60), as the hub does it: `POST …/start-over` forgets the place (the listening place here, the page's
     * progress), takes away this profile's finish and answers a stamp, which the work's page and both position reads
     * repeat as `resetAt`; a place written with a `resetSeen` older than it is refused as `reading_position_reset`.
     * Nothing else of "you" changes. The reading status (#63) is `you.chosen`, a word the patch's `status` sets, and the work's page and the
     * patch's answer carry the effective one (the choice, else finished, else a place begun) as `status`.
     */
    @Volatile var resetAt = 0L
    val startOvers = CopyOnWriteArrayList<Long>()
    /** The hub starts the book over right after it answers the next read of the listening place: the read was true when it was made. */
    @Volatile var startOverAfterNextRead = false
    private var clock = 1_764_000_100_000L
    /** What a device that is not told of the reset writes: places it was refused. */
    val refusedAsReset = CopyOnWriteArrayList<JSONObject>()

    /** A generated cover, served at `/v1/img/fixture/cover`. */
    @Volatile var cover: ByteArray? = null
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
            path == "/v1/reading/works/$work" && request.method == "GET" ->
                page?.let { json(JSONObject(it.toString()).put("you", JSONObject(you.toString())).apply {
                    if (resetAt > 0) put("resetAt", resetAt)
                    effective(optJSONObject("progress")).takeIf { word -> word.isNotEmpty() }?.let { word -> put("status", word) }
                }) } ?: error(404, "not_found")
            path == "/v1/reading/works/$work/start-over" && request.method == "POST" -> startOver()
            path == "/v1/reading/works/$work/you" && request.method == "PATCH" -> patchYou(request.body.readUtf8())
            path == "/v1/img/fixture/cover" -> cover?.let { MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(it)) }
                ?: MockResponse().setResponseCode(404)
            path == "$publication/audio" -> refuse?.let { (status, code) -> error(status, code, "unmapped_root") } ?: manifest()
            path.startsWith("$publication/audio/tracks/") -> track(path.substringAfterLast('/').toInt(), url.queryParameter("rev"), request.getHeader("Range"))
            path == "$publication/audio/position" && request.method == "GET" -> position()
            path == "$publication/audio/position" && request.method == "POST" -> write(JSONObject(request.body.readUtf8()))
            path == "$publication/file" && url.queryParameter("audio") == "omit" ->
                slim?.let { MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(it)) } ?: error(409, "audio_not_streamable", "unreadable")
            path == "$publication/file" -> whole?.let { MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(it)) }
                ?: MockResponse().setResponseCode(404)
            // A reader's place in the text: none kept, and a write taken (the stand-in keeps no text places).
            path == "$publication/position" && request.method == "GET" -> json(JSONObject().put("locator", JSONObject.NULL).withReset())
            path == "$publication/position" -> {
                val body = runCatching { JSONObject(request.body.readUtf8()) }.getOrDefault(JSONObject())
                if (staleWrite(body)) { refusedAsReset += body; error(409, "reading_position_reset") } else json(JSONObject().put("ok", true))
            }
            else -> json(JSONObject(), 404)
        }
    }

    /** The stamp on a read of a place. */
    private fun JSONObject.withReset(): JSONObject {
        if (resetAt > 0) put("resetAt", resetAt)
        return this
    }

    /** The start over, as another device asks it. */
    fun startOverNow(): Long { startOver(); return resetAt }

    /** A write that carries the reset it last saw, older than the hub's, is made from a place that went away. */
    private fun staleWrite(body: JSONObject) = resetAt > 0 && body.has("resetSeen") && body.getLong("resetSeen") < resetAt

    private fun startOver(): MockResponse {
        resetAt = ++clock
        startOvers += resetAt
        held = null
        page?.remove("progress")
        you.remove("finished"); you.put("status", "")
        // A status chosen as finished goes with the finish; the others say nothing of the place (#63).
        if (you.optString("chosen") == "finished") you.remove("chosen")
        return json(JSONObject().put("ok", true).put("action", "start_over").put("workId", work).put("resetAt", resetAt)
            .put("you", JSONObject(you.toString())))
    }

    /** `PATCH …/works/{id}/you`, with the hub's rules. */
    private fun patchYou(raw: String): MockResponse {
        val body = runCatching { JSONObject(raw) }.getOrNull() ?: return error(400, "invalid_request")
        youWrites += body
        youRefused?.let { (status, code) -> return error(status, code) }
        val keys = body.keys().asSequence().toList()
        if (keys.isEmpty() || keys.any { it !in setOf("rating", "finished", "readCount", "status") }) return error(400, "invalid_request")
        for (key in keys) {
            val value = body.get(key)
            if (value == JSONObject.NULL) continue
            val valid = when (key) {
                "rating" -> value is Int && value in 1..5
                "readCount" -> value is Int && value in 1..99
                "status" -> value is String && value in setOf("want", "reading", "finished", "not-reading")
                else -> value is String && Regex("""\d{4}-(0[1-9]|1[0-2])""").matches(value)
            }
            if (!valid) return error(400, "invalid_request")
        }
        // The change as the hub applies it: finished by status is this month, unless the book has a month (#63).
        val change = JSONObject(body.toString())
        if (change.optString("status") == "finished" && !change.has("finished") && !you.has("finished"))
            change.put("finished", java.time.YearMonth.now().toString())
        val chosen = you.optString("chosen")
        val said = change.keys().asSequence().toList()
        said.filter { it != "status" }.forEach { key -> if (change.isNull(key)) you.remove(key) else you.put(key, change.get(key)) }
        // A month finished, said without a status, is a finish where a status was chosen; one taken away takes a finished status with it.
        if (!change.has("status") && change.has("finished")) {
            if (!change.isNull("finished") && chosen.isNotEmpty()) you.put("chosen", "finished")
            if (change.isNull("finished") && chosen == "finished") you.remove("chosen")
        }
        if (change.has("status")) { if (change.isNull("status")) you.remove("chosen") else you.put("chosen", change.getString("status")) }
        if (you.has("finished") && !you.has("readCount")) you.put("readCount", 1)
        you.put("status", if (you.has("finished")) "read" else "")
        return json(JSONObject().put("workId", work).put("you", JSONObject(you.toString()))
            .apply { effective(null).takeIf { word -> word.isNotEmpty() }?.let { word -> put("status", word) } })
    }

    /** The work's reading status for this profile, as the hub works it out (#63): the choice, else finished, else a place begun. */
    private fun effective(progress: JSONObject?): String {
        val chosen = you.optString("chosen")
        val percentage = progress?.optDouble("percentage", 0.0) ?: 0.0
        val completed = progress?.optBoolean("completed") == true
        return when {
            chosen == "want" && percentage > 0 && !completed -> "reading"
            chosen.isNotEmpty() -> chosen
            you.has("finished") || you.optString("status") == "read" || completed -> "finished"
            percentage > 0 -> "reading"
            else -> ""
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
        .put("chapters", JSONArray(chapters.map { (title, track, start) ->
            JSONObject().put("title", title).put("track", track).put("startMs", start).apply { chapterSource?.let { put("source", it) } }
        }))
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
                .put("exact", place.exact).put("form", if (place.exact) "audio" else "text").put("timestamp", writtenAt)
        } ?: JSONObject.NULL).put("device", device).put("byThisDevice", byThisDevice).withReset()).also {
        if (startOverAfterNextRead) { startOverAfterNextRead = false; startOver() }
    }

    /** The hub's write: checked against `expected`, then kept as the hub reads it back. */
    private fun write(body: JSONObject): MockResponse {
        writes += body to System.currentTimeMillis()
        if (staleWrite(body)) { refusedAsReset += body; return error(409, "reading_position_reset") }
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
