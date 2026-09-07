package com.pocketds.hub.net

/**
 * Why a call failed, in terms the UI can act on.
 *
 * The distinction that matters most is [UNAUTHORIZED] versus everything else:
 * it is the only one where retrying is pointless and the fix is a trip to the
 * Setup screen.
 */
enum class FailureKind {
    NO_NETWORK,
    TIMEOUT,
    UNAUTHORIZED,
    RATE_LIMITED,
    NOT_FOUND,
    UPSTREAM_DOWN,
    SERVER,
    BAD_RESPONSE,
    UNKNOWN;

    val isRetryable: Boolean
        get() = this == NO_NETWORK || this == TIMEOUT || this == SERVER ||
            this == UPSTREAM_DOWN || this == RATE_LIMITED
}

/**
 * Classification, as a pure function of an exception class name and a status
 * code, so it can be exhaustively tested without a network.
 *
 * Class *names* rather than the exception objects because the JVM stubs used in
 * unit tests do not carry the real Android/OkHttp types.
 */
object HubFailures {

    fun classify(exceptionClassName: String?, httpCode: Int?): FailureKind {
        if (httpCode != null) {
            return when {
                httpCode == 401 || httpCode == 403 -> FailureKind.UNAUTHORIZED
                httpCode == 404 -> FailureKind.NOT_FOUND
                httpCode == 429 -> FailureKind.RATE_LIMITED
                httpCode == 502 || httpCode == 503 || httpCode == 504 -> FailureKind.UPSTREAM_DOWN
                httpCode in 500..599 -> FailureKind.SERVER
                httpCode in 400..499 -> FailureKind.BAD_RESPONSE
                else -> FailureKind.UNKNOWN
            }
        }
        val name = exceptionClassName ?: return FailureKind.UNKNOWN
        return when {
            name.endsWith("SocketTimeoutException") -> FailureKind.TIMEOUT
            name.endsWith("InterruptedIOException") -> FailureKind.TIMEOUT
            name.endsWith("UnknownHostException") -> FailureKind.NO_NETWORK
            name.endsWith("ConnectException") -> FailureKind.NO_NETWORK
            name.endsWith("NoRouteToHostException") -> FailureKind.NO_NETWORK
            name.contains("SSL") -> FailureKind.BAD_RESPONSE
            name.endsWith("SerializationException") -> FailureKind.BAD_RESPONSE
            name.endsWith("JsonDecodingException") -> FailureKind.BAD_RESPONSE
            else -> FailureKind.UNKNOWN
        }
    }

    /** What to put on screen. Short, because it goes in a one-line strip. */
    fun message(kind: FailureKind): String = when (kind) {
        FailureKind.NO_NETWORK -> "Can't reach the hub"
        FailureKind.TIMEOUT -> "The hub took too long"
        FailureKind.UNAUTHORIZED -> "The hub rejected this device — check the token"
        FailureKind.RATE_LIMITED -> "Too many requests — slow down"
        FailureKind.NOT_FOUND -> "Not found"
        FailureKind.UPSTREAM_DOWN -> "A service behind the hub is down"
        FailureKind.SERVER -> "The hub hit an error"
        FailureKind.BAD_RESPONSE -> "The hub sent something unexpected"
        FailureKind.UNKNOWN -> "Something went wrong"
    }
}

/**
 * The outcome of a call.
 *
 * [Ok.fromCache] and [Ok.ageSeconds] come from the hub's own cache block, and
 * exist so the UI can say "showing results from 4 minutes ago" instead of
 * presenting stale data as current.
 */
sealed interface HubResult<out T> {
    data class Ok<T>(
        val value: T,
        val fromCache: Boolean = false,
        val ageSeconds: Int = 0,
        val degraded: Boolean = false
    ) : HubResult<T>

    data class Failed(
        val kind: FailureKind,
        val message: String = HubFailures.message(kind)
    ) : HubResult<Nothing>
}
