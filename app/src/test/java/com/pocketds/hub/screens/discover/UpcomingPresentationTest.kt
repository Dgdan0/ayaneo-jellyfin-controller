package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.MediaRef
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class UpcomingPresentationTest {
 @Test fun weeksAreContiguousAcrossSundayAndYearBoundaries() {
  val first=UpcomingPresentation.range(LocalDate.of(2026,9,25),0)
  val next=UpcomingPresentation.range(LocalDate.of(2026,9,25),1)
  assertEquals(LocalDate.of(2026,9,28),first.endExclusive)
  assertEquals(first.endExclusive,next.start)
  assertEquals(UpcomingPresentation.range(LocalDate.of(2026,9,25),-1).endExclusive,first.start)
  assertEquals(7,UpcomingPresentation.days(first).size)
  assertEquals(7,UpcomingPresentation.days(next).size)
  val year=UpcomingPresentation.range(LocalDate.of(2026,12,31),1)
  assertEquals(LocalDate.of(2027,1,4),year.start)
 }
 @Test fun batchGroupsOnlySameSeriesSeasonAndDay() {
  fun item(id:String,season:Int,date:String)=CalendarItem(id=id,date=date,season=season,episode=id.toInt(),media=MediaRef(key="tmdb:series:2",type="series",title="Series"))
  val grouped=UpcomingPresentation.groups(listOf(item("2",1,"2026-09-25"),item("1",1,"2026-09-25"),item("3",2,"2026-09-25"),item("4",1,"2026-09-26")))
  assertEquals(3,grouped.size)
  assertEquals(listOf(1,2),grouped.first().items.map{it.episode})
  assertEquals("Season 1 · 2 episodes",grouped.first().label)
 }
 @Test fun movieReleasesAndMissingIdentifiersDoNotMerge() {
  val movie=CalendarItem(id="m1",date="2026-09-25",media=MediaRef(key="tmdb:movie:4"),releaseType="Digital release")
  val all=listOf(movie,movie.copy(id="m2",releaseType="In cinemas"),CalendarItem(id="e1",date="2026-09-25"),CalendarItem(id="e2",date="2026-09-25"))
  assertEquals(4,UpcomingPresentation.groups(all).size)
 }
 @Test fun theWeekSwitchNamesLastThisAndNextWeek() {
  val today=LocalDate.of(2026,10,2)
  assertEquals("Last week",UpcomingPresentation.weekLabel(today,-1))
  assertEquals("This week · 28 Sep – 4 Oct",UpcomingPresentation.weekLabel(today,0))
  assertEquals("Next week · 5 – 11 Oct",UpcomingPresentation.weekLabel(today,1))
  assertEquals("28 Dec 2026 – 3 Jan 2027",UpcomingPresentation.rangeLabel(UpcomingPresentation.range(LocalDate.of(2026,12,30),0)))
 }
 @Test fun aReleaseIsSoonThenAiredThenMissingUnlessItArrived() {
  val zone=java.time.ZoneId.of("UTC")
  val now=java.time.Instant.parse("2026-10-02T12:00:00Z")
  fun at(t:String,file:Boolean=false)=CalendarItem(id=t,date=t.take(10),at=t,hasFile=file)
  assertEquals(UpcomingPresentation.ReleaseState.SOON,UpcomingPresentation.state(at("2026-10-02T20:00:00Z"),now,zone))
  assertEquals(UpcomingPresentation.ReleaseState.AIRED,UpcomingPresentation.state(at("2026-10-02T04:00:00Z"),now,zone))
  assertEquals(UpcomingPresentation.ReleaseState.MISSING,UpcomingPresentation.state(at("2026-09-30T04:00:00Z"),now,zone))
  assertEquals(UpcomingPresentation.ReleaseState.IN_LIBRARY,UpcomingPresentation.state(at("2026-09-30T04:00:00Z",file=true),now,zone))
  val day=UpcomingPresentation.Group(listOf(at("2026-09-30T04:00:00Z",file=true),at("2026-09-30T05:00:00Z")))
  assertEquals(UpcomingPresentation.ReleaseState.MISSING,UpcomingPresentation.state(day,now,zone))
  assertEquals("S2E6",UpcomingPresentation.Group(listOf(CalendarItem(media=MediaRef(type="series"),season=2,episode=6))).label)
 }
}
