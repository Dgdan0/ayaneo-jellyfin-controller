package com.pocketds.hub.reader

import java.security.MessageDigest

data class ReaderPageCacheEntry(val name: String, val size: Long, val lastAccess: Long)

object ReaderPageCachePolicy {
    fun fileName(identity: String): String = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".page"

    fun evictions(
        entries: List<ReaderPageCacheEntry>,
        maxBytes: Long,
        protectedName: String
    ): List<String> {
        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return emptyList()
        val removals = mutableListOf<String>()
        entries.sortedWith(compareBy<ReaderPageCacheEntry> { it.lastAccess }.thenBy { it.name })
            .forEach { entry ->
                if (total <= maxBytes || entry.name == protectedName) return@forEach
                removals += entry.name
                total -= entry.size
            }
        return removals
    }
}
