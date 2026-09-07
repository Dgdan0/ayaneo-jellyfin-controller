package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormModelTest {

    private fun requestForm() = FormModel(
        listOf(
            FormRow.Choice("profile", "Quality", listOf("Any", "HD-720p", "HD-1080p"), selected = 2),
            FormRow.Choice("folder", "Folder", listOf("Daniel\\Movies", "Marvel\\Movies")),
            FormRow.Action("submit", "Request"),
            FormRow.Action("cancel", "Cancel")
        )
    )

    @Test
    fun `the cursor starts on a field, never on a button`() {
        // Opening a dialog with "Request" already under the cursor turns a
        // reflex A press into a request with all the defaults.
        assertEquals(0, requestForm().index)
    }

    @Test
    fun `a form of nothing but buttons still starts somewhere valid`() {
        val model = FormModel(listOf(FormRow.Action("ok", "OK"), FormRow.Action("no", "Cancel")))
        assertEquals(0, model.index)
    }

    @Test
    fun `moving the cursor clamps rather than wrapping`() {
        // Holding down must not deposit the cursor back on the first field --
        // or worse, wrap past the end onto a submit button.
        val model = requestForm()
        model.move(-1)
        assertEquals(0, model.index)
        repeat(20) { model.move(1) }
        assertEquals(3, model.index)
    }

    @Test
    fun `values wrap, because a profile list is a ring`() {
        // The opposite rule to the cursor, deliberately: pressing left from the
        // first of seven quality profiles should reach the last one.
        val model = requestForm()
        model.focus(1)
        assertEquals(0, model.selectedIndex("folder"))
        model.adjust(-1)
        assertEquals(1, model.selectedIndex("folder"))
        model.adjust(1)
        assertEquals(0, model.selectedIndex("folder"))
    }

    @Test
    fun `a single-option choice does not pretend to change`() {
        val model = FormModel(listOf(FormRow.Choice("only", "Server", listOf("Radarr"))))
        assertFalse(model.adjust(1))
    }

    @Test
    fun `A on a field changes the field and submits nothing`() {
        // The important half is the null: a press that lands on a field must
        // never be mistaken for pressing the button.
        val model = requestForm()
        assertNull(model.activate())
        assertEquals(0, model.selectedIndex("profile"))
    }

    @Test
    fun `A on an action returns its id`() {
        val model = requestForm()
        model.focus(2)
        assertEquals("submit", model.activate())
    }

    @Test
    fun `left and right do nothing on a button`() {
        val model = requestForm()
        model.focus(2)
        assertFalse(model.adjust(1))
    }

    @Test
    fun `toggles flip from any of the three presses`() {
        val model = FormModel(listOf(FormRow.Toggle("s1", "Season 1")))
        assertFalse(model.isChecked("s1"))
        model.activate()
        assertTrue(model.isChecked("s1"))
        model.adjust(1)
        assertFalse(model.isChecked("s1"))
        model.adjust(-1)
        assertTrue(model.isChecked("s1"))
    }

    @Test
    fun `checked ids come back in row order`() {
        val model = FormModel(
            listOf(
                FormRow.Toggle("season:1", "Season 1", checked = true),
                FormRow.Toggle("season:2", "Season 2"),
                FormRow.Toggle("season:3", "Season 3", checked = true),
                FormRow.Toggle("other", "Something else", checked = true)
            )
        )
        assertEquals(listOf("season:1", "season:3"), model.checkedIds("season:"))
    }

    @Test
    fun `setChecked does not move the cursor`() {
        val model = FormModel(
            listOf(FormRow.Toggle("a", "A"), FormRow.Toggle("b", "B"))
        )
        model.focus(1)
        model.setChecked("a", true)
        assertEquals(1, model.index)
        assertTrue(model.isChecked("a"))
    }

    @Test
    fun `replacing rows keeps the cursor on the same field`() {
        // Ticking "all seasons" hides the per-season rows. The cursor must stay
        // on the row the user is looking at rather than jumping to the top.
        val model = FormModel(
            listOf(
                FormRow.Choice("profile", "Quality", listOf("A", "B")),
                FormRow.Toggle("all", "All seasons"),
                FormRow.Toggle("season:1", "Season 1")
            )
        )
        model.focus(1)
        model.replace(
            listOf(
                FormRow.Choice("profile", "Quality", listOf("A", "B")),
                FormRow.Toggle("all", "All seasons", checked = true)
            )
        )
        assertEquals(1, model.index)
        assertEquals("all", model.current()?.id)
    }

    @Test
    fun `replacing rows falls back to a field when the old one is gone`() {
        val model = FormModel(
            listOf(FormRow.Toggle("gone", "Gone"), FormRow.Action("ok", "OK"))
        )
        model.focus(0)
        model.replace(listOf(FormRow.Choice("new", "New", listOf("x")), FormRow.Action("ok", "OK")))
        assertEquals(0, model.index)
        assertEquals("new", model.current()?.id)
    }

    @Test
    fun `a choice reports the label and detail for what is selected`() {
        val row = FormRow.Choice(
            "folder", "Folder",
            options = listOf("Daniel\\Movies", "Marvel\\Movies"),
            details = listOf("209 GB free", "209 GB free"),
            selected = 1
        )
        assertEquals("Marvel\\Movies", row.value)
        assertEquals("209 GB free", row.detail)
    }

    @Test
    fun `a choice with no details does not blow up`() {
        val row = FormRow.Choice("x", "X", options = listOf("one"))
        assertEquals("", row.detail)
    }

    @Test
    fun `focus ignores an out of range position`() {
        val model = requestForm()
        model.focus(99)
        assertEquals(0, model.index)
    }
}
