package com.pocketds.hub.input

/**
 * The whole input layer, as one pure object.
 *
 * Views hand it primitives -- a key code, six axis values, a clock reading, the
 * fact that something was tapped -- and it emits [PadAction]s. Nothing above it
 * knows about key codes, dead zones, hat axes or Schmitt triggers, and nothing
 * inside it knows about Android, so the feel is tuned against unit tests rather
 * than by redeploying to the handheld.
 *
 * Three things it is responsible for that are easy to get wrong:
 *
 *  * **Repeat is clock-driven, not event-driven.** A held stick stops producing
 *    events and a held hat never produced more than one, so [onTick] must be
 *    called every frame while [idle] is false.
 *  * **Duplicate sources are latched away.** One L2 pull on this hardware
 *    produces both an analog `ABS_BRAKE` movement and a `BUTTON_L2` key event.
 *    The first source seen wins and the other is dropped for good.
 *  * **The stick is not latched against the D-pad.** They are two legitimate
 *    ways to move that the same person uses in the same sitting. Only the hat
 *    and the synthesised `KEYCODE_DPAD_*` compete, because those are two
 *    reports of one physical press.
 */
class PadEventRouter(
    private val gamepadMap: GamepadMap = GamepadMap(),
    private val stick: AnalogRepeater = AnalogRepeater(),
    private val hat: AnalogRepeater = AnalogRepeater.forHat(),
    private val leftTrigger: TriggerLatch = TriggerLatch(),
    private val rightTrigger: TriggerLatch = TriggerLatch(),
    val inputMode: InputModeTracker = InputModeTracker(),
    private val emit: (PadAction) -> Unit
) {

    /**
     * Latches are **per device**, not global.
     *
     * The competition being resolved is one physical press reported twice by one
     * device -- a hat movement and a synthesised key code from the same pad. Two
     * different devices are not competing, they are two input methods, and a
     * global latch would let the first one seen silence the other for good.
     *
     * This is not hypothetical: this handheld exposes a second virtual device
     * with a `dpad` source alongside the controller. One stray key code from it
     * -- from an accessibility service, a paired keyboard, an injected event --
     * would kill the real D-pad until the app was restarted.
     */
    private val dpadSources = HashMap<Int, SourceLatch>()
    private val leftTriggerSources = HashMap<Int, SourceLatch>()
    private val rightTriggerSources = HashMap<Int, SourceLatch>()

    private fun latch(map: HashMap<Int, SourceLatch>, deviceId: Int): SourceLatch =
        map.getOrPut(deviceId) { SourceLatch() }

    // Last known axis values, so the frame ticker has something to pump.
    private var lastX = 0f
    private var lastY = 0f
    private var lastHatX = 0f
    private var lastHatY = 0f

    /** Which device the pumped axis values came from, for the hat latch. */
    private var motionDeviceId = DEVICE_UNKNOWN

    /**
     * @return true if the event was consumed -- including when it was
     *   deliberately dropped as a duplicate source, because the framework must
     *   not then go and do something else with it.
     */
    fun onKeyDown(keyCode: Int, deviceId: Int = DEVICE_UNKNOWN): Boolean {
        val action = gamepadMap.actionFor(keyCode) ?: return false
        inputMode.onDirectional()

        when (action) {
            is PadAction.Step ->
                if (!latch(dpadSources, deviceId).accept(SourceLatch.SOURCE_KEYS)) return true

            is PadAction.Page -> {
                val map = if (action.direction == Direction.UP) {
                    leftTriggerSources
                } else {
                    rightTriggerSources
                }
                if (!latch(map, deviceId).accept(SourceLatch.SOURCE_KEYS)) return true
            }

            else -> Unit
        }

        emit(action)
        return true
    }

    /**
     * A generic motion event carrying joystick axes.
     *
     * @param y positive is down, matching Android's AXIS_Y.
     * @return true if these axes are ours to act on.
     */
    fun onMotion(
        x: Float,
        y: Float,
        hatX: Float,
        hatY: Float,
        leftTriggerValue: Float,
        rightTriggerValue: Float,
        nowMs: Long,
        deviceId: Int = DEVICE_UNKNOWN
    ): Boolean {
        motionDeviceId = deviceId
        lastX = x
        lastY = y
        lastHatX = hatX
        lastHatY = hatY

        // Triggers are edge-detected here rather than on the tick: a pull is a
        // discrete event, and re-testing the same held value every frame is how
        // you get a page-turn per frame.
        if (leftTrigger.update(leftTriggerValue) &&
            latch(leftTriggerSources, deviceId).accept(SourceLatch.SOURCE_ANALOG)
        ) {
            inputMode.onDirectional()
            emit(PadAction.Page(Direction.UP))
        }
        if (rightTrigger.update(rightTriggerValue) &&
            latch(rightTriggerSources, deviceId).accept(SourceLatch.SOURCE_ANALOG)
        ) {
            inputMode.onDirectional()
            emit(PadAction.Page(Direction.DOWN))
        }

        pump(nowMs)
        return true
    }

    /** Called every frame from the view layer while [idle] is false. */
    fun onTick(nowMs: Long) = pump(nowMs)

    /** A tap or click arrived, so the focus ring should get out of the way. */
    fun onPointer() {
        inputMode.onPointer()
    }

    /** True when neither the stick nor the hat is held, so the ticker can stop. */
    fun idle(): Boolean = stick.idle() && hat.idle()

    fun reset() {
        stick.reset()
        hat.reset()
        leftTrigger.reset()
        rightTrigger.reset()
        lastX = 0f
        lastY = 0f
        lastHatX = 0f
        lastHatY = 0f
    }

    private companion object {
        const val DEVICE_UNKNOWN = -1
    }

    private fun pump(nowMs: Long) {
        stick.update(lastX, lastY, nowMs)?.let {
            inputMode.onDirectional()
            emit(PadAction.Step(it))
        }
        hat.update(lastHatX, lastHatY, nowMs)?.let {
            if (latch(dpadSources, motionDeviceId).accept(SourceLatch.SOURCE_HAT)) {
                inputMode.onDirectional()
                emit(PadAction.Step(it))
            }
        }
    }
}
