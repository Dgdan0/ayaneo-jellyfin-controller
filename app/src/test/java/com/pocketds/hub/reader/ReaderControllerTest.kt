package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderControllerTest {
    @Test
    fun `start opens controls without moving the publication`() {
        val controller = ReaderController(ReaderProfile.BOOK)

        assertEquals(
            ReaderCommand.ShowOverlay(ReaderOverlay.CONTROLS),
            controller.dispatch(PadAction.Menu)
        )
        assertEquals(ReaderOverlay.CONTROLS, controller.overlay)
    }

    @Test
    fun `dpad navigates content while hidden and focus while controls are visible`() {
        val controller = ReaderController(ReaderProfile.COMIC)

        assertEquals(
            ReaderCommand.Navigate(Direction.RIGHT),
            controller.dispatch(PadAction.Step(Direction.RIGHT))
        )

        controller.dispatch(PadAction.Menu)
        assertEquals(
            ReaderCommand.MoveFocus(Direction.RIGHT),
            controller.dispatch(PadAction.Step(Direction.RIGHT))
        )
    }

    @Test
    fun `back closes a sheet then controls then exits`() {
        val controller = ReaderController(ReaderProfile.BOOK)
        controller.dispatch(PadAction.Secondary)
        assertEquals(ReaderOverlay.NAVIGATOR, controller.overlay)

        assertEquals(
            ReaderCommand.ShowOverlay(ReaderOverlay.CONTROLS),
            controller.dispatch(PadAction.Back)
        )
        assertEquals(
            ReaderCommand.HideControls,
            controller.dispatch(PadAction.Back)
        )
        assertEquals(ReaderCommand.Exit, controller.dispatch(PadAction.Back))
    }

    @Test
    fun `activate advances content while hidden and activates focus while visible`() {
        val controller = ReaderController(ReaderProfile.MANGA)
        assertEquals(ReaderCommand.Advance, controller.dispatch(PadAction.Activate))

        controller.dispatch(PadAction.Menu)
        assertEquals(ReaderCommand.ActivateFocused, controller.dispatch(PadAction.Activate))
    }

    @Test
    fun `select and y open appearance and navigator from the page`() {
        val controller = ReaderController(ReaderProfile.BOOK)
        assertEquals(
            ReaderCommand.ShowOverlay(ReaderOverlay.APPEARANCE),
            controller.dispatch(PadAction.Refresh)
        )
        controller.dispatch(PadAction.Menu)
        assertEquals(
            ReaderCommand.ShowOverlay(ReaderOverlay.NAVIGATOR),
            controller.dispatch(PadAction.Secondary)
        )
    }
}
