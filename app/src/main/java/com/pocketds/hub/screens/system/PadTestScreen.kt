package com.pocketds.hub.screens.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.AxisStats
import com.pocketds.hub.input.KeyRecord
import com.pocketds.hub.input.PadEventLog
import com.pocketds.hub.input.PadFormat
import com.pocketds.hub.input.PadNames
import com.pocketds.hub.input.RestDriftTracker
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Theme
import kotlin.math.abs

/**
 * The input probe. This is the whole of phase A0, and it is a diagnostic
 * instrument rather than a feature.
 *
 * Roughly six things about how this device reports its gamepad are guesses
 * until they are seen on screen, and every navigation decision downstream
 * depends on the answers:
 *
 *  1. Does the vendor accessibility service swallow our face buttons? A service
 *     declaring flagRequestFilterKeyEvents sees keys before the foreground app
 *     and can consume them. Compare this screen with `dev.sh keys off`.
 *  2. Are the sticks in joystick mode at all? If the firmware is in
 *     stick-to-mouse mode, generic motion events never arrive and the AXES panel
 *     stays empty while the sticks plainly move.
 *  3. Does the D-pad arrive as key codes, as HAT_X/HAT_Y, or as both? Both is
 *     common, and would double-step every navigation.
 *  4. Is the physically bottom face button really KEYCODE_BUTTON_A? Firmware
 *     sometimes maps it to the right-hand one.
 *  5. Which spelling do the Hall triggers use -- LTRIGGER/RTRIGGER, BRAKE/GAS,
 *     BUTTON_L2/R2, or several at once?
 *  6. Do the Hall sticks report a driver dead zone of 0 and drift anyway? Leave
 *     the sticks alone for thirty seconds and read the drift line.
 *
 * Ships in release builds. It stays the fastest answer to "the controller is
 * behaving oddly" long after phase A0 is over.
 */
class PadTestScreen : Screen {

    override val title: String = "Pad"

    private lateinit var context: Context
    private lateinit var colors: PocketColors

    private val axes = AxisStats()
    private val keys = PadEventLog()
    private val restDrift = RestDriftTracker()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Which axes each device actually declares, cached per deviceId.
     *
     * Without this, every watched axis gets recorded on every event and the ones
     * the hardware does not have show up as a row of perfect zeros with a sample
     * count in the thousands -- which reads as "this axis exists and never
     * moves" rather than "this axis is not there".
     */
    private val axesByDevice = HashMap<Int, IntArray>()

    /** When the current quiet period began, for the "clean" verdict. */
    private var restWindowStartedMs = SystemClock.uptimeMillis()



    private lateinit var headerView: TextView
    private lateinit var axisView: TextView
    private lateinit var keyView: TextView
    private lateinit var verdictView: TextView

    /** Set once a motion event has actually carried joystick axes. Answers (2). */
    private var sawJoystickMotion = false

    /** Which sources have delivered a D-pad step. Answers (3). */
    private val dpadSources = linkedSetOf<String>()

