package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.Theme

/**
 * A reader's keys, shown (#16, X1). The app's hint bar is hidden while a
 * reader is open, so each reader carries its own row of hints inside its
 * controls, and a Controls sheet lists every key. Both come from
 * [ReaderPadMap], the table the keys themselves follow, so neither can drift
 * from what the keys do.
 */
object ReaderKeys {
    /**
     * The app's hint bar, over a page: its chips are buttons too, handed to
     * [onAction] as the key would be. Classic draws it in the dark video
     * palette, since the reader's bars are dark whatever the theme.
     */
    fun row(context: Context, colors: PocketColors, onAction: (PadAction) -> Unit): HintBarView {
        val glass = Theme.isGlass(context)
        return HintBarView(context, if (glass) colors else Theme.onVideo(context), glass).apply { this.onAction = onAction }
    }

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
