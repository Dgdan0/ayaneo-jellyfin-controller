package com.pocketds.hub.screens.library

import org.junit.Assert.*
import org.junit.Test

class LibrarySortChoiceStateTest {
    @Test fun current_field_and_direction_remain_selected_when_focus_moves() {
        val state = LibrarySortChoiceState("title", ascending = false)
        assertTrue(state.isFieldSelected("title"))
        assertFalse(state.isFieldSelected("date"))
        assertTrue(state.isDirectionSelected("title", candidateAscending = false))
        assertFalse(state.isDirectionSelected("title", candidateAscending = true))
        assertEquals(1, state.directionStartIndex("title"))
    }

    @Test fun new_field_starts_on_ascending_without_claiming_it_is_saved() {
        val state = LibrarySortChoiceState("title", ascending = false)
        assertEquals(0, state.directionStartIndex("date"))
        assertFalse(state.isDirectionSelected("date", candidateAscending = true))
        assertFalse(state.isDirectionSelected("date", candidateAscending = false))
    }
}
