package com.pocketds.hub.ui

/**
 * Where each option of a [BlobSegmentedView] sits while the blob moves.
 *
 * Pure so the arithmetic is tested on the JVM: the selected option is [grow]
 * wider than its text needs, and during a change the old option gives that
 * width back as the new one takes it. The blob is drawn between the old and new
 * option's *current* bounds, so it lands exactly on the new option however the
 * widths have moved underneath it.
 */
object SegmentGeometry {
    data class Span(val left: Float, val right: Float) {
        val width: Float get() = right - left
    }

    /** How much of [grow] each option has, [progress] of the way from [from] to [to]. */
    fun growth(count: Int, from: Int, to: Int, progress: Float): List<Float> = List(count) { index ->
        when (index) {
            // The interpolator overshoots past 1, which briefly swells the new
            // option too. The old one only ever shrinks back to its text.
            to -> progress.coerceAtLeast(0f)
            from -> (1f - progress).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    fun spans(natural: List<Float>, growth: List<Float>, grow: Float, pad: Float): List<Span> {
        var x = pad
        return natural.mapIndexed { index, width ->
            val w = width + grow * growth.getOrElse(index) { 0f }
            Span(x, x + w).also { x += w }
        }
    }

    fun total(spans: List<Span>, pad: Float): Float = (spans.lastOrNull()?.right ?: pad) + pad

    /** The blob, [progress] of the way from option [from] to option [to]. */
    fun blob(spans: List<Span>, from: Int, to: Int, progress: Float): Span? {
        val target = spans.getOrNull(to) ?: return null
        val start = spans.getOrNull(from) ?: return target
        fun mix(a: Float, b: Float) = a + (b - a) * progress
        return Span(mix(start.left, target.left), mix(start.right, target.right))
    }
}
