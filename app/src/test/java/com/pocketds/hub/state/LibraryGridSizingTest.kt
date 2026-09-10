package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryGridSizingTest {
    @Test fun `rail width changes seven columns to six without crushing cards`() {
        assertEquals(7, LibraryGridSizing.columns(1_572, 24, 2f))
        assertEquals(6, LibraryGridSizing.columns(1_328, 24, 2f))
    }

    @Test fun `small windows keep at least two usable columns`() {
        assertEquals(2, LibraryGridSizing.columns(430, 24, 2f))
    }

    @Test fun `pre-layout keeps the configured maximum`() {
        assertEquals(7, LibraryGridSizing.columns(0, 0, 2f))
    }
}
