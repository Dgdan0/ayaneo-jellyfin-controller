package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAnnotationWritten
import com.pocketds.hub.model.ReadingAnnotationsResponse
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationSyncTest {
    @get:Rule val folder = TemporaryFolder()

    private fun note(id: String, color: String = "yellow", at: Long = 100, deleted: Boolean = false, synced: Long = 0) =
        ReadingAnnotation(id, color, "", "OEBPS/chapter-1.xhtml", AnnotationQuote("a ", "bird", "."), null, at, at, deleted, synced)

    /** A hub that keeps what it is sent, last write wins on updatedAt, and says when it stored each version. */
    private class FakeHub : AnnotationRemote {
        val held = LinkedHashMap<String, ReadingAnnotation>()
        var clock = 1_000L
        var failWith: HubResult.Failed? = null
        val writes = ArrayList<String>()
        var listedSince: Long? = -1

        private fun store(incoming: ReadingAnnotation): HubResult.Ok<ReadingAnnotationWritten> {
            val current = held[incoming.id]
            if (current != null && incoming.updatedAt <= current.updatedAt) return HubResult.Ok(ReadingAnnotationWritten("w", current, false))
            val stored = incoming.copy(syncedAt = ++clock)
            held[incoming.id] = stored
            return HubResult.Ok(ReadingAnnotationWritten("w", stored, true))
        }

        override suspend fun annotations(workId: String, since: Long?): HubResult<ReadingAnnotationsResponse> {
            failWith?.let { return it }
            listedSince = since
            val list = held.values.filter { if (since == null) !it.deleted else it.syncedAt > since }
            return HubResult.Ok(ReadingAnnotationsResponse(workId, list, clock))
        }

        override suspend fun save(workId: String, annotation: ReadingAnnotation): HubResult<ReadingAnnotationWritten> {
            failWith?.let { return it }
            writes += "put ${annotation.id}"
            return store(annotation)
        }

        override suspend fun delete(workId: String, id: String, updatedAt: Long): HubResult<ReadingAnnotationWritten> {
            failWith?.let { return it }
            writes += "delete $id"
            return store((held[id] ?: ReadingAnnotation(id)).copy(deleted = true, note = "", updatedAt = updatedAt))
        }
    }

    private class BookLedger(val book: AnnotationBook) : AnnotationLedger {
        override fun pending() = book.pending()
        override fun sent(id: String, sent: ReadingAnnotation, held: ReadingAnnotation) = book.sent(id, sent, held)
        override fun refused(id: String) = book.refused(id)
        override fun cursor() = book.cursor
        override fun merged(remote: List<ReadingAnnotation>) { book.merge(remote) }
    }

    private fun run(hub: FakeHub, book: AnnotationBook) = runBlocking { AnnotationSync(hub).run("w", BookLedger(book)) }

    @Test fun theOutboxGoesOutOldestFirstAndTheHubsAnswerIsWhatWeKeep() {
        val hub = FakeHub()
        val book = AnnotationBook()
        book.put(note("an_b"), 300)
        book.put(note("an_a"), 100)
        book.remove("an_a", 400)
        val result = run(hub, book)
        assertEquals(AnnotationSync.Result(), result)
        assertEquals(listOf("put an_b", "delete an_a"), hub.writes)
        assertTrue(book.pendingIds.isEmpty())
        assertTrue(book.get("an_a")!!.deleted)
        assertEquals(hub.held.getValue("an_b").syncedAt, book.get("an_b")!!.syncedAt)
    }

    @Test fun whatTheOtherDevicesDidComesAfterTheCursorAndTheCursorMovesOn() {
        val hub = FakeHub()
        hub.held["an_x"] = note("an_x", "pink", at = 50, synced = 900)
        val book = AnnotationBook()
        run(hub, book)
        assertNull("the first read has no cursor", hub.listedSince)
        assertEquals(listOf("an_x"), book.live.map { it.id })
        assertEquals(900L, book.cursor)
        // Another device deletes it, and a new one appears; this one asks only for what is after what it read.
        hub.held["an_x"] = note("an_x", at = 500, deleted = true, synced = 1_100)
        hub.held["an_y"] = note("an_y", at = 40, synced = 1_101)
        run(hub, book)
        assertEquals(900L, hub.listedSince)
        assertEquals(listOf("an_y"), book.live.map { it.id })
    }

    @Test fun anEditMadeOfflineAnHourAgoIsStillSentAndStillReachesTheOtherDevice() {
        val hub = FakeHub()
        hub.held["an_seed"] = note("an_seed", at = 5_000, synced = 900)
        val phone = AnnotationBook()
        run(hub, phone)
        val ipad = AnnotationBook()
        run(hub, ipad)
        assertEquals(900L, ipad.cursor)
        // The iPad has read up to 900. The phone, which was offline, now sends an edit it made long before that.
        phone.put(note("an_1", at = 10), 20)
        assertEquals(20L, phone.get("an_1")!!.updatedAt)
        run(hub, phone)
        run(hub, ipad)
        assertEquals(setOf("an_1", "an_seed"), ipad.live.map { it.id }.toSet())
    }

    @Test fun anEditThatLostAtTheHubTakesWhatWonAndStopsWaiting() {
        val hub = FakeHub()
        hub.held["an_1"] = note("an_1", "green", at = 900, synced = 1_001)
        val book = AnnotationBook(listOf(note("an_1", "yellow", at = 100)))
        book.put(note("an_1", "blue"), 200)
        run(hub, book)
        assertEquals("green", book.get("an_1")!!.color)
        assertTrue(book.pendingIds.isEmpty())
    }

    @Test fun noNetworkLeavesTheOutboxAndAsksForAnotherTry() {
        val hub = FakeHub().apply { failWith = HubResult.Failed(FailureKind.NO_NETWORK) }
        val book = AnnotationBook()
        book.put(note("an_1"), 100)
        val result = run(hub, book)
        assertEquals(AnnotationSync.Result(retry = true, stopped = true), result)
        assertEquals(listOf("an_1"), book.pendingIds)
        assertEquals(1, book.live.size)
    }

    @Test fun aHubThatDoesNotKnowTheRoutesIsLeftAloneUntilTheNextTime() {
        val hub = FakeHub().apply { failWith = HubResult.Failed(FailureKind.NOT_FOUND) }
        val book = AnnotationBook()
        book.put(note("an_1"), 100)
        assertEquals(AnnotationSync.Result(retry = false, stopped = true), run(hub, book))
        assertEquals(listOf("an_1"), book.pendingIds)
    }

    @Test fun anEditTheHubRefusesIsNotSentAgainAndTheRestGoOn() {
        val hub = FakeHub()
        val refusing = object : AnnotationRemote by hub {
            override suspend fun save(workId: String, annotation: ReadingAnnotation): HubResult<ReadingAnnotationWritten> =
                if (annotation.id == "an_bad") HubResult.Failed(FailureKind.BAD_RESPONSE, code = "invalid_request") else hub.save(workId, annotation)
        }
        val book = AnnotationBook()
        book.put(note("an_bad"), 100)
        book.put(note("an_ok"), 200)
        val result = runBlocking { AnnotationSync(refusing).run("w", BookLedger(book)) }
        assertEquals(AnnotationSync.Result(), result)
        assertTrue(book.pendingIds.isEmpty())
        assertEquals(listOf("an_ok"), hub.held.keys.toList())
        assertEquals(2, book.live.size)
    }

    @Test fun theBodyHasOnlyWhatTheHubAcceptsAndNeverMoreThanItKeeps() {
        val body = Json.parseToJsonElement(AnnotationBodies.of(note("an_1").copy(note = "x".repeat(5000), deleted = true, syncedAt = 77,
            locator = JsonObject(mapOf("href" to JsonPrimitive("OEBPS/chapter-1.xhtml")))))).jsonObject
        assertEquals(setOf("id", "color", "note", "document", "quote", "locator", "createdAt", "updatedAt"), body.keys)
        assertEquals(AnnotationLimits.NOTE_BYTES, (body["note"] as JsonPrimitive).content.length)
        val bare = Json.parseToJsonElement(AnnotationBodies.of(note("an_1"))).jsonObject
        assertFalse(bare.containsKey("locator"))
        val huge = JsonObject(mapOf("blob" to JsonPrimitive("y".repeat(9000))))
        assertFalse(Json.parseToJsonElement(AnnotationBodies.of(note("an_1").copy(locator = huge))).jsonObject.containsKey("locator"))
    }

    @Test fun aNoteInAnotherAlphabetIsCutByBytesBetweenCharacters() {
        val hebrew = "שלום".repeat(1500)
        val clipped = AnnotationLimits.clip(hebrew, AnnotationLimits.NOTE_BYTES)
        assertTrue(clipped.toByteArray(Charsets.UTF_8).size <= AnnotationLimits.NOTE_BYTES)
        assertTrue(hebrew.startsWith(clipped))
        assertEquals("", AnnotationLimits.clip("", 10))
        val emoji = "a\uD83D\uDE00".repeat(10)
        assertEquals("a\uD83D\uDE00a", AnnotationLimits.clip(emoji, 7))
    }

    @Test fun theStoreKeepsABookAcrossARestartAndNothingOfAnotherProfile() {
        val store = AnnotationStore(folder.newFolder("annotations"))
        val book = AnnotationBook()
        book.put(note("an_1"), 100)
        store.save("profile-a", "w", book)
        val again = AnnotationStore(File(folder.root, "annotations")).load("profile-a", "w")
        assertEquals(listOf("an_1"), again.live.map { it.id })
        assertEquals(listOf("an_1"), again.pendingIds)
        assertTrue(store.load("profile-b", "w").all.isEmpty())
        assertTrue(store.load("profile-a", "other").all.isEmpty())
        assertEquals(listOf("w"), store.pendingWorks("profile-a"))
        assertTrue(store.pendingWorks("profile-b").isEmpty())
        again.sent("an_1", again.get("an_1")!!, again.get("an_1")!!.copy(syncedAt = 5))
        store.save("profile-a", "w", again)
        assertTrue(store.pendingWorks("profile-a").isEmpty())
    }

    @Test fun aFileThatCannotBeReadIsAnErrorAndIsNeverStartedOverQuietly() {
        val directory = folder.newFolder("broken")
        val store = AnnotationStore(directory)
        store.save("profile-a", "w", AnnotationBook(listOf(note("an_1"))))
        directory.listFiles()!!.first { it.extension == "json" }.writeText("{not json")
        assertThrows(Exception::class.java) { store.load("profile-a", "w") }
    }
}
