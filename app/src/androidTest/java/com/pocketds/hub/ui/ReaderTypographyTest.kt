package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubPagePalette
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.EpubTheme
import com.pocketds.hub.reader.PageGeometry
import com.pocketds.hub.reader.PageInfo
import com.pocketds.hub.reader.PageInfoChoice
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
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The reader's page as Kindle lays it (#47), against a local hub and a generated book: Kindle's Sepia, a grey
 * Dim and a true-black Dark, in one column (what a portrait page is) and in two, with the corners and the
 * title, and the margin and the gap between two columns measured from the pixels on screen. Nothing here
 * reaches a real server or a real book.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderTypographyTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun host(activity: ReaderFixtureActivity) =
        Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost

    private suspend fun until(what: String, check: () -> Boolean) {
        try { withTimeout(25_000) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private fun screenshot(): Bitmap = ins.uiAutomation.takeScreenshot()

    private fun save(activity: ReaderFixtureActivity, name: String, bitmap: Bitmap) {
        File(activity.getExternalFilesDir(null), "typography-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The ink columns of the page: where text starts and ends across it, and the widest empty band between. */
    private data class Columns(val left: Int, val right: Int, val gapStart: Int, val gapEnd: Int)

    /** Scans the page area (below the status bar and the top strip, above the foot strip and the navigation bar) for ink. */
    private fun columns(shot: Bitmap, page: Int, density: Float): Columns {
        val top = (54 + PageInfo.STRIP_DP * density).toInt() + 4
        val bottom = shot.height - 54 - (PageInfo.STRIP_DP * density).toInt() - 4
        val ink = IntArray(shot.width)
        val row = IntArray(shot.width)
        for (y in top until bottom step 2) {
            shot.getPixels(row, 0, shot.width, 0, y, shot.width, 1)
            for (x in row.indices) {
                val c = row[x]
                val diff = Math.abs(((c shr 16) and 0xFF) - ((page shr 16) and 0xFF)) + Math.abs(((c shr 8) and 0xFF) - ((page shr 8) and 0xFF)) +
                    Math.abs((c and 0xFF) - (page and 0xFF))
                if (diff > 120) ink[x]++
            }
        }
        val first = ink.indexOfFirst { it > 0 }
        val last = ink.indexOfLast { it > 0 }
        // The widest empty run between the first quarter and the last quarter.
        var bestStart = 0; var bestEnd = 0; var start = -1
        for (x in shot.width / 4 until shot.width * 3 / 4) {
            if (ink[x] == 0) { if (start < 0) start = x } else if (start >= 0) {
                if (x - start > bestEnd - bestStart) { bestStart = start; bestEnd = x }
                start = -1
            }
        }
        return Columns(first, last, bestStart, bestEnd)
    }

    @Test fun kindlesMarginsAndGapOnTheThreeThemes(): Unit = runBlocking {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val density = activity.resources.displayMetrics.density
        val original = EpubAppearanceStore.load(activity)
        val oldInfo = PageInfoSettings.load(activity)
        val oldComfort = ComfortSettings.load(activity)
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        val server = ReaderFixtures.fileServer(ReaderFixtures.epub(aligned = false, twoColumns = true))
        HubSettings.save(activity, server.url("/").toString(), "fixture")
        var screen: EpubReaderScreen? = null
        fun reader() = activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull()
        val shots = mutableListOf<String>()
        try {
            PageInfoSettings.save(activity, PageInfoChoice())
            ComfortSettings.save(activity, ScreenComfort())
            for (theme in listOf(EpubTheme.SEPIA, EpubTheme.DARK, EpubTheme.BLACK)) for (columns in listOf(EpubColumns.ONE, EpubColumns.TWO)) {
                EpubAppearanceStore.save(activity, EpubReaderPreferences(theme = theme, columns = columns))
                withContext(Dispatchers.Main) {
                    screen = EpubReaderScreen(HubClient(activity), "typo-${System.nanoTime()}", "edition", "Light Bringer", { true }, bookPages = 735)
                    val root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
                }
                until("the book") { reader() != null && screen!!.field<View>("loading").visibility != View.VISIBLE }
                delay(1500)
                ins.waitForIdleSync()
                val shot = screenshot()
                val palette = EpubPagePalette.of(theme)
                val name = "${theme.name.lowercase()}-${if (columns == EpubColumns.TWO) "two-columns" else "one-column"}"
                save(activity, name, shot)
                shots += name

                // Readium is told half the gap as its padding, whatever the margin preset.
                val gutter = withContext(Dispatchers.Main) { reader()!!.evaluateJavascript("getComputedStyle(document.documentElement).getPropertyValue('--RS__pageGutter').trim()") }
                assertEquals("\"${PageGeometry.GUTTER_DP}px\"", gutter)

                val found = columns(shot, palette.first, density)
                val outer = PageGeometry.outerMarginDp(1f) * density
                // The text starts at the outer margin (36 dp), to within a letter's bearing.
                assertTrue("$name: the text starts at ${found.left}px, the margin is $outer", Math.abs(found.left - outer) <= 14)
                if (columns == EpubColumns.TWO) {
                    val gap = (found.gapEnd - found.gapStart) / density
                    assertTrue("$name: the gap is $gap dp", Math.abs(gap - PageGeometry.GAP_DP) <= 7)
                    // And the right column ends as far from the right edge as the left one starts from the left.
                    assertTrue("$name: the text ends at ${found.right}px", Math.abs((shot.width - found.right) - outer) <= 16)
                }
                withContext(Dispatchers.Main) { screen!!.onHide(); screen!!.onDestroyView() }
                delay(300)
            }
            assertEquals(6, shots.size)
        } finally {
            withContext(Dispatchers.Main) { runCatching { screen?.onHide(); screen?.onDestroyView() }; activity.finish() }
            EpubAppearanceStore.save(activity, original)
            PageInfoSettings.save(activity, oldInfo)
            ComfortSettings.save(activity, oldComfort)
            HubSettings.save(activity, oldUrl, oldToken)
            server.shutdown()
        }
    }
}
