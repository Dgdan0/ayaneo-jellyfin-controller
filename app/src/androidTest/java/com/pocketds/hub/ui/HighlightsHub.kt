package com.pocketds.hub.ui

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject

/**
 * A stand-in hub for the highlight tests (#62), nothing real behind it: it serves one book's file, takes any reading place, and keeps
 * highlights the way the hub does (last write wins on `updatedAt`, a delete is a tombstone, every version is stored at a later moment
 * than the one before), answering as the hub does so the app's outbox and merge are exercised for real.
 */
class HighlightsHub(private val epub: ByteArray) {
    val server = MockWebServer()
    /** Every highlight route request, in order: "PUT an_…", "DELETE an_…", "GET ?since=…". */
    val log = ArrayList<String>()
    private val held = LinkedHashMap<String, JSONObject>()
    private var clock = 1_000L
    /** While true the highlight routes answer 503, as a hub that is down. */
    @Volatile var down = false
    /** The device and whether it is the asker, answered with a reading place. */
    @Volatile var device = ""
    @Volatile var byThisDevice = false

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                val query = request.path.orEmpty().substringAfter('?', "")
                return when {
                    path.contains("/annotations") -> annotations(request, path, query)
                    path.endsWith("/file") -> MockResponse().setHeader("Content-Type", "application/epub+zip").setBody(Buffer().write(epub))
                    path.endsWith("/position") && request.method == "GET" -> json("""{"locator":null,"device":"$device","byThisDevice":$byThisDevice}""")
                    request.method == "POST" -> json("""{"ok":true}""")
                    else -> json("""{"locator":null}""")
                }
            }
        }
        server.start()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Synchronized private fun annotations(request: RecordedRequest, path: String, query: String): MockResponse {
        if (down) return json("""{"error":{"code":"upstream_down","message":"down"}}""", 503)
        val id = path.substringAfter("/annotations/", "")
        return when (request.method) {
            "GET" -> {
                val since = query.removePrefix("since=").toLongOrNull()
                log += "GET ?since=$since"
                val list = JSONArray()
                held.values.filter { if (since == null) !it.optBoolean("deleted") else it.getLong("syncedAt") > since }.forEach(list::put)
                json(JSONObject().put("workId", "w").put("annotations", list).put("serverTime", clock).toString())
            }
            "PUT" -> {
                log += "PUT $id"
                write(JSONObject(request.body.readUtf8()))
            }
            "DELETE" -> {
                log += "DELETE $id"
                val stamp = query.substringAfter("updatedAt=", "").toLongOrNull() ?: clock
                write((held[id]?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", id).put("createdAt", stamp))
                    .put("note", "").put("updatedAt", stamp).put("deleted", true))
            }
            else -> json("{}", 405)
        }
    }

    private fun write(incoming: JSONObject): MockResponse {
        val id = incoming.getString("id")
        val current = held[id]
        val applied = current == null || incoming.getLong("updatedAt") > current.getLong("updatedAt")
        if (applied) {
            incoming.put("syncedAt", ++clock)
            held[id] = incoming
        }
        return json(JSONObject().put("workId", "w").put("annotation", held.getValue(id)).put("applied", applied).toString())
    }

    /** What the hub holds now, tombstones too. */
    @Synchronized fun stored(): List<JSONObject> = held.values.toList()

    /** A highlight made on another device, as the hub would hold it. */
    @Synchronized fun elsewhere(id: String, color: String, document: String, before: String, highlight: String, after: String, note: String = "", at: Long = 5_000) {
        held[id] = JSONObject().put("id", id).put("color", color).put("note", note).put("document", document)
            .put("quote", JSONObject().put("before", before).put("highlight", highlight).put("after", after))
            .put("createdAt", at).put("updatedAt", at).put("syncedAt", ++clock)
    }

    fun shutdown() = server.shutdown()
}
