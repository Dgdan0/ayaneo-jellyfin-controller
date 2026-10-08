package com.pocketds.hub.reader

import android.view.View
import android.widget.SeekBar
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ScreenComfort
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.ValueAdjusterView
import com.pocketds.hub.ui.ValueRange

/**
 * The Comfort sheet (#16, X3), one glass sheet in every reader: brightness and
 * warmth, and in a book the screen kept on while narration plays. (The black
 * page is the Dark theme in Appearance, #47.) A change shows at once and is kept for every reader ([ComfortSettings]);
 * [onChange] hands it to the reader to draw.
 */
object ComfortSheet {
    private val BRIGHTNESS = ValueRange(ScreenComfort.MIN_BRIGHTNESS, 1f, 0.05f)
    private val WARMTH = ValueRange(0f, 1f, 0.1f)

    fun show(panel: SidePanelView, colors: PocketColors, kind: ReaderKind, onChange: (ScreenComfort) -> Unit, focusTag: String? = null) {
        val context = panel.context
        var value = ComfortSettings.load(context)
        fun change(next: ScreenComfort) {
            value = next
            ComfortSettings.save(context, next)
            onChange(next)
        }
        panel.resetBody(keepScroll = focusTag != null)
        panel.open("Comfort", "Every reader · dims and warms this app's page, not the screen's light")
        val brightness = ValueAdjusterView(context, colors, "Brightness", BRIGHTNESS, value.brightness, ScreenComfort::brightnessLabel) {
            change(value.copy(brightness = it))
        }.apply { tag = "brightness" }
        panel.body.addView(brightness)
        panel.body.addView(ValueAdjusterView(context, colors, "Warmth", WARMTH, value.warmth, ScreenComfort::warmthLabel) {
            change(value.copy(warmth = it))
        }.apply { tag = "warmth" })
        if (kind == ReaderKind.BOOK) {
            panel.choice("Keep the screen on while narrating", if (value.awakeWhileNarrating) "On" else "Off", value.awakeWhileNarrating) {
                change(value.copy(awakeWhileNarrating = !value.awakeWhileNarrating))
                show(panel, colors, kind, onChange, "awake")
            }.tag = "awake"
        }
        panel.note("Left and right change brightness and warmth. Every reader opens this way until you change it.")
        // The cursor starts on the brightness slider itself, where left and right change it at once;
        // on its "−" they would do nothing (ValueAdjusterView steps only from its slider).
        val focusables = panel.body.getFocusables(View.FOCUS_FORWARD)
        panel.focusBody(focusables.firstOrNull { focusTag != null && isTagged(it, focusTag) }
            ?: brightness.getFocusables(View.FOCUS_FORWARD).firstOrNull { it is SeekBar })
    }

    /** A row, or the slider inside a tagged adjuster. */
    private fun isTagged(view: View, tag: String): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.tag == tag) return true
            current = current.parent as? View
        }
        return false
    }
}
