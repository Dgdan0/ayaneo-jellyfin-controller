package com.pocketds.hub.screens.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingActionHintTest {
    @Test fun icon_only_actions_have_short_specific_controller_labels() {
        assertEquals("Add to Want to Read", ReadingActionHint.label("list:want", description = "Add Dark Matter to Want to Read"))
        assertEquals("Remove from Want to Read", ReadingActionHint.label("list:want", description = "Remove Dark Matter from Want to Read"))
        assertEquals("More actions", ReadingActionHint.label("list:more"))
    }

    @Test fun primary_action_keeps_its_mode_without_long_narration_metadata() {
        assertEquals("Read along", ReadingActionHint.label("entry", text = "Read along · Unabridged narration"))
        assertEquals("Continue", ReadingActionHint.label("entry", text = "Continue"))
        assertEquals("Change format", ReadingActionHint.label("format"))
    }

    @Test fun volume_chips_and_the_continue_card_say_what_they_do() {
        assertEquals("Show volume", ReadingActionHint.label("list:volume:1"))
        assertEquals("Continue reading", ReadingActionHint.label("continue:8338"))
        assertEquals("Open", ReadingActionHint.label("list:book:rw_1"))
    }
}
