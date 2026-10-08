package com.pocketds.hub.screens.library

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.offline.DownloadChoice
import com.pocketds.hub.offline.SeriesDownloadChoices
import com.pocketds.hub.offline.StorageBar
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.StorageBarView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ValueAdjusterView
import com.pocketds.hub.ui.ValueRange

/**
 * The series' Download choices (#48), as a panel down the right edge: Keep the next N ready (a stepper from 1 to 10,
 * three to begin with), the rest of the season, everything unwatched, the whole series, and Choose episodes, which
 * is select mode. Each shows how many episodes it adds and what they weigh, and the storage bar under the rows
 * previews the addition for the row the cursor is on, so what a press will do is on the screen before it is pressed.
 * While Keep ready is on the panel offers to turn it off, and says that the files stay.
 */
class SeriesDownloadPanel(context: Context, colors: PocketColors, ringVisible: () -> Boolean) :
    SidePanelView(context, colors, ringVisible, side = true) {

    enum class Pick { KEEP_READY, KEEP_OFF, REST_OF_SEASON, UNWATCHED, WHOLE_SERIES, CHOOSE }

    /** What the rows say now; the screen builds it from the catalog and the device. */
    data class Choices(
        val keepCount: Int,
        val keepOn: Boolean,
        val keep: DownloadChoice,
        val restName: String,
        val rest: DownloadChoice,
        val unwatched: DownloadChoice,
        val whole: DownloadChoice
    )

    /** A row picked. */
    var onPick: ((Pick, keepCount: Int) -> Unit)? = null
    /** The stepper moved: the screen answers with the choices for the new N. */
    var onKeepCount: ((Int) -> Choices)? = null
    /** The cursor is on a row that adds [DownloadChoice] (or on none): the storage bar previews it. */
    var onPreview: ((DownloadChoice?) -> Unit)? = null

    private val storageBar = StorageBarView(context, colors)
    private var keepRow: View? = null
    private var keepCount = 3
    private val rowChoices = HashMap<View, DownloadChoice?>()
    private val ringVisibleCheck = ringVisible
    private val panelColors = colors
    private var format: (Long) -> String = { it.toString() }

    /** Opens the panel for [series]; [text] writes a size. */
    fun show(series: String, choices: Choices, text: (Long) -> String, onClosed: () -> Unit = {}) {
        format = text
        keepCount = choices.keepCount
        build(choices)
        open("Download", series, onClosed)
        focusBody(null)
    }

    /** The storage bar under the rows, for the preview or the plain line. */
    fun showStorage(model: StorageBar.Model, line: String) = storageBar.bind(model, line)

    private fun build(choices: Choices) {
        resetBody()
        rowChoices.clear()
        section("Keep ready")
        // A hand-added view starts the next card: the stepper has one of its own.
        body.addView(ValueAdjusterView(context, panelColors, "Keep the next", ValueRange(1f, 10f, 1f), choices.keepCount.toFloat(),
            { value -> "${value.toInt()} episode${if (value.toInt() == 1) "" else "s"}" }) { value ->
            keepCount = value.toInt()
            onKeepCount?.invoke(keepCount)?.let { updateKeepRow(it) }
        }, LinearLayout.LayoutParams(-1, -2))
        val keep = choice(keepLabel(choices.keepCount, choices.keepOn), keepDetail(choices)) {
            onPick?.invoke(Pick.KEEP_READY, keepCount)
        }
        keepRow = keep
        track(keep, choices.keep)
        if (choices.keepOn) {
            choice("Turn off Keep ready", "The files stay on this device") { onPick?.invoke(Pick.KEEP_OFF, keepCount) }.also { track(it, null) }
        }
        section("Download now")
        choice("Rest of ${choices.restName}", SeriesDownloadChoices.detail(choices.rest, format)) { onPick?.invoke(Pick.REST_OF_SEASON, keepCount) }
            .also { track(it, choices.rest) }
        choice("Everything unwatched", SeriesDownloadChoices.detail(choices.unwatched, format)) { onPick?.invoke(Pick.UNWATCHED, keepCount) }
            .also { track(it, choices.unwatched) }
        choice("Whole series", SeriesDownloadChoices.detail(choices.whole, format)) { onPick?.invoke(Pick.WHOLE_SERIES, keepCount) }
            .also { track(it, choices.whole) }
        section("Pick them yourself")
        setting("Choose episodes", "") { onPick?.invoke(Pick.CHOOSE, keepCount) }.also { track(it, null) }
        footer.removeAllViews()
        (storageBar.parent as? ViewGroup)?.removeView(storageBar)
        footer.addView(storageBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Styler.dpInt(context, 8f); bottomMargin = Styler.dpInt(context, 10f) })
    }

    private fun track(row: View, adds: DownloadChoice?) {
        rowChoices[row] = adds
        FocusDecorator.listen(row, ringVisibleCheck) { view, focused -> if (focused) onPreview?.invoke(rowChoices[view]) }
    }

    /** "3 episodes · 4.1 GB"; with Keep ready on and nothing to add, that the next ones are ready. */
    private fun keepDetail(choices: Choices) =
        if (choices.keepOn && choices.keep.isEmpty) "The next ${choices.keepCount} are on this device or on their way"
        else SeriesDownloadChoices.detail(choices.keep, format)

    private fun keepLabel(count: Int, on: Boolean) = if (on) "Keeping the next $count ready" else "Keep the next $count ready"

    /** The stepper moved: the Keep row's words follow, in place, so the stepper keeps the cursor. */
    private fun updateKeepRow(choices: Choices) {
        val row = keepRow as? ViewGroup ?: return
        val copy = row.getChildAt(0) as? ViewGroup ?: return
        (copy.getChildAt(0) as? TextView)?.text = keepLabel(choices.keepCount, choices.keepOn)
        val detail = keepDetail(choices)
        if (copy.childCount > 1) (copy.getChildAt(1) as? TextView)?.text = detail
        row.contentDescription = "${keepLabel(choices.keepCount, choices.keepOn)}, $detail"
        rowChoices[row] = choices.keep
        onPreview?.invoke(choices.keep)
    }

    /** The row words, for a test or a screen reader's check. */
    val rowTexts: List<String> get() = rows.map { it.contentDescription?.toString().orEmpty() }
}
