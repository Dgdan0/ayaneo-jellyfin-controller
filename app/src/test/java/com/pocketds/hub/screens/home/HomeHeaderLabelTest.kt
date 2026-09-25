package com.pocketds.hub.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeaderLabelTest {
    @Test fun page_header_owns_home_title_and_content_names_the_person() {
        assertEquals("Hello Dgdan", HomeHeaderLabel.forUser("Dgdan"))
        assertEquals("Hello Sam", HomeHeaderLabel.forUser("  Sam  "))
        assertEquals("Hello", HomeHeaderLabel.forUser("  "))
        assertEquals("Hello", HomeHeaderLabel.forUser(null, missingProfile = true))
    }
}
