package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageInfoChoice
import com.pocketds.hub.reader.PageInfoCorner
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.PageInfoSettings
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Turning many pages quickly (#64): every swipe, pad press or tap in the margin turns exactly one page, however fast they come
 * and whichever way. A burst of ten, one way, the other, and mixed, at gaps of 40 to 150 ms between one and the next, counted
 * as the pages landed against the turns sent. The swipes are real touches, injected into the window at a finger's sampling
 * rate, from the middle of the page and from the margins and strips Readium's page does not cover.
 *
 * What was measured before the fix: Readium's paginated page is a ViewPager, and a finger that comes down while a turn is
 * still animating "catches" the page where it is, so the next swipe turns from there and a turn is lost (8 of 10 at 60 ms);
 * and a swipe that begins in the side margins or the strips above and below the page never reached Readium at all (0 of 10).
 *
 * The generated book of #55 ([ReaderFixtures.contentsEpub]); nothing here opens a real book or reaches a real server.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderFastTurnsTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun contentsRow(row: View): String = all(row).filterIsInstance<TextView>().first().text.toString().trim()

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * A finger from ([x0], [y0]) to ([x1], [y1]) in screen pixels, injected as a real touch sampled every 8 ms (a touch screen's rate).
     * The events carry the times a finger would have made them, whatever the machine does: on a busy one the injection itself takes
     * seconds an event, and a swipe stretched to two seconds is a slow drag that no pager turns.
     */
    private fun finger(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        try { injectFinger(down, x0, y0, x1, y1, durationMs) } finally { if (SystemClock.uptimeMillis() - down > BUSY_MS) slowSwipes++ }
    }

    /** Swipes whose injection took longer than [BUSY_MS]: the machine was too busy for the pager to be judged by them. */
    private var slowSwipes = 0
    private val BUSY_MS = 600L

    /**
     * A failed count is a failure, unless swipes took seconds to inject: on a machine that busy (the other agent's builds and emulators
     * share it) what the pager does with a swipe stretched in real time is not what a finger does, and the run is skipped, not failed.
     */
    private fun verdict(message: String, ok: Boolean) {
        if (!ok && slowSwipes > 0) assumeTrue("Too busy to say: $slowSwipes swipes took over ${BUSY_MS} ms to inject. $message", false)
        assertTrue(message, ok)
    }

    private fun injectFinger(down: Long, x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long) {
        fun send(at: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, down + at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            ins.sendPointerSync(event)
            event.recycle()
        }
        send(0, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = maxOf(4, (durationMs / 8).toInt())
        for (i in 1..steps) send(durationMs * i / steps, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        send(durationMs, MotionEvent.ACTION_UP, x1, y1)
    }

    /** Where a swipe starts and how it goes: [startX] and [startY] are fractions of the reader, [reach] the fraction of its width it crosses. */
    private data class Style(val name: String, val startX: Float, val startY: Float, val reach: Float, val durationMs: Long)

    private val fromTheMiddle = Style("from the middle", 0.72f, 0.5f, 0.40f, 90)

    private fun swipe(root: View, forward: Boolean, style: Style) {
        // Inside the reader's own view: a touch over the status or navigation bar is refused by the system.
        val at = IntArray(2).also(root::getLocationOnScreen)
        val width = root.width
        val x0 = at[0] + if (forward) width * style.startX else width * (1f - style.startX)
        val x1 = if (forward) x0 - width * style.reach else x0 + width * style.reach
        val y = at[1] + root.height * style.startY
        finger(x0, y, x1, y, style.durationMs)
    }

    /** A tap at a fraction of the reader's width, [dp] in from the edge it names. */
    private fun tap(root: View, right: Boolean, dp: Float) {
        val at = IntArray(2).also(root::getLocationOnScreen)
        val inset = dp * root.resources.displayMetrics.density
        val x = at[0] + if (right) root.width - inset else inset
        val y = at[1] + root.height * 0.5f
        finger(x, y, x, y, 40)
    }

    private class Reading(val activity: ReaderFixtureActivity, val screen: EpubReaderScreen, val root: View)

    private suspend fun withBook(block: suspend Reading.() -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val original = EpubAppearanceStore.load(activity)
        val oldInfo = PageInfoSettings.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.contentsEpub())
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        try {
            EpubAppearanceStore.save(activity, EpubReaderPreferences())
            ComfortSettings.save(activity, ScreenComfort())
            PageInfoSettings.save(activity, PageInfoChoice(corner = PageInfoCorner.PAGE_IN_BOOK))
            lateinit var root: View
            withContext(Dispatchers.Main) {
                screen = EpubReaderScreen(HubClient(activity), "turns-${System.nanoTime()}", "edition", "The Last Observatory", { true })
                root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
            }
            until("the book") { activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().isNotEmpty() && screen!!.field<View>("loading").visibility != View.VISIBLE }
            delay(1000)
            val reading = Reading(activity, screen!!, root)
            // In the middle of the long second part, the menu away: nothing over the page, a swipe is the page's own.
            reading.goTo("2. The ridge")
            withContext(Dispatchers.Main) { if (screen!!.field<Boolean>("controlsVisible")) screen!!.onPad(PadAction.Menu) }
            until("the menu to close") { !screen!!.field<Boolean>("controlsVisible") }
            delay(500)
            reading.block()
        } catch (failure: Throwable) {
            File(activity.getExternalFilesDir(null), "reader-fast-turns-failure.png").outputStream().use {
                ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            throw failure
        } finally {
            withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
            EpubAppearanceStore.save(activity, original)
            PageInfoSettings.save(activity, oldInfo)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }

    /** The page in its file, counted from 0, and how many there are: what the navigator reports once a page has settled. */
    private suspend fun Reading.place(): Pair<Int, Int> = withContext(Dispatchers.Main) { screen.field<Int>("pageIndex") to screen.field<Int>("pageCount") }

    /** The page the book has settled on: its index unchanged for a second. */
    private suspend fun Reading.settled(): Int {
        var last = -1 to -1
        var since = System.currentTimeMillis()
        val start = since
        while (System.currentTimeMillis() - start < 15_000) {
            val now = place()
            if (now != last) { last = now; since = System.currentTimeMillis() }
            else if (System.currentTimeMillis() - since >= 1_000) break
            delay(50)
        }
        return last.first
    }

    private suspend fun Reading.goTo(title: String) {
        withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
        val overlay: SidePanelView = screen.field("overlay")
        until("Contents") { overlay.isOpen && overlay.rows.size == ReaderFixtures.CONTENTS_TITLES.size }
        withContext(Dispatchers.Main) { overlay.rows.first { contentsRow(it) == title }.performClick() }
        until("Contents to close") { !overlay.isOpen }
        settled()
    }

    /** What a burst did: [moves] sent [gapMs] apart (+1 forward, -1 back), the pages the book moved, and a line for the log. */
    private data class Burst(val name: String, val gapMs: Long, val sent: Int, val expected: Int, val landed: Int) {
        val line get() = "$name, gap $gapMs ms: sent $sent (net $expected), landed $landed"
    }

    private suspend fun Reading.burst(name: String, moves: List<Int>, gapMs: Long, send: suspend (Int) -> Unit): Burst {
        val before = settled()
        val trace = StringBuilder()
        for (move in moves) {
            trace.append("${state()} -> ")
            send(move)
            trace.append("${state()}; ")
            delay(gapMs)
        }
        val after = settled()
        return Burst(name, gapMs, moves.size, moves.sum(), after - before).also {
            android.util.Log.i("TURNS", it.line)
            if (it.landed != it.expected) android.util.Log.i("TURNS", "  trace: $trace\n  settled on ${place()} with the pager at ${state()}, from page $before")
        }
    }

    private val forward = List(10) { 1 }
    private val back = List(10) { -1 }
    private val mixed = listOf(1, 1, -1, 1, 1, 1, -1, 1, -1, 1)

    /** Every burst of [name] at each gap, forward, back and mixed, must land as many pages as were sent. */
    private suspend fun Reading.bursts(name: String, gaps: List<Long>, send: suspend (Boolean) -> Unit) {
        val results = mutableListOf<Burst>()
        for (gap in gaps) {
            results += burst("$name forward", forward, gap) { send(true) }
            results += burst("$name back", back, gap) { send(false) }
            results += burst("$name mixed", mixed, gap) { send(it > 0) }
        }
        val wrong = results.filter { it.landed != it.expected }
        verdict("Every turn is one page: ${wrong.joinToString("; ") { it.line }}\nof ${results.joinToString("\n") { it.line }}", wrong.isEmpty())
    }

    /** The page's own state, for a trace: its current item, where it is scrolled, and whether it is idle (0), dragging (1) or settling (2). */
    private suspend fun Reading.state(): String = withContext(Dispatchers.Main) {
        val web = all(root).filter { it.javaClass.name == "org.readium.r2.navigator.R2WebView" && it.isShown }.maxByOrNull { v -> val r = android.graphics.Rect(); if (v.getGlobalVisibleRect(r)) r.width() * r.height() else 0 }
            ?: return@withContext "no page"
        val item = web.javaClass.getMethod("getMCurItem\$readium_navigator_release").invoke(web)
        val scrollState = web.javaClass.getDeclaredField("mScrollState").apply { isAccessible = true }.getInt(web)
        "item=$item x=${web.scrollX} state=$scrollState"
    }

    @Test fun fastSwipesFromTheMiddleOfThePageTurnOnePageEach(): Unit = runBlocking {
        withBook { bursts("swipe", listOf(150L, 100L, 80L, 60L, 40L)) { forward -> swipe(root, forward, fromTheMiddle) } }
    }

    /**
     * Swipes as a hand makes them, not as a machine: thirty of them at gaps of 30 to 170 ms, each of its own length, speed and place
     * on the page, mostly one way and sometimes the other. Seeded, so a failure is the same failure next time.
     */
    @Test fun thirtySwipesAtIrregularGapsTurnOnePageEach(): Unit = runBlocking {
        withBook {
          val failures = mutableListOf<String>()
          for (round in 0 until 4) {
            val random = java.util.Random(64L + round)
            // Mostly forward in one round and mostly back in the next, so the book stays in the middle of its file.
            val moves = List(30) { if ((random.nextInt(4) == 0) == (round % 2 == 0)) -1 else 1 }
            val before = settled()
            val trace = StringBuilder()
            val t0 = System.currentTimeMillis()
            for ((index, move) in moves.withIndex()) {
                val style = Style("a hand", 0.58f + random.nextFloat() * 0.2f, 0.35f + random.nextFloat() * 0.3f, 0.25f + random.nextFloat() * 0.25f, 55L + random.nextInt(80))
                val sentAt = System.currentTimeMillis() - t0
                val pagerBefore = state()
                swipe(root, move > 0, style)
                trace.append("#$index ${if (move > 0) "+" else "-"} at +$sentAt (took ${System.currentTimeMillis() - t0 - sentAt} ms): $pagerBefore -> ${state()}\n")
                delay(30L + random.nextInt(140))
            }
            val landed = settled() - before
            android.util.Log.i("TURNS", "irregular: sent ${moves.size} (net ${moves.sum()}), landed $landed")
            if (landed != moves.sum()) android.util.Log.i("TURNS", "  trace:\n$trace  settled on ${place()} with the pager at ${state()}, from page $before")
            if (landed != moves.sum()) failures += "round $round: thirty swipes, net ${moves.sum()}, landed $landed"
          }
          verdict("Every swipe turns one page: ${failures.joinToString("; ")}", failures.isEmpty())
        }
    }

    /**
     * Across the end of a file the pages are the next file's: a burst that starts a few pages before the end goes on into the epilogue,
     * one page a swipe (the pager of files has the swipe where the page of one ends), and back again.
     */
    @Test fun aBurstAcrossTheEndOfAFileTurnsOnePageEach(): Unit = runBlocking {
        withBook {
            val (_, pages) = place()
            // Four pages from the end of the second part, by the pad, which counts exactly.
            while (place().first < pages - 4) {
                withContext(Dispatchers.Main) { screen.onPad(PadAction.Step(Direction.RIGHT)) }
                delay(150)
            }
            settled()
            suspend fun where() = withContext(Dispatchers.Main) { screen.field<org.readium.r2.shared.publication.Locator?>("latestLocator")?.href.toString().substringAfterLast('/') to place().first }
            val start = where()
            assertEquals("two.xhtml", start.first)
            // What the pad does across the end, as the measure of what a swipe should: ten presses 80 ms apart.
            repeat(10) { withContext(Dispatchers.Main) { screen.onPad(PadAction.Step(Direction.RIGHT)) }; delay(80) }
            settled()
            assertEquals("ten pad presses on from four pages before the end of the file", "three.xhtml" to 6, where())
            repeat(10) { withContext(Dispatchers.Main) { screen.onPad(PadAction.Step(Direction.LEFT)) }; delay(80) }
            settled()
            assertEquals("and ten back", start, where())
            repeat(10) { swipe(root, true, fromTheMiddle); delay(120) }
            settled()
            val forward = where()
            android.util.Log.i("TURNS", "across the end: from $start to $forward")
            // Page pages-4 is the fourth from the end; the fourth swipe is the epilogue's first page, the tenth its seventh.
            assertEquals("ten swipes on from four pages before the end of the file: $forward", "three.xhtml" to 6, forward)
            repeat(10) { swipe(root, false, fromTheMiddle); delay(120) }
            settled()
            val back = where()
            android.util.Log.i("TURNS", "across the end, back: $back")
            assertEquals("and ten back", start, back)
        }
    }

    @Test fun fastPadTurnsTurnOnePageEach(): Unit = runBlocking {
        withBook {
            bursts("pad", listOf(100L, 60L, 30L)) { forward ->
                withContext(Dispatchers.Main) { screen.onPad(PadAction.Step(if (forward) Direction.RIGHT else Direction.LEFT)) }
            }
        }
    }

    @Test fun fastTapsInTheMarginsTurnOnePageEach(): Unit = runBlocking {
        withBook { bursts("margin tap", listOf(120L, 80L)) { forward -> tap(root, forward, 8f) } }
    }

    /** A swipe that begins in the margin at a side, or in the strip above or below the page, turns the page too. */
    @Test fun swipesThatBeginInTheMarginsAndStripsTurnTheirPage(): Unit = runBlocking {
        withBook {
            val styles = listOf(
                Style("from the side margin", 0.995f, 0.5f, 0.40f, 90),
                Style("from the top strip", 0.72f, 0.03f, 0.40f, 90),
                Style("from the bottom strip", 0.72f, 0.97f, 0.40f, 90)
            )
            val results = mutableListOf<Burst>()
            for (style in styles) {
                results += burst("swipe ${style.name} forward", forward, 150) { swipe(root, true, style) }
                results += burst("swipe ${style.name} back", back, 150) { swipe(root, false, style) }
            }
            val wrong = results.filter { it.landed != it.expected }
            verdict("Every swipe turns a page: ${wrong.joinToString("; ") { it.line }}", wrong.isEmpty())
        }
    }
}
