package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable
data class CalendarItem(
 val id:String="", val service:String="", val media:MediaRef=MediaRef(),
 val date:String="", val at:String="", val releaseType:String="",
 val season:Int=0, val episode:Int=0, val episodeTitle:String="",
 val overview:String="", val hasFile:Boolean=false
)
@Serializable
data class CalendarResponse(
 val start:String="", val end:String="", val timezone:String="", val generatedAt:String="",
 val items:List<CalendarItem> = emptyList(), val partial:List<PartialFailure> = emptyList()
)
