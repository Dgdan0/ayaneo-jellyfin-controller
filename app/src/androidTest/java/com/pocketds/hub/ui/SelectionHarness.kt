package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.DictionaryCard
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.PageInfoChoice
import com.pocketds.hub.reader.SpeechVoice
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.HighlightSettings
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.PageInfoSettings
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/** A speaker that says nothing and remembers what it was asked to say. */
class RecordingVoice : SpeechVoice {
    val said = ArrayList<String>()
    override fun say(text: String) { said += text }
    override fun stop() = Unit
    override fun release() = Unit
}

/**
 * The real reader on a generated book and a stand-in hub (#62), driven through its own screen: nothing here opens a real book or reaches a real
 * server, and the `.uitest` application is the only one touched. Words are selected the way a finger does, through the page's own selection.
 */
@OptIn(ExperimentalReadiumApi::class)
class SelectionBook(
    val activity: ReaderFixtureActivity,
    val screen: EpubReaderScreen,
    val root: View,
    val hub: HighlightsHub,
    val workId: String,
    val voice: RecordingVoice
) {
    private val ins = InstrumentationRegistry.getInstrumentation()

    inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    val card: DictionaryCard get() = screen.field("dictionaryCard")
    val overlay: SidePanelView get() = screen.field("overlay")
    private val navigator: EpubNavigatorFragment get() = screen.field<EpubNavigatorFragment?>("navigator")!!

    suspend fun until(what: String, limitMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(limitMs) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        navigator.evaluateJavascript(script) ?: ""
    }

    /** Where the words just selected were on the screen at the moment they were selected, from the page itself. */
    var selected = RectF()

    /** Selects [text] on the page, as a long press and a drag of the handles would, and lets the reader look at it. */
    suspend fun select(text: String) {
        val quoted = org.json.JSONObject.quote(text)
        val found = js("""(function(find){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n;
            while((n=w.nextNode())){var i=n.nodeValue.indexOf(find);if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+find.length);
            var s=getSelection();s.removeAllRanges();s.addRange(r);var b=r.getBoundingClientRect();return 'ok '+[b.left,b.top,b.right,b.bottom].join(',');}}return 'none';})($quoted)""")
        check(found.contains("ok ")) { "'$text' is not on the page: $found" }
        val (l, t, r, b) = found.substringAfter("ok ").trim('"').split(',').map { it.toFloat() }
        val d = activity.resources.displayMetrics.density
        val page = IntArray(2)
        withContext(Dispatchers.Main) { navigator.view!!.getLocationOnScreen(page) }
        selected = RectF(l * d + page[0], t * d + page[1], r * d + page[0], b * d + page[1])
        withContext(Dispatchers.Main) {
            val inspect = screen.javaClass.getDeclaredMethod("inspectSelection", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            inspect.invoke(screen, null)
        }
    }

    /** Where [text] is on the screen now, without selecting it. */
    suspend fun rectOf(text: String): RectF {
        val quoted = org.json.JSONObject.quote(text)
        val found = js("""(function(find){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n;
            while((n=w.nextNode())){var i=n.nodeValue.indexOf(find);if(i>=0){var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+find.length);
            var b=r.getBoundingClientRect();return 'ok '+[b.left,b.top,b.right,b.bottom].join(',');}}return 'none';})($quoted)""")
        check(found.contains("ok ")) { "'$text' is not on the page: $found" }
        val (l, t, r, b) = found.substringAfter("ok ").trim('"').split(',').map { it.toFloat() }
        val d = activity.resources.displayMetrics.density
        val page = IntArray(2)
        withContext(Dispatchers.Main) { navigator.view!!.getLocationOnScreen(page) }
        return RectF(l * d + page[0], t * d + page[1], r * d + page[0], b * d + page[1])
    }

    /** A finger down and up at [x], [y] on the screen: a tap, through the page's own web view. */
    fun tap(x: Float, y: Float) {
        val down = android.os.SystemClock.uptimeMillis()
        val event = { action: Int, at: Long -> android.view.MotionEvent.obtain(down, at, action, x, y, 0) }
        ins.sendPointerSync(event(android.view.MotionEvent.ACTION_DOWN, down))
        ins.sendPointerSync(event(android.view.MotionEvent.ACTION_UP, down + 60))
    }

    /** How many elements the page has for a CSS [selector]. */
    suspend fun count(selector: String): Int = js("document.querySelectorAll(${org.json.JSONObject.quote(selector)}).length").trim('"').toIntOrNull() ?: 0

    val shelf: com.pocketds.hub.reader.AnnotationShelf get() = screen.field("annotations")

    suspend fun selectAndWait(text: String) {
        select(text)
        until("the card for '$text'") { card.isOpen }
        delay(500)
    }

    /** The card's control with this description. */
    fun control(description: String): View = card.control(description) ?: throw AssertionError("no '$description' on the card: ${card.controlLabels}")

    suspend fun press(description: String) = withContext(Dispatchers.Main) { control(description).performClick() }

    /** The card's rectangle and the words', both in the screen's own coordinates. */
    fun onScreen(rect: RectF): RectF {
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        return RectF(rect).apply { offset(at[0].toFloat(), at[1].toFloat()) }
    }

    /** What the screen anchored the card to, in the screen's own coordinates, for a failure to say. */
    fun anchored(): RectF? = screen.field<com.pocketds.hub.reader.ReaderSelection?>("selection")?.rect?.let(::onScreen)

    fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** The whole screen, kept in Downloads for the report. */
    fun shot(name: String) { shell("screencap -p /sdcard/Download/reader62-$name.png") }
}

private fun host(activity: ReaderFixtureActivity) =
    Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
        when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
    } as ScreenHost

