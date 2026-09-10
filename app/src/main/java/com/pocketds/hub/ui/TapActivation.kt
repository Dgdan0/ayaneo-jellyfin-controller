package com.pocketds.hub.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Makes a focusable controller card activate on its first pointer tap.
 *
 * On this device the trackpad's synthetic touch first gives a focusable card
 * focus and only a second tap reaches View's click path. Handling the completed
 * tap here removes that extra step. RecyclerView can still intercept a moved
 * gesture for scrolling, which delivers ACTION_CANCEL and prevents activation.
 */
fun View.activateOnTap(action: () -> Unit) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var moved = false

    setOnClickListener { action() }
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                view.isPressed = true
                view.requestFocus()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                    moved = true
                    view.isPressed = false
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.isPressed = false
                if (!moved) view.performClick()
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                moved = true
                true
            }
            else -> true
        }
    }
}
