package com.pocketds.hub.playback

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import kotlin.math.abs

/** A small live timing control that leaves the scene and active subtitle visible. */
internal class SubtitleOffsetOverlay(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : FrameLayout(context) {
    private val valueView: TextView
    private val timingControl: LinearLayout
    private val seekBar: SeekBar
    private val resetButton: TextView
    private val doneButton: TextView
    private lateinit var rangeButton: TextView
    private var offsetMillis = 0L
    private var wideRange = false
    private var selectedControl = CONTROL_TIMING
    private var onChange: ((Long) -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var opener: View? = null

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        // The strip alone receives touch; transport and video stay interactive.
        isClickable = true
        clipChildren = false

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 13f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { /* Keep card taps away from outside-to-close. */ }
        }
        addView(
            card,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        heading.addView(TextView(context).apply {
            text = "Subtitle timing"
            textSize = 13f
            setTextColor(colors.primaryText)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        valueView = TextView(context).apply {
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(colors.accent)
        }
        heading.addView(valueView)

        timingControl = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 10f, baseFill = colors.stripBackground)
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = "Subtitle offset slider"
            Styler.makeFocusable(this)
        }
        card.addView(
            timingControl,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(3)
            }
        )
        timingControl.addView(stepButton("−", -FINE_STEP_MILLIS), LinearLayout.LayoutParams(dp(44), dp(44)))
        seekBar = SeekBar(context).apply {
            max = SubtitleTimingPolicy.steps(wideRange)
            isFocusable = false
            progressTintList = ColorStateList.valueOf(colors.accent)
            progressBackgroundTintList = ColorStateList.valueOf(colors.cardSurface)
            thumbTintList = ColorStateList.valueOf(colors.accent)
            contentDescription = "Subtitle timing, earlier to later"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setOffset(SubtitleTimingPolicy.progressToOffset(progress, wideRange), notify = true)
                }

                override fun onStartTrackingTouch(bar: SeekBar) = select(CONTROL_TIMING)
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        timingControl.addView(seekBar, LinearLayout.LayoutParams(0, dp(46), 1f))
        timingControl.addView(stepButton("+", FINE_STEP_MILLIS), LinearLayout.LayoutParams(dp(44), dp(44)))

        val rangeLabels = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(rangeLabels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18)))
        listOf("Earlier", "In sync", "Later").forEachIndexed { index, label ->
            rangeLabels.addView(TextView(context).apply {
                text = label
                textSize = 10f
                gravity = when (index) { 0 -> Gravity.START; 1 -> Gravity.CENTER; else -> Gravity.END }
                setTextColor(colors.mutedText)
            }, LinearLayout.LayoutParams(0, dp(18), 1f))
        }

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        card.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        rangeButton = actionButton("±5 s", "Switch subtitle timing range") {
            select(CONTROL_RANGE)
            wideRange = !wideRange
            rangeButton.text = if (wideRange) "±60 s" else "±5 s"
            rangeButton.contentDescription = "Subtitle timing range ${if (wideRange) "plus or minus 60" else "plus or minus 5"} seconds. Switch range"
            seekBar.max = SubtitleTimingPolicy.steps(wideRange)
            setOffset(offsetMillis, notify = true)
        }
        footer.addView(rangeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        resetButton = actionButton("Reset", "Reset subtitle timing") {
            select(CONTROL_RESET)
            setOffset(0, notify = true)
        }
        doneButton = actionButton("Close", "Close subtitle timing") {
            select(CONTROL_DONE)
            close()
        }
        footer.addView(resetButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
        footer.addView(doneButton, LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    fun show(value: Long, onChange: (Long) -> Unit, onClose: () -> Unit) {
        this.onChange = onChange
        this.onClose = onClose
        opener = rootView.findFocus()
        visibility = View.VISIBLE
        bringToFront()
        wideRange = kotlin.math.abs(value) > SubtitleTimingPolicy.range(false)
        rangeButton.text = if (wideRange) "±60 s" else "±5 s"
        seekBar.max = SubtitleTimingPolicy.steps(wideRange)
        setOffset(value, notify = false)
        select(CONTROL_TIMING)
    }

    fun dismiss() {
        visibility = View.GONE
        onChange = null
        onClose = null
        opener?.takeIf { it.isShown && it.isFocusable }?.requestFocus()
        opener = null
    }

    fun focusTiming() { if (isOpen) select(CONTROL_TIMING) }

    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        if (action == PadAction.Back) { close(); return true }
        if (!hasFocus()) return false
        when (action) {
            is PadAction.Step -> when (action.direction) {
                Direction.UP -> select(CONTROL_TIMING)
                Direction.DOWN -> if (selectedControl == CONTROL_TIMING) select(CONTROL_RANGE) else return false
                Direction.LEFT -> if (selectedControl == CONTROL_TIMING) {
                    adjust(-FINE_STEP_MILLIS)
                } else {
                    select((selectedControl - 1).coerceAtLeast(CONTROL_RANGE))
                }
                Direction.RIGHT -> if (selectedControl == CONTROL_TIMING) {
                    adjust(FINE_STEP_MILLIS)
                } else {
                    select((selectedControl + 1).coerceAtMost(CONTROL_DONE))
                }
            }
            is PadAction.Page -> when (action.direction) {
                Direction.UP -> adjust(-COARSE_STEP_MILLIS)
                Direction.DOWN -> adjust(COARSE_STEP_MILLIS)
                else -> Unit
            }
            PadAction.Primary -> return false
            PadAction.Activate -> when (selectedControl) {
                CONTROL_RANGE -> rangeButton.performClick()
                CONTROL_RESET -> setOffset(0, notify = true)
                CONTROL_DONE -> close()
                else -> return false
            }
            else -> return false
        }
        return true
    }

    private fun select(control: Int) {
        selectedControl = control
        val selected = when (control) {
            CONTROL_RANGE -> rangeButton
            CONTROL_RESET -> resetButton
            CONTROL_DONE -> doneButton
            else -> timingControl
        }
        selected.requestFocus()
    }

    private fun adjust(delta: Long) = setOffset(offsetMillis + delta, notify = true)

    private fun setOffset(value: Long, notify: Boolean) {
        offsetMillis = SubtitleTimingPolicy.clamp(value, wideRange)
        val progress = SubtitleTimingPolicy.offsetToProgress(offsetMillis, wideRange)
        if (seekBar.progress != progress) seekBar.progress = progress
        valueView.text = SubtitleTimingPolicy.label(offsetMillis)
        valueView.contentDescription = spokenLabel(offsetMillis)
        if (notify) onChange?.invoke(offsetMillis)
    }

    private fun close() {
        val callback = onClose
        dismiss()
        callback?.invoke()
    }

    private fun stepButton(label: String, delta: Long) = TextView(context).apply {
        text = label
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(colors.primaryText)
        isClickable = true
        contentDescription = if (delta < 0) "Show subtitles 0.1 seconds earlier" else
            "Show subtitles 0.1 seconds later"
        setOnClickListener {
            select(CONTROL_TIMING)
            adjust(delta)
        }
    }

    private fun actionButton(label: String, description: String, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(colors.primaryText)
        background = Styler.cardBackground(context, colors, cornerDp = 9f, baseFill = colors.stripBackground)
        contentDescription = description
        Styler.makeFocusable(this)
        setOnClickListener { click() }
    }

    private fun spokenLabel(value: Long): String = when {
        value == 0L -> "No subtitle offset"
        value < 0 -> "%.1f seconds earlier".format(abs(value) / 1_000.0)
        else -> "%.1f seconds later".format(value / 1_000.0)
    }

    private fun dp(value: Int): Int = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val CONTROL_TIMING = 0
        const val CONTROL_RANGE = 1
        const val CONTROL_RESET = 2
        const val CONTROL_DONE = 3
        const val FINE_STEP_MILLIS = 100L
        const val COARSE_STEP_MILLIS = 1_000L
    }
}
