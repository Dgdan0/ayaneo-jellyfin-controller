package com.pocketds.hub.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JobSlotTest {

    @Test
    fun `busy while the job runs and idle once it finishes`() = runTest {
        val slot = JobSlot()
        val gate = CompletableDeferred<Unit>()
        assertTrue(slot.launch(this) { gate.await() })
        advanceUntilIdle()
        assertTrue(slot.isBusy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(slot.isBusy)
    }

    @Test
    fun `a second launch is refused while the first is running`() = runTest {
        val slot = JobSlot()
        var runs = 0
        slot.launch(this) { runs++; awaitCancellation() }
        assertFalse(slot.launch(this) { runs++ })
        advanceUntilIdle()
        assertEquals(1, runs)
        slot.cancel()
    }

    @Test
    fun `cancelling the screen scope cannot leave the slot stuck busy`() = runTest {
        // The bug this replaces: busy = true, the screen hides, onHide cancels
        // the request, and the "busy = false" after the await never runs --
        // so the Request button, the Transfers poll loop and Save stayed dead.
        val screen = TestScope(StandardTestDispatcher(testScheduler))
        val slot = JobSlot()
        slot.launch(screen) { awaitCancellation() }
        advanceUntilIdle()
        assertTrue(slot.isBusy)

        screen.coroutineContext.cancelChildren()
        advanceUntilIdle()
        assertFalse(slot.isBusy)
        assertTrue(slot.launch(screen) { })
    }

    @Test
    fun `the block already sees the slot busy on an immediate dispatcher`() = runTest {
        // Screens run on Dispatchers.Main.immediate: the block starts before
        // launch returns. A hint redraw at its top must read "busy".
        val slot = JobSlot()
        var seenBusy = false
        val immediate = TestScope(UnconfinedTestDispatcher(testScheduler))
        slot.launch(immediate) { seenBusy = slot.isBusy; awaitCancellation() }
        assertTrue(seenBusy)
        slot.cancel()
    }

    @Test
    fun `onIdle runs once whether the job finished or was cancelled`() = runTest {
        val slot = JobSlot()
        var idle = 0
        slot.launch(this, onIdle = { idle++ }) { }
        advanceUntilIdle()
        slot.launch(this, onIdle = { idle++ }) { awaitCancellation() }
        advanceUntilIdle()
        slot.cancel()
        advanceUntilIdle()
        assertEquals(2, idle)
        assertFalse(slot.isBusy)
    }
}
