package com.pocketds.hub.ui

import android.content.Context
import android.view.ViewGroup
import com.pocketds.hub.input.PadAction

/**
 * Short confirmations stay centred; consumption controls opt into the shared side panel.
 *
 * A side sheet's questions ([confirm], [ask]) still open as the centred card
 * (GLASS_PLAN.md, and the side panel's design): a second, centred overlay
 * beside the sheet, made the first time one is asked. [isOpen], [onPad] and
 * [dismiss] answer for both, so a screen keeps talking to one overlay.
 */
class ChoiceOverlay(context:Context,colors:PocketColors,ringVisible:()->Boolean,sidePanel:Boolean=false) : SidePanelView(context,colors,ringVisible,sidePanel) {
    data class Choice(val id:String,val label:String,val detail:String="",val danger:Boolean=false,val selected:Boolean=false)
    private val sheet = sidePanel
    private val ring = ringVisible
    /** A side sheet's centred card for its questions. */
    private var question: ChoiceOverlay? = null

    override val isOpen: Boolean get() = super.isOpen || question?.isOpen == true

    override fun onPad(action: PadAction): Boolean {
        question?.takeIf { it.isOpen }?.let { return it.onPad(action) }
        return super.onPad(action)
    }

    override fun dismiss() {
        question?.dismiss()
        super.dismiss()
    }

    /**
     * A question with a few answers, harmless first: on a side sheet it opens
     * as the centred card, and the menu that asked it closes.
     */
    fun ask(title:String,subtitle:String,choices:List<Choice>,startIndex:Int?=0,onCancel:()->Unit={},onPick:(String)->Unit) {
        if (!sheet) return show(title, subtitle, choices, startIndex, onCancel, onPick)
        if (super.isOpen) super.dismiss()
        val card = question ?: ChoiceOverlay(context, colors, ring).also { made ->
            question = made
            (parent as? ViewGroup)?.addView(made, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        card.show(title, subtitle, choices, startIndex, onCancel, onPick)
    }
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
    ) = ask(
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
