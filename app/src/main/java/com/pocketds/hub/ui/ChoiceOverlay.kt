package com.pocketds.hub.ui

import android.content.Context

/** Short confirmations stay centred; consumption controls opt into the shared side panel. */
class ChoiceOverlay(context:Context,colors:PocketColors,ringVisible:()->Boolean,sidePanel:Boolean=false) : SidePanelView(context,colors,ringVisible,sidePanel) {
    data class Choice(val id:String,val label:String,val detail:String="",val danger:Boolean=false,val selected:Boolean=false)
    fun show(title:String,subtitle:String,choices:List<Choice>,startIndex:Int=0,onCancel:()->Unit={},onPick:(String)->Unit) {
        resetBody()
        open(title,subtitle,onCancel)
        val views=choices.map { entry -> choice(entry.label,entry.detail,entry.selected,entry.danger) {
            dismiss();onPick(entry.id)
        }}
        focusBody(views.getOrNull(startIndex))
    }
}
