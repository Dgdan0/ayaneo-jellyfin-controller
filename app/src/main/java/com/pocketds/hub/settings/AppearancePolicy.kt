package com.pocketds.hub.settings

import com.pocketds.hub.state.ContentMode
import java.net.URI
import java.security.MessageDigest

/**
 * Stable IDs are persisted; names and ordering may change without losing a choice.
 *
 * Pastels, because a colour that fills the Play button and the selected tab
 * has to sit quietly on near-black artwork.
 */
enum class AccentPreset(val id: String, val label: String, val color: Int) {
    TEAL("teal","Teal",0xff3ddbc6.toInt()),
    MINT("mint","Mint",0xff8ee3cf.toInt()),
    SKY("sky","Sky",0xffa5c8ff.toInt()),
    LAVENDER("lavender","Lavender",0xffc7b8ff.toInt()),
    LILAC("lilac","Lilac",0xffe3b8f5.toInt()),
    ROSE("rose","Rose",0xffffb4c6.toInt()),
    PEACH("peach","Peach",0xffffc6a5.toInt()),
    BUTTER("butter","Butter",0xfff6e3a1.toInt()),
    GOLD("gold","Gold",0xffe9c46a.toInt()),
    SAGE("sage","Sage",0xffbfd8b0.toInt());

    /** Text and icons drawn on the accent: a deep shade of it. */
    val ink: Int get() {
        fun channel(shift: Int) = (((color shr shift) and 255) * 0.2f).toInt()
        return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    companion object {
        /** The first palette's names, so a choice made before the pastels keeps its nearest colour. */
        private val retired = mapOf("blue" to SKY, "indigo" to LAVENDER, "violet" to LILAC, "coral" to PEACH,
            "amber" to GOLD, "olive" to SAGE, "green" to MINT, "cyan" to TEAL)
        /** Books are gold until chosen otherwise, so reading reads as its own space. */
        fun defaultFor(mode: ContentMode) = if (mode == ContentMode.BOOKS) GOLD else TEAL
        fun fromStored(id: String?, fallback: AccentPreset = TEAL) =
            entries.firstOrNull { it.id == id } ?: retired[id] ?: fallback
    }
}

object PreferenceScope {
    // No server ID is exposed by the current Hub contract. Changing addresses creates a new scope.
    fun key(hub: String, profile: String, domain: ContentMode): String {
        val address = runCatching {
            val uri = URI(hub.trim())
            val scheme = uri.scheme.orEmpty().lowercase()
            val port = uri.port.takeUnless { it == -1 || it == 443 && scheme == "https" || it == 80 && scheme == "http" }
            "$scheme://${uri.host.orEmpty().lowercase()}${port?.let { ":$it" }.orEmpty()}${uri.path.orEmpty().trimEnd('/')}"
        }.getOrDefault(hub.trim().trimEnd('/'))
        val digest = MessageDigest.getInstance("SHA-256").digest("$address\u0000${profile.trim()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$digest:${domain.stored}"
    }
}

data class SortPreference(val field: String, val ascending: Boolean) {
    fun encode() = "$field:${if (ascending) "asc" else "desc"}"
    fun supported(fields: List<String>, fallback: String) = if (field in fields) this else forField(fallback)

    /** What the direction means for this field: "A to Z", "Newest first", "Highest first". */
    fun directionLabel(): String = when (field) {
        "last_read" -> if (ascending) "Least recently read first" else "Most recently read first"
        "name", "title", "author", "series" -> if (ascending) "A to Z" else "Z to A"
        "added", "release", "played", "last_read", "year" -> if (ascending) "Oldest first" else "Newest first"
        else -> if (ascending) "Lowest first" else "Highest first"
    }

    companion object {
        fun forField(field: String) = SortPreference(field, field !in setOf("added","last_read","release","year","rating","played","progress"))
        fun decode(raw: String?, fallback: String): SortPreference {
            val parts = raw.orEmpty().split(':')
            return if (parts.size == 2 && parts[0].matches(Regex("[a-z_]+")) && parts[1] in listOf("asc","desc"))
                SortPreference(parts[0],parts[1] == "asc") else forField(fallback)
        }
    }
}
