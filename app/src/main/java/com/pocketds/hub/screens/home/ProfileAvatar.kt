package com.pocketds.hub.screens.home

import com.pocketds.hub.model.JellyfinUser
import java.util.Locale

/**
 * A Jellyfin profile as the Glass picker draws it: its initial on a colour of
 * its own, as the prototype's "Who is watching?" tiles are.
 *
 * Jellyfin gives profiles no colour, so one is chosen here, the same way every
 * time: the profiles in alphabetical order take the palette in order. Four
 * profiles always get four different colours, which hashing each id could not
 * promise, and the same household always gets the same colours, whatever
 * order the hub lists it in. Pure, so both rules are pinned by JVM tests.
 */
object ProfileAvatar {
    /** The prototype's four, in its order, then more for a bigger household. */
    val PALETTE: IntArray = intArrayOf(
        0xFF8B7BFF.toInt(), 0xFF2CC4AD.toInt(), 0xFFF2A541.toInt(), 0xFFFF6B7D.toInt(),
        0xFF5AA9FF.toInt(), 0xFFB8E06A.toInt(), 0xFFE58BE0.toInt(), 0xFFFFD166.toInt()
    )

    /** Each profile's colour by its id, worked out from the whole set. */
    fun colors(users: List<JellyfinUser>): Map<String, Int> =
        users.distinctBy { it.id }
            .sortedWith(compareBy({ it.name.trim().lowercase(Locale.ROOT) }, { it.id }))
            .mapIndexed { index, user -> user.id to PALETTE[index % PALETTE.size] }
            .toMap()

    /** The letter on the tile: the first letter or digit of the name, else its first character. */
    fun initial(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        var index = 0
        while (index < trimmed.length) {
            val point = trimmed.codePointAt(index)
            if (Character.isLetterOrDigit(point)) return String(Character.toChars(point)).uppercase(Locale.ROOT)
            index += Character.charCount(point)
        }
        return String(Character.toChars(trimmed.codePointAt(0)))
    }
}
