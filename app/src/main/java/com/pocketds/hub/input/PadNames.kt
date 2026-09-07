package com.pocketds.hub.input

/**
 * Readable names for the raw integers a gamepad arrives as.
 *
 * The constants are redeclared here rather than read from android.view.KeyEvent
 * and android.view.MotionEvent on purpose. Those classes are stubs in a JVM
 * unit test and throw on access, and this project's convention is that logic
 * takes primitives so it can be tested without a device. The values are part of
 * the Android platform's public ABI and cannot change.
 *
 * Unknown codes render as the number rather than "unknown", because on a probe
 * screen the number is the useful part -- a vendor may well emit something not
 * in this table, and that is exactly what we are looking for.
 */
object PadNames {

    // --- MotionEvent axes -------------------------------------------------
    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_Z = 11
    const val AXIS_RX = 12
    const val AXIS_RY = 13
    const val AXIS_RZ = 14
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16
    const val AXIS_LTRIGGER = 17
    const val AXIS_RTRIGGER = 18
    const val AXIS_THROTTLE = 19
    const val AXIS_RUDDER = 20
    const val AXIS_WHEEL = 21
    const val AXIS_GAS = 22
    const val AXIS_BRAKE = 23

    /**
     * The axes worth watching on a games handheld, in the order a reader wants
     * them: sticks, then hat, then triggers in both of the spellings devices
     * use for them.
     *
     * Both spellings are listed because which one a device reports is exactly
     * the thing we do not know yet. Hall triggers have been seen as
     * LTRIGGER/RTRIGGER, as BRAKE/GAS, as button keycodes, and as more than one
     * of those at once.
     */
    val WATCHED_AXES = intArrayOf(
        AXIS_X, AXIS_Y,
        AXIS_Z, AXIS_RZ,
        AXIS_RX, AXIS_RY,
        AXIS_HAT_X, AXIS_HAT_Y,
        AXIS_LTRIGGER, AXIS_RTRIGGER,
        AXIS_BRAKE, AXIS_GAS,
        AXIS_THROTTLE, AXIS_RUDDER, AXIS_WHEEL
    )

    private val AXIS_NAMES = mapOf(
        AXIS_X to "X", AXIS_Y to "Y",
        AXIS_Z to "Z", AXIS_RX to "RX", AXIS_RY to "RY", AXIS_RZ to "RZ",
        AXIS_HAT_X to "HAT_X", AXIS_HAT_Y to "HAT_Y",
        AXIS_LTRIGGER to "LTRIGGER", AXIS_RTRIGGER to "RTRIGGER",
        AXIS_THROTTLE to "THROTTLE", AXIS_RUDDER to "RUDDER",
        AXIS_WHEEL to "WHEEL", AXIS_GAS to "GAS", AXIS_BRAKE to "BRAKE"
    )

    fun axisName(axis: Int): String = AXIS_NAMES[axis] ?: "AXIS_$axis"

    // --- InputDevice sources ---------------------------------------------
    // A source is a class in the low bits plus flags, so membership is tested
    // with (source and SOURCE_X) == SOURCE_X, never equality.
    const val SOURCE_KEYBOARD = 0x00000101
    const val SOURCE_DPAD = 0x00000201
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_TOUCHSCREEN = 0x00001002
    const val SOURCE_MOUSE = 0x00002002
    const val SOURCE_STYLUS = 0x00004002
    const val SOURCE_TRACKBALL = 0x00010004
    const val SOURCE_TOUCHPAD = 0x00100008
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_ROTARY_ENCODER = 0x00400000

    private val SOURCE_NAMES = listOf(
        SOURCE_KEYBOARD to "keyboard",
        SOURCE_DPAD to "dpad",
        SOURCE_GAMEPAD to "gamepad",
        SOURCE_TOUCHSCREEN to "touchscreen",
        SOURCE_MOUSE to "mouse",
        SOURCE_STYLUS to "stylus",
        SOURCE_TRACKBALL to "trackball",
        SOURCE_TOUCHPAD to "touchpad",
        SOURCE_JOYSTICK to "joystick",
        SOURCE_ROTARY_ENCODER to "rotary"
    )

    /** Every source class present in [source], in a stable order. */
    fun sourceNames(source: Int): List<String> =
        SOURCE_NAMES.filter { (bits, _) -> source and bits == bits }.map { it.second }

    fun describeSource(source: Int): String {
        val names = sourceNames(source)
        val hex = "0x%08x".format(source)
        return if (names.isEmpty()) hex else names.joinToString("+") + " " + hex
    }

    fun has(source: Int, sourceClass: Int): Boolean = source and sourceClass == sourceClass

    // --- KeyEvent key codes ----------------------------------------------
    const val KEYCODE_BACK = 4
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_C = 98
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100
    const val KEYCODE_BUTTON_Z = 101
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
    const val KEYCODE_BUTTON_THUMBL = 106
    const val KEYCODE_BUTTON_THUMBR = 107
    const val KEYCODE_BUTTON_START = 108
    const val KEYCODE_BUTTON_SELECT = 109
    const val KEYCODE_BUTTON_MODE = 110

    private val KEY_NAMES = mapOf(
        3 to "HOME", KEYCODE_BACK to "BACK",
        KEYCODE_DPAD_UP to "DPAD_UP", KEYCODE_DPAD_DOWN to "DPAD_DOWN",
        KEYCODE_DPAD_LEFT to "DPAD_LEFT", KEYCODE_DPAD_RIGHT to "DPAD_RIGHT",
        KEYCODE_DPAD_CENTER to "DPAD_CENTER",
        24 to "VOLUME_UP", 25 to "VOLUME_DOWN",
        66 to "ENTER", 82 to "MENU", 111 to "ESCAPE",
        KEYCODE_BUTTON_A to "BUTTON_A", KEYCODE_BUTTON_B to "BUTTON_B",
        KEYCODE_BUTTON_C to "BUTTON_C", KEYCODE_BUTTON_X to "BUTTON_X",
        KEYCODE_BUTTON_Y to "BUTTON_Y", KEYCODE_BUTTON_Z to "BUTTON_Z",
        KEYCODE_BUTTON_L1 to "BUTTON_L1", KEYCODE_BUTTON_R1 to "BUTTON_R1",
        KEYCODE_BUTTON_L2 to "BUTTON_L2", KEYCODE_BUTTON_R2 to "BUTTON_R2",
        KEYCODE_BUTTON_THUMBL to "BUTTON_THUMBL", KEYCODE_BUTTON_THUMBR to "BUTTON_THUMBR",
        KEYCODE_BUTTON_START to "BUTTON_START", KEYCODE_BUTTON_SELECT to "BUTTON_SELECT",
        KEYCODE_BUTTON_MODE to "BUTTON_MODE"
    )

    fun keyName(keyCode: Int): String = KEY_NAMES[keyCode] ?: "KEYCODE_$keyCode"

    /** True for the codes this app intends to navigate with. */
    fun isNavigationKey(keyCode: Int): Boolean = keyCode in KEY_NAMES && keyCode !in setOf(3, 24, 25)
}
