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
}
