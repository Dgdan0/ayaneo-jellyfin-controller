package com.pocketds.hub.screens.discover

import org.junit.Assert.assertEquals
import org.junit.Test

class PipelineChipTest {

    @Test
    fun `each stage state reads as one of four tones`() {
        assertEquals(PipelineChip.Tone.DONE, PipelineChip.Tone.of("done"))
        assertEquals(PipelineChip.Tone.ACTIVE, PipelineChip.Tone.of("active"))
        // Stuck is as much a problem as failed: both need someone to look.
        assertEquals(PipelineChip.Tone.FAILED, PipelineChip.Tone.of("failed"))
        assertEquals(PipelineChip.Tone.FAILED, PipelineChip.Tone.of("stuck"))
        // Anything else, a step not reached or one the hub could not ask about, waits.
        assertEquals(PipelineChip.Tone.PENDING, PipelineChip.Tone.of("pending"))
        assertEquals(PipelineChip.Tone.PENDING, PipelineChip.Tone.of("unknown"))
        assertEquals(PipelineChip.Tone.PENDING, PipelineChip.Tone.of(""))
    }
}
