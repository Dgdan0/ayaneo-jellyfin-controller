package com.pocketds.hub.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OfflineTransferLogicTest {
    @Test fun partialContentAppendsToAPartialFile() {
        assertEquals(TransferWriteMode.APPEND, transferWriteMode(400, 1_000, 206))
    }

    @Test fun fullResponseRestartsInsteadOfAppendingDuplicateBytes() {
        assertEquals(TransferWriteMode.RESTART, transferWriteMode(400, 1_000, 200))
    }

    @Test fun rangeNotSatisfiableCompletesOnlyAnExactFile() {
        assertEquals(TransferWriteMode.COMPLETE, transferWriteMode(1_000, 1_000, 416))
        expectFailure<IllegalStateException> { transferWriteMode(999, 1_000, 416) }
    }

    @Test fun impossibleStoredLengthIsRejected() {
        expectFailure<IllegalArgumentException> { transferWriteMode(1_001, 1_000, 206) }
    }

    @Test fun resumedRangesMustBeginAtTheStoredByteAndKeepTheSameTotal() {
        assertEquals(true, validContentRange(400, 1_000, 206, "bytes 400-999/1000"))
        assertEquals(false, validContentRange(400, 1_000, 206, "bytes 0-999/1000"))
        assertEquals(false, validContentRange(400, 1_000, 206, "bytes 400-1199/1200"))
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
