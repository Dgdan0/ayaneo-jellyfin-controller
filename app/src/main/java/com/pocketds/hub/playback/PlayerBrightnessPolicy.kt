package com.pocketds.hub.playback

/** Dims only this app's video surface; it never adjusts the device's two backlights. */
object PlayerBrightnessPolicy {
    fun overlayAlpha(level: Float): Float = (1f - level.coerceIn(0f, 1f)) * .85f
}