    private val repaint = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REPAINT_MS)
        }
    }

    // ------------------------------------------------------------------ view

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        context = host.viewContext
        colors = Theme.colors(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        headerView = label(11f).apply { setTextColor(colors.mutedText) }
        root.addView(headerView)

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }

        axisView = label(13f)
        columns.addView(scroller(axisView), LinearLayout.LayoutParams(0, MATCH, 0.42f))

        keyView = label(13f)
        columns.addView(
            scroller(keyView),
            LinearLayout.LayoutParams(0, MATCH, 0.58f).apply { leftMargin = dp(10) }
        )
        root.addView(columns)

        verdictView = label(12f).apply {
            setTextColor(colors.primaryText)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(verdictView)
        root.addView(footer())

        render()
        return root
    }

    private fun scroller(child: View): ScrollView = ScrollView(context).apply {
        isFillViewport = true
        addView(child, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun label(sizeSp: Float) = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = sizeSp
        setTextColor(colors.primaryText)
        setLineSpacing(0f, 1.05f)
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(button("Reset") {
            axes.reset()
            keys.clear()
            restDrift.reset()
            restWindowStartedMs = SystemClock.uptimeMillis()
            axesByDevice.clear()
            dpadSources.clear()
            sawJoystickMotion = false
            DebugLog.log("pad", "probe reset")
            render()
        })

        addView(button("Copy report") {
            val text = report()
            copyToClipboard(text)
            // Also to logcat: the on-screen scrollback has to be swiped through a
            // screenshot at a time, and the device clipboard cannot be read over
            // adb. This makes `dev.sh trace` the whole answer.
            text.lineSequence().forEach { if (it.isNotBlank()) DebugLog.log("report", it) }
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        })

        addView(label(10f).apply {
            text = "BACK is passed through so you can still leave. It is logged too."
            setTextColor(colors.mutedText)
            setPadding(dp(12), 0, 0, 0)
        })
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onShow() {
        handler.post(repaint)
        restWindowStartedMs = SystemClock.uptimeMillis()
        DebugLog.log("pad", "probe shown; " + describeDevices().replace("\n", " | "))
    }

    override fun onHide() {
        handler.removeCallbacks(repaint)
    }

    // ------------------------------------------------------------------ input

    /** @return true if consumed. BACK never is, so the app stays exitable. */
    override fun onRawKeyEvent(event: KeyEvent): Boolean {
        keys.add(
            KeyRecord(
                down = event.action == KeyEvent.ACTION_DOWN,
                keyCode = event.keyCode,
                scanCode = event.scanCode,
                source = event.source,
                deviceId = event.deviceId,
                repeatCount = event.repeatCount,
                atMs = event.eventTime
            )
        )
        if (isDpadKey(event.keyCode)) dpadSources.add("key codes")
        // Everything is recorded, but a few keys are passed through or the probe
        // becomes a room with no door: BACK so the app can be left, and the
        // shoulders so another section can be reached without touching the screen.
        return event.keyCode !in PASS_THROUGH
    }

    override fun onDestroyView() {
        handler.removeCallbacks(repaint)
    }

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint("L1/R1", "Leave", PadAction.Section(1))
    )

    override fun onRawMotionEvent(event: MotionEvent): Boolean {
        if (!PadNames.has(event.source, PadNames.SOURCE_JOYSTICK)) return false
        sawJoystickMotion = true

        val present = supportedAxes(event)

        // Reading the batched history is not an optimisation here: the extremes
        // of a fast flick live in those samples, and the drift figure is only as
        // good as the samples it saw.
        for (h in 0 until event.historySize) {
            for (axis in present) {
                axes.record(axis, event.getHistoricalAxisValue(axis, h))
            }
            restDrift.update(stickMagnitude { event.getHistoricalAxisValue(it, h) }, event.eventTime)
        }
        for (axis in present) {
            axes.record(axis, event.getAxisValue(axis))
        }
        restDrift.update(stickMagnitude { event.getAxisValue(it) }, event.eventTime)

        val hat = maxOf(
            abs(event.getAxisValue(PadNames.AXIS_HAT_X)),
            abs(event.getAxisValue(PadNames.AXIS_HAT_Y))
        )
        if (hat > 0.5f) dpadSources.add("hat axes")
        return true
    }

    /** Only the axes this device declares, so absent ones do not show as zeros. */
    private fun supportedAxes(event: MotionEvent): IntArray =
        axesByDevice.getOrPut(event.deviceId) {
            val device = event.device
            PadNames.WATCHED_AXES
                .filter { device != null && device.getMotionRange(it, event.source) != null }
                .toIntArray()
        }

    /** The largest absolute value across the four stick axes. */
    private inline fun stickMagnitude(value: (Int) -> Float): Float = maxOf(
        abs(value(PadNames.AXIS_X)), abs(value(PadNames.AXIS_Y)),
        abs(value(PadNames.AXIS_Z)), abs(value(PadNames.AXIS_RZ))
    )

    private fun isDpadKey(keyCode: Int) = keyCode == PadNames.KEYCODE_DPAD_UP ||
        keyCode == PadNames.KEYCODE_DPAD_DOWN ||
        keyCode == PadNames.KEYCODE_DPAD_LEFT ||
        keyCode == PadNames.KEYCODE_DPAD_RIGHT

    // ----------------------------------------------------------------- render

    private fun render() {
        headerView.text = describeDevices()

        val readings = axes.snapshot()
        axisView.text = if (readings.isEmpty()) {
            "AXES\n\nnothing yet.\n\nIf the sticks move and this stays\n" +
                "empty, they are not in joystick\nmode. Check: dev.sh pad"
        } else {
            "AXES  (current, then range seen)\n\n" +
                readings.joinToString("\n") { PadFormat.axisLine(it) }
        }

        val recent = keys.recent(KEY_LINES)
        keyView.text = if (recent.isEmpty()) {
            "KEYS\n\npress something."
        } else {
            "KEYS  (newest first, " + keys.size + " held)\n\n" +
                recent.joinToString("\n") { PadFormat.line(it) }
        }

        verdictView.text = verdict()
    }

    private fun verdict(): String {
        val dpad = when {
            dpadSources.isEmpty() -> "not seen yet"
            dpadSources.size > 1 ->
                "BOTH (" + dpadSources.joinToString(" + ") + ") -- will double-step"
            else -> dpadSources.first()
        }

        val sb = StringBuilder()
        sb.append("joystick motion: ").append(if (sawJoystickMotion) "yes" else "NOT SEEN")
        sb.append("     d-pad via: ").append(dpad)
        sb.append("\nrest drift: ")

        // Deliberately not derived from the AXES range above: that range includes
        // every deliberate push, so using it reports a drift of 1.0 and suggests
        // a dead zone that swallows the whole stick.
        val now = SystemClock.uptimeMillis()
        val samples = restDrift.samples()
        val drift = restDrift.drift()
        val quietSeconds = (now - restWindowStartedMs) / 1000
        when {
            restDrift.settling(now) -> sb.append("settling after a push...")
            // A stick that is genuinely centred stops producing motion events
            // altogether, so zero samples is not "no reading" -- it is the best
            // possible reading, and saying "no data" would be misleading.
            samples == 0 && quietSeconds >= CLEAN_AFTER_SECONDS ->
                sb.append("clean -- no spurious events in ").append(quietSeconds).append("s")
            samples == 0 ->
                sb.append("watching... let go of the sticks (")
                    .append(CLEAN_AFTER_SECONDS - quietSeconds).append("s)")
            else -> {
                sb.append(PadFormat.axisValue(drift))
                sb.append(" over ").append(samples).append(" resting samples")
                sb.append("  ->  dead zone ")
                sb.append(String.format("%.2f", AxisStats.suggestDeadZone(drift)))
            }
        }
        return sb.toString()
    }

    /**
     * What each attached device claims about itself. The `flat` figure is the
     * driver's own declared dead zone, and a Hall stick reporting 0 there while
     * the drift line reads non-zero is precisely the trap this screen exists to
     * expose.
     */
    private fun describeDevices(): String {
        val sb = StringBuilder()
        val metrics = context.resources.displayMetrics
        sb.append("screen ").append(metrics.widthPixels).append("x").append(metrics.heightPixels)
            .append("   density ").append(metrics.density)
            .append("   dpi ").append(metrics.densityDpi)
            .append("   android ").append(Build.VERSION.RELEASE)
            .append("\n")

        val pads = InputDevice.getDeviceIds().asIterable()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter {
                PadNames.has(it.sources, PadNames.SOURCE_GAMEPAD) ||
                    PadNames.has(it.sources, PadNames.SOURCE_JOYSTICK) ||
                    PadNames.has(it.sources, PadNames.SOURCE_DPAD)
            }

        if (pads.isEmpty()) {
            sb.append("no gamepad / joystick / dpad device is attached")
            return sb.toString()
        }

        for (device in pads) {
            sb.append("dev ").append(device.id).append(" \"").append(device.name).append("\"  ")
                .append(PadNames.describeSource(device.sources))
            val flats = PadNames.WATCHED_AXES.asIterable().mapNotNull { axis ->
                val range = device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)
                    ?: device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD)
                if (range == null) null
                else PadNames.axisName(axis) + "[flat " + String.format("%.3f", range.flat) + "]"
            }
            if (flats.isNotEmpty()) sb.append("\n    ").append(flats.joinToString(" "))
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    /** Everything on screen as text, for pasting somewhere with a real keyboard. */
    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine(describeDevices())
        sb.appendLine()
        sb.appendLine(verdict())
        sb.appendLine()
        sb.appendLine("AXES")
        axes.snapshot().forEach { sb.appendLine(PadFormat.axisLine(it)) }
        sb.appendLine()
        sb.appendLine("KEYS (newest first)")
        keys.recent(60).forEach { sb.appendLine(PadFormat.line(it)) }
        return sb.toString()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("pad probe", text))
        DebugLog.log("pad", "probe report copied (" + text.length + " chars)")
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** 20 Hz. Cheap, and fast enough that a stick nudge reads as live. */
        const val REPAINT_MS = 50L
        const val KEY_LINES = 22

        /** Recorded, but not consumed -- see onRawKeyEvent. */
        val PASS_THROUGH = setOf(
            PadNames.KEYCODE_BACK,
            PadNames.KEYCODE_BUTTON_L1,
            PadNames.KEYCODE_BUTTON_R1
        )

        /** Untouched for this long with no events at all means the sticks are clean. */
        const val CLEAN_AFTER_SECONDS = 15L
    }
}
