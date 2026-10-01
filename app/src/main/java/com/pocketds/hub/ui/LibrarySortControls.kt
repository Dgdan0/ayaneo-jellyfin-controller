package com.pocketds.hub.ui

import android.content.Context
import android.view.View
import com.pocketds.hub.settings.SortPreference

/** One immediate-apply panel, with durable selection independent of controller focus. */
object LibrarySortPanel {
    fun control(context:Context,colors:PocketColors,open:()->Unit) = CenteredIconTextView(context).apply {
        textSize=12f; minimumHeight=Styler.dpInt(context,48f)
        setTextColor(colors.primaryText);setPadding(Styler.dpInt(context,12f),0,Styler.dpInt(context,12f),0)
        setCenteredIcon(AppIconDrawable(AppIcon.SORT,colors.mutedText),Styler.dpInt(context,20f),Styler.dpInt(context,8f))
        background=Styler.chipBackground(context,colors);Styler.makeFocusable(this);activateOnTap(open)
    }
    fun label(fields:List<Pair<String,String>>,value:SortPreference) =
        "${fields.firstOrNull { it.first==value.field }?.second ?: value.field} ${if(value.ascending) "↑" else "↓"}"

    fun show(panel:SidePanelView,opener:View,fields:List<Pair<String,String>>,value:SortPreference,
             apply:(SortPreference)->Unit,onDismiss:()->Unit,focus:String=value.field) {
        if (!panel.isOpen) opener.requestFocus()
        panel.resetBody();panel.open("Sort library",onDismiss={opener.requestFocus();onDismiss()})
        val rows=linkedMapOf<String,View>()
        fields.forEach { (id,label) -> rows[id]=panel.choice(label,selected=id==value.field) {
            val next=if(id==value.field)value else SortPreference.forField(id)
            if(next!=value)apply(next)
            show(panel,opener,fields,next,apply,onDismiss,id)
        } }
        listOf(true to "Ascending",false to "Descending").forEach { (ascending,label) ->
            val id=if(ascending)"asc" else "desc"
            val detail=when(value.field) {
                "name","title","author","series" -> if(ascending)"A to Z" else "Z to A"
                "added","release","played","last_read","year" -> if(ascending)"Oldest first" else "Newest first"
                else -> if(ascending)"Lowest first" else "Highest first"
            }
            rows[id]=panel.choice(label,detail,selected=value.ascending==ascending) {
                val next=value.copy(ascending=ascending)
                if(next!=value)apply(next)
                show(panel,opener,fields,next,apply,onDismiss,id)
            }
        }
        panel.focusBody(rows[focus])
    }
}
