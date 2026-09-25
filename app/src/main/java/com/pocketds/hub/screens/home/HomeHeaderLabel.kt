package com.pocketds.hub.screens.home

object HomeHeaderLabel {
    fun forUser(name: String?, missingProfile: Boolean = false): String =
        name?.trim()?.takeIf(String::isNotEmpty)?.let { "Hello $it" } ?: "Hello"
}
