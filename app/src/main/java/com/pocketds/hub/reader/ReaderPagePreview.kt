package com.pocketds.hub.reader

data class ReaderPageTransform(val scale: Float, val x: Float, val y: Float)

/** Fits the already-rendered page; the reader's measured viewport never changes. */
object ReaderPagePreview {
    fun fit(width: Int, height: Int, top: Int = 0, bottom: Int = 0, right: Int = 0, margin: Int = 0): ReaderPageTransform {
        if (width <= 0 || height <= 0) return ReaderPageTransform(1f, 0f, 0f)
        val left = margin.coerceIn(0, width - 1).toFloat()
        val upper = (top.toLong() + margin).coerceIn(0, (height - 1).toLong()).toFloat()
        val availableWidth = (width.toLong() - right - margin - left.toInt()).coerceIn(1, width.toLong()).toFloat()
        val availableHeight = (height.toLong() - bottom - margin - upper.toInt()).coerceIn(1, height.toLong()).toFloat()
        val scale = minOf(1f, availableWidth / width, availableHeight / height)
        return ReaderPageTransform(
            scale,
            (left + (availableWidth - width * scale) / 2).coerceAtMost(width - width * scale),
            (upper + (availableHeight - height * scale) / 2).coerceAtMost(height - height * scale)
        )
    }
}
