package com.pocketds.hub.settings

import com.pocketds.hub.state.ContentMode
import java.net.URI
import java.security.MessageDigest

/** Stable IDs are persisted; names and ordering may change without losing a choice. */
enum class AccentPreset(val id: String, val label: String, val dark: Int, val light: Int) {
    TEAL("teal","Teal",0xff4ed6bf.toInt(),0xff08796c.toInt()),
    BLUE("blue","Blue",0xff80b9ff.toInt(),0xff2266ba.toInt()),
    INDIGO("indigo","Indigo",0xffa5b4fc.toInt(),0xff4f46b9.toInt()),
    VIOLET("violet","Violet",0xffc0a3ff.toInt(),0xff773db8.toInt()),
    ROSE("rose","Rose",0xfff9a8c7.toInt(),0xffa12b60.toInt()),
    CORAL("coral","Coral",0xffffaaa0.toInt(),0xffac4035.toInt()),
    AMBER("amber","Amber",0xfff7ce73.toInt(),0xff85600a.toInt()),
    OLIVE("olive","Olive",0xffcedb8a.toInt(),0xff667520.toInt()),
    GREEN("green","Green",0xff7ddd9b.toInt(),0xff267746.toInt()),
    CYAN("cyan","Cyan",0xff76d5ef.toInt(),0xff15738a.toInt());
    companion object { fun fromStored(id: String?) = entries.firstOrNull { it.id == id } ?: TEAL }
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
    companion object {
        fun forField(field: String) = SortPreference(field, field !in setOf("added","last_read","release","year","rating","played","progress"))
        fun decode(raw: String?, fallback: String): SortPreference {
            val parts = raw.orEmpty().split(':')
            return if (parts.size == 2 && parts[0].matches(Regex("[a-z_]+")) && parts[1] in listOf("asc","desc"))
                SortPreference(parts[0],parts[1] == "asc") else forField(fallback)
        }
    }
}
