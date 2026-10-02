package com.pocketds.hub.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BidiTest {
    @Test
    fun `each part of a mixed line keeps its own direction`() {
        assertEquals("\u2068בלאגן\u2069 · \u206811 min\u2069", Bidi.join(listOf("בלאגן", "11 min"), " · "))
        assertEquals("\u2068NEXT UP\u2069 · \u2068S1E6\u2069", Bidi.isolateParts("NEXT UP · S1E6", " · "))
    }
}
