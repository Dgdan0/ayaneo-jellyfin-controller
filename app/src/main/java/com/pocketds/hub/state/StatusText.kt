package com.pocketds.hub.state

import com.pocketds.hub.model.CacheInfo
import com.pocketds.hub.model.ServiceNames
import com.pocketds.hub.net.FailureKind

enum class StatusTone { NORMAL, WARNING, ERROR }

/**
 * @param news whether the line has something to tell the reader, which is what
 *   decides whether it shows ([StatusText.shows]): data that is old, partial or
 *   failed, or a [StatusText.notice]. A count of rows or "Loading…" is not
 *   news.
 */
data class StatusMessage(
    val text: String,
    val tone: StatusTone = StatusTone.NORMAL,
    val news: Boolean = tone != StatusTone.NORMAL
)

/**
 * What a screen's status line says, and in which tone -- the only place that
 * decides it.
 *
 * About thirty screens used to compose this themselves and had drifted into six
 * dialects: "· cached", "· cached 40s ago", "· offline cache", "· hub degraded",
 * a colour change with no words at all, and "Select retries" offered even for a
 * rejected token, where retrying only moves the device toward the hub's ban.
 * Screens now pass what happened; the wording follows from it.
 */
object StatusText {

    private const val SEPARATOR = " · "

    /**
     * Data served past its freshness is news once it is a minute old. "Updated
     * moments ago" is the hub refreshing behind the screen, which is the cache
     * working; it sat over Home's artwork as "4 rows · updated moments ago".
     */
    const val STALE_NEWS_SECONDS = 60

    /**
     * Whether the line shows: only with news, as a quiet chip
     * (`TextView.showStatus`). A status line that always talked sat over the
     * artwork saying nothing.
     */
    fun shows(message: StatusMessage): Boolean = message.text.isNotBlank() && message.news

    /**
     * Something the reader must be told: why a list is empty, what to do next
     * ("Choose a profile with Y").
     */
    fun notice(text: String): StatusMessage = StatusMessage(text, news = true)

    fun loading(what: String, refreshing: Boolean): StatusMessage =
        StatusMessage(if (refreshing) "Refreshing $what…" else "Loading $what…")

    /**
     * A loaded screen's line: its [summary] plus the [caveat], if any.
     */
    fun loaded(
        summary: String,
        cache: CacheInfo = CacheInfo(),
        unavailable: List<String> = emptyList()
    ): StatusMessage = loaded(summary, caveat(cache, unavailable))

    /** For a screen that keeps the [caveat] while its summary changes (e.g. a results toggle). */
    fun loaded(summary: String, caveat: StatusMessage): StatusMessage = StatusMessage(
        listOf(summary, caveat.text).filter(String::isNotBlank).joinToString(SEPARATOR),
        caveat.tone,
        caveat.news
    )

    /**
     * Only what the reader needs to be warned about; empty for fresh, complete
     * data -- which screens that hide their status line when all is well use.
     *
     * @param unavailable ids of services that did not answer while the rest
     *   loaded. Named in the text, so a partial result never reads as the whole.
     */
    fun caveat(cache: CacheInfo, unavailable: List<String> = emptyList()): StatusMessage {
        val parts = mutableListOf<String>()
        when {
            // Fresh data -- a cache hit inside its TTL included -- needs no
            // caveat: the TTLs exist precisely so that it is still true.
            cache.degraded -> parts += "couldn't refresh, showing data from ${age(cache.ageSeconds)}"
            cache.stale -> parts += "updated ${age(cache.ageSeconds)}"
        }
        val services = unavailable.filter(String::isNotBlank).distinct()
        if (services.isNotEmpty()) parts += services.joinToString(", ", transform = ServiceNames::display) + " unavailable"
        val warning = cache.degraded || services.isNotEmpty()
        val old = cache.stale && cache.ageSeconds >= STALE_NEWS_SECONDS
        return StatusMessage(parts.joinToString(SEPARATOR), if (warning) StatusTone.WARNING else StatusTone.NORMAL, warning || old)
    }

    /**
     * @param message the hub's own wording, which is more specific than
     *   anything the app could say ("This token was rejected — …").
     * @param hasData whether the screen is still showing an earlier result.
     * @param canRetry false on a screen with no Select-to-retry action, so the
     *   line never promises one that does nothing.
     */
    fun failed(
        message: String,
        kind: FailureKind,
        hasData: Boolean,
        canRetry: Boolean = true
    ): StatusMessage {
        val parts = mutableListOf(message)
        if (hasData) parts += "showing earlier results"
        if (canRetry && kind.isRetryable) parts += "Select retries"
        return StatusMessage(parts.joinToString(SEPARATOR), StatusTone.ERROR)
    }

    fun age(seconds: Int): String = when {
        seconds < 60 -> "moments ago"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}
