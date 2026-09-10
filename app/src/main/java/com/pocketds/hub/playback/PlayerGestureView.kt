package com.pocketds.hub.playback

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Owns only the video surface gestures; player controls are layered above it. */
internal class PlayerGestureView(
    context: Context,
    private val listener: Listener
) : View(context) {
    enum class Side { LEFT, RIGHT, CENTER }

    interface Listener {
        fun onSingleTap()
        fun onDoubleTap(side: Side)
        fun onHorizontalStart()
        fun onHorizontalMove(fraction: Float)
        fun onHorizontalEnd(cancelled: Boolean)
        fun onVerticalStart(side: Side)
        fun onVerticalMove(side: Side, fraction: Float)
        fun onVerticalEnd(side: Side, cancelled: Boolean)
    }

    private enum class Drag { NONE, HORIZONTAL, VERTICAL }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var drag = Drag.NONE
    private var verticalSide = Side.LEFT

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()

        override fun onDoubleTap(event: MotionEvent): Boolean {
            val side = when {
                event.x < width * 0.42f -> Side.LEFT
                event.x > width * 0.58f -> Side.RIGHT
                else -> Side.CENTER
            }
            listener.onDoubleTap(side)
            return true
        }
    })

    init {
        isClickable = true
        contentDescription = "Video gestures"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun performClick(): Boolean {
        super.performClick()
        listener.onSingleTap()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                drag = Drag.NONE
                verticalSide = if (event.x < width / 2f) Side.LEFT else Side.RIGHT
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (drag == Drag.NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    drag = if (abs(dx) >= abs(dy)) Drag.HORIZONTAL else Drag.VERTICAL
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (drag == Drag.HORIZONTAL) listener.onHorizontalStart()
                    else listener.onVerticalStart(verticalSide)
                }
                when (drag) {
                    Drag.HORIZONTAL -> listener.onHorizontalMove(dx / width.coerceAtLeast(1).toFloat())
                    Drag.VERTICAL -> listener.onVerticalMove(
                        verticalSide,
                        (downY - event.y) / height.coerceAtLeast(1).toFloat()
                    )
                    Drag.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (drag) {
                    Drag.HORIZONTAL -> listener.onHorizontalEnd(cancelled = false)
                    Drag.VERTICAL -> listener.onVerticalEnd(verticalSide, cancelled = false)
                    Drag.NONE -> Unit
                }
                drag = Drag.NONE
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                when (drag) {
                    Drag.HORIZONTAL -> listener.onHorizontalEnd(cancelled = true)
                    Drag.VERTICAL -> listener.onVerticalEnd(verticalSide, cancelled = true)
                    Drag.NONE -> Unit
                }
                drag = Drag.NONE
                return true
            }
        }
        return true
    }
}
