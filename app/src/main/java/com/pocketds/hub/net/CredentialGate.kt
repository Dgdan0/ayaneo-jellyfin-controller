package com.pocketds.hub.net

/**
 * What the hub has said about this device's bearer token, shared by every
 * request the process makes.
 *
 * The hub bans a source after five 401s in a row for fifteen minutes, and
 * every request during the ban extends it -- while also refusing the correct
 * token. So one wrong token has to stop *all* traffic after the first 401:
 * screen reads, poster loads, reader pages and a queue of offline downloads
 * each used to learn it separately (the downloads and images never checked),
 * and a series queue could burn through five attempts in a second.
 *
 * Only 401 means the token is wrong. The hub answers 403 for a scope the token
 * lacks ("this device is not allowed to control downloads") and that token
 * still works for everything else, so a 403 must not lock the app out.
 *
 * Until a token has been accepted once, the caller sends one request at a time
 * ([needsProbe]), so a burst of concurrent poster loads cannot reach the ban
 * threshold before the first answer arrives.
 *
 * Primitives only, so it is a JVM test.
 */
class CredentialGate {

    sealed interface Block {
        val message: String

        data object Rejected : Block {
            override val message = "This token was rejected — edit it in Manage > Ayaneo Hub"
        }

        data class Banned(val untilMillis: Long, val nowMillis: Long) : Block {
            val remainingSeconds: Long get() = ((untilMillis - nowMillis + 999) / 1_000).coerceAtLeast(1)
            override val message: String
                get() = "The Hub is refusing this device for ${(remainingSeconds + 59) / 60} min " +
                    "after failed sign-ins"
        }
    }

    private var rejected = ""
    private var accepted = ""
    private var bannedToken = ""
    private var bannedUntil = 0L

    /** Why a request with [token] must not be sent now, or null to send it. */
    @Synchronized
    fun blockFor(token: String, nowMillis: Long): Block? = when {
        token.isEmpty() -> null
        token == rejected -> Block.Rejected
        // A ban is on the source, not the token, but a newly typed token is
        // the user asking to try again (after restarting the hub, which is
        // how a ban is cleared), so it gets its one attempt.
        token == bannedToken && nowMillis < bannedUntil -> Block.Banned(bannedUntil, nowMillis)
        else -> null
    }

    /** True while [token] has never been accepted: send one request at a time. */
    @Synchronized
    fun needsProbe(token: String): Boolean = token.isNotEmpty() && token != accepted

    /** Records what the hub answered to a request that carried [token]. */
    @Synchronized
    fun observe(token: String, code: Int, retryAfterSeconds: Long?, nowMillis: Long) {
        if (token.isEmpty()) return
        when {
            code == 401 -> {
                rejected = token
                if (accepted == token) accepted = ""
            }
            code == 429 && (retryAfterSeconds ?: 0) >= BAN_THRESHOLD_SECONDS -> {
                bannedToken = token
                bannedUntil = nowMillis + retryAfterSeconds!! * 1_000
            }
            code in 200..399 || code == 403 -> {
                // Both mean withAuth verified the token.
                accepted = token
                if (rejected == token) rejected = ""
                if (bannedToken == token) bannedUntil = 0L
            }
        }
    }

    companion object {
        /**
         * The ordinary rate limit answers Retry-After: 2; a ban answers the
         * rest of fifteen minutes. Anything this long is worth waiting out
         * rather than retrying into.
         */
        const val BAN_THRESHOLD_SECONDS = 30L
    }
}
