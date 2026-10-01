package com.pocketds.hub.ui

import android.content.Context

/** Short confirmations stay centred; consumption controls opt into the shared side panel. */
class ChoiceOverlay(context:Context,colors:PocketColors,ringVisible:()->Boolean,sidePanel:Boolean=false) : SidePanelView(context,colors,ringVisible,sidePanel) {
    data class Choice(val id:String,val label:String,val detail:String="",val danger:Boolean=false,val selected:Boolean=false)
    /** [startIndex] null lands on the row last chosen in this menu (see SidePanelView.focusBody). */
    fun show(title:String,subtitle:String,choices:List<Choice>,startIndex:Int?=null,onCancel:()->Unit={},onPick:(String)->Unit) {
        resetBody()
        open(title,subtitle,onCancel)
        val views=choices.map { entry -> choice(entry.label,entry.detail,entry.selected,entry.danger) {
            dismiss();onPick(entry.id)
        }}
        focusBody(startIndex?.let(views::getOrNull))
    }

    /**
     * One option per value, the current one checked and under the cursor.
     *
     * Speed, aspect, subtitle appearance and narration speed each built this by
     * hand and wrote the word "Selected" into the detail line, while every other
     * menu drew the check mark the panel already provides.
     */
    fun <T> pickValue(
        title: String,
        subtitle: String,
        values: List<T>,
        current: T,
        label: (T) -> String,
        detail: (T) -> String = { "" },
        onCancel: () -> Unit = {},
        onPick: (T) -> Unit
    ) {
        val choices = values.mapIndexed { index, value ->
            Choice(index.toString(), label(value), detail(value), selected = value == current)
        }
        show(title, subtitle, choices, values.indexOf(current).coerceAtLeast(0), onCancel) { id ->
            onPick(values[id.toInt()])
        }
    }

    /**
     * A question with one consequential answer. The harmless answer is first
     * and starts under the cursor, so the reflex press after opening it by
     * accident does nothing.
     */
    fun confirm(
        title: String,
        subtitle: String,
        action: String,
        keep: String = "Cancel",
        actionDetail: String = "",
        danger: Boolean = true,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit
    ) = show(
        title, subtitle,
        listOf(Choice(KEEP, keep), Choice(CONFIRM, action, actionDetail, danger = danger)),
        startIndex = 0,
        onCancel = onCancel
    ) { if (it == CONFIRM) onConfirm() else onCancel() }

    private companion object {
        const val KEEP = "keep"
        const val CONFIRM = "confirm"
    }
}
