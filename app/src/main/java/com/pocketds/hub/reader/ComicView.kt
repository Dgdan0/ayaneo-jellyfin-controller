package com.pocketds.hub.reader

import kotlin.math.abs

/** How a comic page is fitted to the screen. */
enum class ComicFit(val stored: String, val label: String) {
    WHOLE("whole", "Whole page"),
    WIDTH("width", "Fit page width"),
    /** Fit the width and read down the page in steps worked out from its shape (#16, C2). */
    THIRDS("thirds", "Read in thirds");

    companion object {
        fun fromStored(raw: String?, fallback: ComicFit): ComicFit = entries.firstOrNull { it.stored == raw } ?: fallback
    }
}

/**
 * How a series is read (#16, C1): its fit, and a direction chosen over the
 * library's (null keeps the library's, "rtl" for Manga). Kept per series, so
 * the next issue, or a reading list passing through, opens the same way.
 */
data class ComicView(val fit: ComicFit, val direction: String? = null) {
    fun encode(): String = fit.stored + "|" + direction.orEmpty()

    companion object {
        /** Thirds unless the person chose otherwise: a portrait page at fit width is about 92% of print. */
        val DEFAULT_FIT = ComicFit.THIRDS

        fun decode(raw: String?, fallback: ComicFit): ComicView {
            if (raw.isNullOrBlank()) return ComicView(fallback)
            val fit = ComicFit.fromStored(raw.substringBefore('|'), fallback)
            val direction = raw.substringAfter('|', "").takeIf { it == "ltr" || it == "rtl" }
            return ComicView(fit, direction)
        }
    }
}

/**
 * The page and the third last shown in an issue, so opening it again puts you
 * on the same third, not the top of the page.
 */
data class ComicPlace(val sourceItemId: String, val page: Int, val step: Int) {
    fun encode(): String = "$sourceItemId|$page|$step"

    /** The step to open [page] of [publication] at, out of [steps]: the saved one only on the same page. */
    fun stepFor(publication: String, page: Int, steps: Int): Int =
        if (publication == sourceItemId && page == this.page) step.coerceIn(0, (steps - 1).coerceAtLeast(0)) else 0

    companion object {
        fun decode(raw: String?): ComicPlace? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val page = parts[1].toIntOrNull() ?: return null
            val step = parts[2].toIntOrNull() ?: return null
            if (parts[0].isBlank() || page < 0 || step < 0) return null
            return ComicPlace(parts[0], page, step)
        }
    }
}

/**
 * A zoom kept from page to page (#16, C1, the owner's decision): zoom in a
 * little and turn the page, and the next page opens at the same zoom and the
 * same horizontal place, at its top. [factor] is how far past the fit's own
 * scale; [anchorX] is where the middle of the view sits across the page, 0 to
 * 1. At 1 the fit reads as it always does (thirds step, whole pages show).
 */
data class ComicZoom(val factor: Float = 1f, val anchorX: Float = 0.5f) {
    val active: Boolean get() = abs(factor - 1f) > CLOSE

    /** The scale for a page whose fit has the scale [base], within the view's limits. */
    fun scaleFor(base: Float, min: Float, max: Float): Float = (base * factor).coerceIn(min, max)

    /**
     * Where the middle of the view goes on a new page [pageWidth] by
     * [pageHeight], when [visibleWidth] by [visibleHeight] of it shows (in
     * the page's pixels): the same place across, at the top going forward
     * and at the bottom going back. A page narrower than the view is centred.
     */
    fun center(pageWidth: Float, pageHeight: Float, visibleWidth: Float, visibleHeight: Float, atEnd: Boolean): Pair<Float, Float> {
        val x = if (visibleWidth >= pageWidth) pageWidth / 2f
            else (anchorX * pageWidth).coerceIn(visibleWidth / 2f, pageWidth - visibleWidth / 2f)
        val y = when {
            visibleHeight >= pageHeight -> pageHeight / 2f
            atEnd -> pageHeight - visibleHeight / 2f
            else -> visibleHeight / 2f
        }
        return x to y
    }

    companion object {
        /** Within 2% of the fit is the fit: a zoom pinched back to it ends. */
        const val CLOSE = 0.02f

        fun of(scale: Float, base: Float, centerX: Float, pageWidth: Float): ComicZoom {
            if (base <= 0f || pageWidth <= 0f) return ComicZoom()
            val factor = scale / base
            return if (abs(factor - 1f) <= CLOSE) ComicZoom() else ComicZoom(factor, (centerX / pageWidth).coerceIn(0f, 1f))
        }
    }
}
