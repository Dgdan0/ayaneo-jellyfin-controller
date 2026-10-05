package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SidePanelView

/**
 * A reader's keys, shown (#16, X1). The app's hint bar is hidden while a
 * reader is open, so each reader carries its own row of hints inside its
 * controls, and a Controls sheet lists every key. Both come from
 * [ReaderPadMap], the table the keys themselves follow, so neither can drift
 * from what the keys do.
 */
object ReaderKeys {
    /** The row's height in dp: the hint bar's own, which each reader's bar leaves room for. */
    val ROW_DP: Int = HintBarView.HEIGHT_DP.toInt()

    /**
     * The app's hint bar, over a page: its chips are buttons too, handed to
     * [onAction] as the key would be.
     */
    fun row(context: Context, colors: PocketColors, onAction: (PadAction) -> Unit): HintBarView =
        HintBarView(context, colors).apply { this.onAction = onAction }

    /** The Controls sheet in [panel]: every key that does something while reading, and what. */
    fun show(panel: SidePanelView, state: ReaderPadState) {
        panel.resetBody()
        panel.open("Controls", when (state.kind) {
            ReaderKind.COMIC -> "What the pad does while you read a comic"
            ReaderKind.BOOK -> "What the pad does while you read"
            ReaderKind.AUDIOBOOK -> "What the pad does while you listen"
        })
        ReaderPadMap.sheet(state.kind, state).forEach { panel.keys(it.keys, it.does) }
        when (state.kind) {
            ReaderKind.COMIC -> panel.note("With the controls open, A presses the control in focus and B closes them. Select always leaves.")
            ReaderKind.BOOK -> panel.note("In the menu, A presses the control in focus, the D-pad moves between them, and B leaves the book.")
            ReaderKind.AUDIOBOOK -> Unit
        }
        panel.focusBody()
    }
}
