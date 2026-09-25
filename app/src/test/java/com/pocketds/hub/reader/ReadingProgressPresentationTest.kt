package com.pocketds.hub.reader

import com.pocketds.hub.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressPresentationTest {
    private val work=ReadingWork(id="work",title="Book",editions=listOf(ReadingEdition(source="storyteller",sourceItemId="edition",kind="ebook")))
    private val point=ReadingCheckpoint(ReadingCheckpointKey("profile","work","edition","epub"),
        local=ReadingLocation(locator=Json.parseToJsonElement("""{"href":"chapter.xhtml","locations":{"totalProgression":0.37}}""").jsonObject),pending=true,updatedAt=100)

    @Test fun `return to detail immediately shows local progress before sync`() {
        val shown=ReadingProgressPresentation.project(work,listOf(point))
        assertEquals(0.37,shown.continueAt!!.percentage,0.001)
        assertEquals("edition",shown.continueAt!!.sourceItemId)
    }
    @Test fun `unrelated editions never change this book`() {
        assertEquals(work,ReadingProgressPresentation.project(work,listOf(point.copy(key=point.key.copy(sourceItemId="other")))))
    }
    @Test fun `conflicting local progress does not pretend to be the chosen continuation`() {
        assertEquals(work,ReadingProgressPresentation.project(work,listOf(point.copy(conflicted=true))))
    }
}
