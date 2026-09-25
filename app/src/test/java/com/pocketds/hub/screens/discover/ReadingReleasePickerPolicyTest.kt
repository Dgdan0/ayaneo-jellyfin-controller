package com.pocketds.hub.screens.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingReleasePickerPolicyTest {
    @Test fun oneTargetHasSearchButNoDeadChooser() {
        assertFalse(ReadingReleasePickerPolicy.hasTargetChooser(1))
        assertTrue(ReadingReleasePickerPolicy.hasSearchAction(1))
    }

    @Test fun multipleTargetsCanChooseAndSearch() {
        assertTrue(ReadingReleasePickerPolicy.hasTargetChooser(3))
        assertTrue(ReadingReleasePickerPolicy.hasSearchAction(3))
    }

    @Test fun emptyResultsDistinguishNoMatchesFromSourceErrors() {
        assertEquals("No releases found · Search again later", ReadingReleasePickerPolicy.summary(0, 0, 0, 0, false))
        assertEquals("No releases yet · 2 indexer errors · Try Search again", ReadingReleasePickerPolicy.summary(0, 0, 0, 2, false))
    }
}
