package com.pocketds.hub.reader

import com.pocketds.hub.model.*
import com.pocketds.hub.model.ReadingProgress as ProgressView
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

    // A book's page and a comic's page projected as they always were (#30 changed only the audiobook's).
    @Test fun `a book place shows its total progression and a comic place its page over the issue's pages`() {
        val book=ReadingProgressPresentation.project(work,listOf(point)).progress!!
        assertEquals(0.37,book.percentage,0.001)
        assertFalse(book.completed)
        // A book place that names no total still reads as it did: the start.
        val bare=point.copy(local=ReadingLocation(locator=Json.parseToJsonElement("""{"href":"chapter.xhtml"}""").jsonObject))
        assertEquals(0.0,ReadingProgressPresentation.project(work,listOf(bare)).progress!!.percentage,0.0)
        val issue=ReadingSectionItem(sourceItemId="issue",workId="work",title="#1",kind="comic",pageCount=20)
        val run=ReadingWork(id="work",kind="comic",sections=listOf(ReadingSection(id="v",items=listOf(issue))))
        val page=ReadingCheckpoint(ReadingCheckpointKey("profile","work","issue","pages"),local=ReadingLocation(pageIndex=9),pending=true,updatedAt=100)
        val shown=ReadingProgressPresentation.project(run,listOf(page))
        assertEquals(ProgressView(percentage=0.5,completed=false,current=10,total=20),shown.progress)
        assertEquals(shown.progress,shown.sections.single().items.single().progress)
        assertEquals(0.5,shown.continueAt!!.percentage,0.0)
        assertEquals("kavita",shown.continueAt!!.source)
        // The last page is finished.
        val last=ReadingProgressPresentation.project(run,listOf(page.copy(local=ReadingLocation(pageIndex=19)))).progress!!
        assertTrue(last.completed)
        assertEquals(1.0,last.percentage,0.0)
    }

    // An audiobook of three tracks: ten, twenty and five minutes, thirty-five in all (#30).
    private val tracks=listOf(
        ReadingAudioTrack(0,"t_000000000001","Track 01",600_000),
        ReadingAudioTrack(1,"t_000000000002","Track 02",1_200_000),
        ReadingAudioTrack(2,"t_000000000003","Track 03",300_000))
    private val listening=ReadingWork(id="work",title="Book",
        editions=listOf(ReadingEdition(source="storyteller",sourceItemId="edition",kind="audiobook")),
        progress=ProgressView(percentage=0.1),
        continueAt=ReadingContinue(workId="work",source="storyteller",sourceItemId="edition",title="Book",percentage=0.1))
    private fun heard(location:ReadingLocation,at:Long=100)=
        ReadingCheckpoint(ReadingCheckpointKey("profile","work","edition",AudioPlace.KIND),local=location,pending=true,updatedAt=at)
    // The first track and five minutes of the second.
    private val partway=900_000.0/2_100_000

    @Test fun `a pending audiobook place shows how far through the book it is, not 0%`() {
        val shown=ReadingProgressPresentation.project(listening,listOf(heard(AudioPlace.kept(tracks,1,300_000)!!)))
        assertEquals(partway,shown.progress!!.percentage,1e-9)
        assertFalse(shown.progress!!.completed)
        assertEquals(partway,shown.continueAt!!.percentage,1e-9)
        assertEquals("edition",shown.continueAt!!.sourceItemId)
        // A place does not carry a page: the book's own length is not a count of pages heard.
        assertEquals(0,shown.progress!!.current)
    }

    @Test fun `a finished audiobook place shows the book finished`() {
        val shown=ReadingProgressPresentation.project(listening,listOf(heard(AudioPlace.kept(tracks,2,299_000)!!)))
        assertTrue(shown.progress!!.completed)
        assertEquals(1.0,shown.progress!!.percentage,0.0)
        assertEquals(1.0,shown.continueAt!!.percentage,0.0)
        // One an older build kept has no fraction beside it, and is finished all the same.
        val old=ReadingProgressPresentation.project(listening,
            listOf(heard(AudioPlace("t_000000000003",300_000,completed=true).location())))
        assertTrue(old.progress!!.completed)
        assertEquals(1.0,old.progress!!.percentage,0.0)
    }

    @Test fun `an audiobook place from an older build leaves the hub's progress as it is`() {
        val old=heard(AudioPlace("t_000000000002",61_250).location())
        assertEquals(listening,ReadingProgressPresentation.project(listening,listOf(old)))
        // So does one that cannot be told for want of a track's length.
        val unknown=tracks.map { if (it.index==1) it.copy(durationMs=0) else it }
        assertEquals(listening,ReadingProgressPresentation.project(listening,listOf(heard(AudioPlace.kept(unknown,1,300_000)!!))))
    }

    @Test fun `a series page shows the book's audiobook place on its row`() {
        val row=ReadingSectionItem(sourceItemId="edition",workId="work",title="Book",progress=ProgressView(percentage=0.1))
        val series=listening.copy(entityType="collection",editions=emptyList(),sections=listOf(ReadingSection(id="s",items=listOf(row))))
        val shown=ReadingProgressPresentation.project(series,listOf(heard(AudioPlace.kept(tracks,1,300_000)!!)))
        assertEquals(partway,shown.sections.single().items.single().progress!!.percentage,1e-9)
        assertEquals(partway,shown.continueAt!!.percentage,1e-9)
        // The series keeps its own progress, as it does for a book's page.
        assertEquals(series.progress,shown.progress)
        // A place from an older build leaves the row as the hub sent it.
        assertEquals(series,ReadingProgressPresentation.project(series,listOf(heard(AudioPlace("t_000000000002",61_250).location()))))
    }

    @Test fun `of a book's places the latest that can be told wins`() {
        val audio=heard(AudioPlace.kept(tracks,1,300_000)!!,at=200)
        // Heard after it was read: the audiobook's place.
        assertEquals(partway,ReadingProgressPresentation.project(work,listOf(point,audio)).continueAt!!.percentage,1e-9)
        // Read after it was heard: the book's.
        assertEquals(0.37,ReadingProgressPresentation.project(work,listOf(point.copy(updatedAt=300),audio)).continueAt!!.percentage,0.001)
        // A newer place an older build left says nothing, so it does not hide the book's own.
        val unsaid=heard(AudioPlace("t_000000000002",61_250).location(),at=400)
        val shown=ReadingProgressPresentation.project(work,listOf(point,unsaid))
        assertEquals(0.37,shown.continueAt!!.percentage,0.001)
        assertEquals(0.37,shown.progress!!.percentage,0.001)
    }
}
