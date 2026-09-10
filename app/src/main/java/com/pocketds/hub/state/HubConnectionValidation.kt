package com.pocketds.hub.state

object HubConnectionValidation {
    fun error(normalizedAddress: String, token: String): String? {
        val addressAllowed =
            normalizedAddress.startsWith("https://") && normalizedAddress.length > "https://".length ||
                normalizedAddress.startsWith("http://127.0.0.1") ||
                normalizedAddress.startsWith("http://localhost")
        if (!addressAllowed) return "Enter a complete HTTPS address"
        if (token.trim().isEmpty()) return "Paste the Hub access token"
        if (token.trim().length < 32) return "The Hub access token is incomplete"
        return null
    }
}
