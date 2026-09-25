package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.CalendarItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object UpcomingPresentation {
 data class Range(val start:LocalDate,val endExclusive:LocalDate)
 data class Group(val items:List<CalendarItem>) {
  val first get()=items.first()
  val id get()=items.joinToString("|"){it.id}
  val label get()=if(first.media.type!="series") first.releaseType
   else if(items.size>1) "Season ${first.season} · ${items.size} episodes"
   else "S%02dE%02d".format(first.season,first.episode)
 }
 fun range(today:LocalDate,week:Int):Range {
  val monday=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week.toLong())
  return Range(if(week==0)today else monday,monday.plusWeeks(1))
 }
 fun days(range:Range):List<LocalDate> =
  generateSequence(range.start){it.plusDays(1)}.takeWhile{it<range.endExclusive}.toList()
 fun groups(items:List<CalendarItem>):List<Group> =
  items.groupBy {
   if(it.media.type=="series"&&it.media.key.isNotBlank()) "${it.date}:${it.media.key}:${it.season}"
   else it.id
  }.values.map { Group(it.sortedWith(compareBy({it.episode},{it.id}))) }
   .sortedWith(compareBy({it.first.date},{it.items.map{e->e.at}.filter{t->t.isNotBlank()}.minOrNull()?:"~"},{it.first.media.title}))
}
