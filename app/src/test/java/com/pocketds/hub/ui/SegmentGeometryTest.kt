package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentGeometryTest {
    private val natural = listOf(50f, 70f, 60f)

    @Test
    fun `the selected option is wider by the grow amount and the others pack beside it`() {
        val spans = SegmentGeometry.spans(natural, SegmentGeometry.growth(3, -1, 1, 1f), grow = 10f, pad = 3f)
        assertEquals(listOf(3f, 53f, 133f), spans.map { it.left })
        assertEquals(80f, spans[1].width)
        assertEquals(196f, SegmentGeometry.total(spans, 3f))
    }

    @Test
    fun `separate pills keep their gap while the chosen one grows`() {
        val spans = SegmentGeometry.spans(natural, SegmentGeometry.growth(3, -1, 0, 1f), grow = 10f, pad = 0f, gap = 8f)
        assertEquals(listOf(0f, 68f, 146f), spans.map { it.left })
        assertEquals(60f, spans[0].width)
        assertEquals(206f, SegmentGeometry.total(spans, 0f))
    }

    @Test
    fun `halfway through a change both options share the extra width`() {
        val growth = SegmentGeometry.growth(3, 0, 2, 0.5f)
        assertEquals(listOf(0.5f, 0f, 0.5f), growth)
        val spans = SegmentGeometry.spans(natural, growth, 10f, 0f)
        assertEquals(55f, spans[0].width)
        assertEquals(65f, spans[2].width)
    }

    @Test
    fun `an overshoot swells the new option but never shrinks the old one below its text`() {
        assertEquals(listOf(0f, 0f, 1.2f), SegmentGeometry.growth(3, 0, 2, 1.2f))
    }

    @Test
    fun `the blob travels between the current bounds and lands exactly on the new option`() {
        val start = SegmentGeometry.spans(natural, SegmentGeometry.growth(3, 0, 2, 0f), 10f, 0f)
        assertEquals(SegmentGeometry.Span(0f, 60f), SegmentGeometry.blob(start, 0, 2, 0f))
        val end = SegmentGeometry.spans(natural, SegmentGeometry.growth(3, 0, 2, 1f), 10f, 0f)
        assertEquals(SegmentGeometry.Span(120f, 190f), SegmentGeometry.blob(end, 0, 2, 1f))
    }

    @Test
    fun `a first selection appears in place and no selection draws no blob`() {
        val spans = SegmentGeometry.spans(natural, SegmentGeometry.growth(3, -1, 1, 1f), 10f, 0f)
        assertEquals(spans[1], SegmentGeometry.blob(spans, -1, 1, 0.3f))
        assertNull(SegmentGeometry.blob(spans, 0, -1, 1f))
    }
}