/** Runs [block] on the real reader showing [epub], with a stand-in hub; everything it changed on the device is put back. */
@OptIn(ExperimentalReadiumApi::class)
suspend fun withSelectionBook(epub: ByteArray = ReaderFixtures.selectionEpub(), prepare: HighlightsHub.() -> Unit = {}, block: suspend SelectionBook.() -> Unit) {
    val ins = InstrumentationRegistry.getInstrumentation()
    val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
    check(activity.packageName.endsWith(".uitest")) { "Only the isolated .uitest application may be driven" }
    val original = EpubAppearanceStore.load(activity)
    val oldInfo = PageInfoSettings.load(activity)
    val oldComfort = ComfortSettings.load(activity)
    val oldUrl = HubSettings.baseUrl(activity)
    val oldToken = HubSettings.token(activity)
    val oldColor = HighlightSettings.color(activity)
    val hub = HighlightsHub(epub).apply(prepare)
    HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
    var screen: EpubReaderScreen? = null
    val voice = RecordingVoice()
    try {
        EpubAppearanceStore.save(activity, EpubReaderPreferences())
        ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
        PageInfoSettings.save(activity, PageInfoChoice())
        lateinit var root: View
        val workId = "sel-${System.nanoTime()}"
        withContext(Dispatchers.Main) {
            screen = EpubReaderScreen(HubClient(activity), workId, "edition", "The Lantern Keeper", { true }, bookPages = 120)
            screen!!.javaClass.getDeclaredField("voiceFactory").apply { isAccessible = true }.set(screen, { _: android.content.Context, _: (String) -> Unit -> voice })
            root = screen!!.onCreateView(host(activity), FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
        }
        val book = SelectionBook(activity, screen!!, root, hub, workId, voice)
        book.until("the book") { activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().isNotEmpty() && book.run { screen!!.field<View>("loading").visibility != View.VISIBLE } }
        delay(1_200)
        book.block()
    } catch (failure: Throwable) {
        // Read to the end: the command is not done until it has been, and the page is gone by the time a closed stream is looked at.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/reader62-failure.png")).use { it.readBytes() }
        throw failure
    } finally {
        withContext(Dispatchers.Main) { screen?.onHide(); screen?.onDestroyView(); activity.finish() }
        EpubAppearanceStore.save(activity, original)
        PageInfoSettings.save(activity, oldInfo)
        ComfortSettings.save(activity, oldComfort)
        HubSettings.save(activity, oldUrl, oldToken)
        HighlightSettings.setColor(activity, oldColor)
        hub.shutdown()
    }
}
