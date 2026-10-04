package com.pocketds.hub.state

import com.pocketds.hub.model.CacheInfo
import com.pocketds.hub.net.FailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTextTest {

    @Test
    fun `loading says refreshing once there is something on screen`() {
        assertEquals(StatusMessage("Loading episodes…"), StatusText.loading("episodes", refreshing = false))
        assertEquals(StatusMessage("Refreshing episodes…"), StatusText.loading("episodes", refreshing = true))
    }

    @Test
    fun `loading is never shown in the colour of the last failure`() {
        // Library used to set the text but not the colour, so "Refreshing…"
        // appeared in red after an earlier error.
        assertEquals(StatusTone.NORMAL, StatusText.loading("libraries", refreshing = true).tone)
    }

    @Test
    fun `fresh data carries no caveat, cached or not`() {
        assertEquals(StatusMessage("24 rows"), StatusText.loaded("24 rows"))
        assertEquals(StatusMessage("24 rows"), StatusText.loaded("24 rows", CacheInfo(hit = true, ageSeconds = 40)))
    }

    @Test
    fun `stale data says how old it is`() {
        assertEquals(
            StatusMessage("24 rows · updated 4 min ago", news = true),
            StatusText.loaded("24 rows", CacheInfo(hit = true, ageSeconds = 250, stale = true))
        )
    }

    @Test
    fun `degraded data is a warning, not presented as current`() {
        assertEquals(
            StatusMessage("24 rows · couldn't refresh, showing data from 2 h ago", StatusTone.WARNING),
            StatusText.loaded("24 rows", CacheInfo(hit = true, ageSeconds = 7_300, stale = true, degraded = true))
        )
    }

    @Test
    fun `services that did not answer are named, not only coloured`() {
        // Library grids and Episodes used to turn the line amber with no words.
        assertEquals(
            StatusMessage("12 of 40 · Sonarr, qBittorrent unavailable", StatusTone.WARNING),
            StatusText.loaded("12 of 40", unavailable = listOf("sonarr", "qbittorrent", "sonarr"))
        )
    }

    @Test
    fun `the caveat alone is empty when everything is fresh and complete`() {
        assertEquals(StatusMessage(""), StatusText.caveat(CacheInfo(hit = true, ageSeconds = 5)))
        assertEquals(
            StatusMessage("updated 2 min ago", news = true),
            StatusText.caveat(CacheInfo(stale = true, ageSeconds = 150))
        )
    }

    @Test
    fun `an empty summary does not leave a dangling separator`() {
        assertEquals(
            StatusMessage("updated 1 min ago", news = true),
            StatusText.loaded("", CacheInfo(stale = true, ageSeconds = 61))
        )
    }

    @Test
    fun `a retryable failure with nothing on screen offers the retry`() {
        assertEquals(
            StatusMessage("Can't reach the hub · Select retries", StatusTone.ERROR),
            StatusText.failed("Can't reach the hub", FailureKind.NO_NETWORK, hasData = false)
        )
    }

    @Test
    fun `a failed refresh says the earlier results are still there`() {
        assertEquals(
            StatusMessage("The hub took too long · showing earlier results · Select retries", StatusTone.ERROR),
            StatusText.failed("The hub took too long", FailureKind.TIMEOUT, hasData = true)
        )
    }

    @Test
    fun `a rejected token never promises that retrying helps`() {
        // Retrying a 401 only moves the device closer to the hub's source ban.
        assertEquals(
            StatusMessage("This token was rejected — edit it in Manage", StatusTone.ERROR),
            StatusText.failed("This token was rejected — edit it in Manage", FailureKind.UNAUTHORIZED, hasData = false)
        )
    }

    @Test
    fun `a screen without a retry action does not advertise one`() {
        assertEquals(
            StatusMessage("A service behind the hub is down", StatusTone.ERROR),
            StatusText.failed("A service behind the hub is down", FailureKind.UPSTREAM_DOWN, hasData = false, canRetry = false)
        )
    }

    @Test
    fun `Glass shows a line only when it has news`() {
        // "4 rows · updated moments ago" sat over Home's artwork saying nothing.
        val moments = StatusText.loaded("4 rows", CacheInfo(hit = true, ageSeconds = 20, stale = true))
        assertEquals("4 rows · updated moments ago", moments.text)
        assertFalse(StatusText.shows(moments, glass = true))
        assertTrue(StatusText.shows(moments, glass = false))
        assertFalse(StatusText.shows(StatusText.loaded("24 rows"), glass = true))
        assertFalse(StatusText.shows(StatusText.loading("Discover", refreshing = false), glass = true))
        // Old, partial, degraded or failed data is news; so is a notice.
        assertTrue(StatusText.shows(StatusText.loaded("4 rows", CacheInfo(ageSeconds = 90, stale = true)), glass = true))
        assertTrue(StatusText.shows(StatusText.loaded("4 rows", unavailable = listOf("sonarr")), glass = true))
        assertTrue(StatusText.shows(StatusText.loaded("4 rows", CacheInfo(ageSeconds = 30, stale = true, degraded = true)), glass = true))
        assertTrue(StatusText.shows(StatusText.failed("Can't reach the hub", FailureKind.NO_NETWORK, hasData = true), glass = true))
        assertTrue(StatusText.shows(StatusText.notice("This library is empty."), glass = true))
        // Nothing to say is nothing to show, on either look.
        assertFalse(StatusText.shows(StatusMessage("", news = true), glass = true))
        assertFalse(StatusText.shows(StatusMessage(""), glass = false))
    }

    @Test
    fun `a summary keeps its caveat's news`() {
        val caveat = StatusText.caveat(CacheInfo(ageSeconds = 400, stale = true))
        assertTrue(caveat.news)
        assertTrue(StatusText.loaded("12 results", caveat).news)
        assertFalse(StatusText.loaded("12 results", StatusText.caveat(CacheInfo())).news)
    }

    @Test
    fun `ages read in the largest sensible unit`() {
        assertEquals("moments ago", StatusText.age(30))
        assertEquals("1 min ago", StatusText.age(60))
        assertEquals("59 min ago", StatusText.age(3_599))
        assertEquals("3 h ago", StatusText.age(3 * 3_600))
        assertEquals("2 d ago", StatusText.age(2 * 86_400))
    }
}
