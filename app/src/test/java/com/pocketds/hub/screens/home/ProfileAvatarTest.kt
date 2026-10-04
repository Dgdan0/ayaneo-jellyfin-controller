package com.pocketds.hub.screens.home

import com.pocketds.hub.model.JellyfinUser
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileAvatarTest {

    private val household = listOf(
        JellyfinUser("d1", "Dgdan", selected = true),
        JellyfinUser("h2", "Horim"),
        JellyfinUser("a3", "Adirimo"),
        JellyfinUser("h4", "Hadas")
    )

    @Test fun `the four profiles take the prototype's colours whatever order the hub sends`() {
        val expected = mapOf("a3" to 0xFF8B7BFF.toInt(), "d1" to 0xFF2CC4AD.toInt(), "h4" to 0xFFF2A541.toInt(), "h2" to 0xFFFF6B7D.toInt())
        assertEquals(expected, ProfileAvatar.colors(household))
        assertEquals(expected, ProfileAvatar.colors(household.reversed()))
    }

    @Test fun `every profile has a colour, distinct until the palette runs out`() {
        val many = (1..10).map { JellyfinUser("id$it", "Profile ${'A' + it}") }
        val colors = ProfileAvatar.colors(many)
        assertEquals(10, colors.size)
        assertEquals(ProfileAvatar.PALETTE.size, colors.values.take(ProfileAvatar.PALETTE.size).distinct().size)
        // Two profiles with one name still differ by id, and in a stable order.
        val twins = ProfileAvatar.colors(listOf(JellyfinUser("b", "Sam"), JellyfinUser("a", "Sam")))
        assertEquals(ProfileAvatar.PALETTE[0], twins["a"])
        assertEquals(ProfileAvatar.PALETTE[1], twins["b"])
    }

    @Test fun `the initial is the first letter or digit, in capitals`() {
        assertEquals("D", ProfileAvatar.initial("Dgdan"))
        assertEquals("H", ProfileAvatar.initial("  hadas"))
        assertEquals("ד", ProfileAvatar.initial("דן"))
        assertEquals("4", ProfileAvatar.initial("42"))
        assertEquals("B", ProfileAvatar.initial("🙂 Bob"))
        assertEquals("🙂", ProfileAvatar.initial("🙂"))
        assertEquals("?", ProfileAvatar.initial("   "))
    }
}
