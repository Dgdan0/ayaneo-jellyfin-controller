package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingEdition
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
    val voice: RecordingVoice,
    /** The screens the reader asked the app to open (a switch of mode), and what it said to the person. */
    val pushed: MutableList<Any> = ArrayList(),
    val notes: MutableList<String> = ArrayList()
) {
    private val ins = InstrumentationRegistry.getInstrumentation()

    /** The screen on show: the one the reader was opened as, then each one a switch of mode opened (the app lets the one before go). */
    var current: com.pocketds.hub.nav.Screen = screen

    inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    val card: DictionaryCard get() = current.field("dictionaryCard")
    val overlay: SidePanelView get() = current.field("overlay")
    private val navigator: EpubNavigatorFragment get() = current.field<EpubNavigatorFragment?>("navigator")!!

    suspend fun until(what: String, limitMs: Long = 25_000, check: () -> Boolean) {
        try { withTimeout(limitMs) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    suspend fun js(script: String): String = withContext(Dispatchers.Main) {
        navigator.evaluateJavascript(script) ?: ""
    }

    private var left = false

    /** Lets the screen in front go, as leaving the reader does: it saves the place it was on. [adopt] does this itself unless it was done. */
    suspend fun leave() = withContext(Dispatchers.Main) {
        if (!left) { current.onHide(); current.onDestroyView(); left = true }
    }

    /** Opens a screen the reader pushed, as the app would, and lets the one it replaces go. */
    suspend fun adopt(next: Any): View = withContext(Dispatchers.Main) {
        val hosting = host(activity, pushed, notes)
        val opened = next as com.pocketds.hub.nav.Screen
        if (!left) { current.onHide(); current.onDestroyView() }
        left = false
        val view = opened.onCreateView(hosting, FrameLayout(activity))
        activity.setContentView(view)
        opened.onShow()
        current = opened
        view
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
            val inspect = current.javaClass.getDeclaredMethod("inspectSelection", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            inspect.invoke(current, null)
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

    /**
     * A finger down at ([fromX], [fromY]) that stays [holdMs] (a long press), then goes to ([toX], [toY]) over [moveMs] and lifts: the
     * drag that extends a selection, as a person makes it. Real touch events, so the reader's own touch handling sees them as it does.
     */
    suspend fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, holdMs: Long, moveMs: Long) {
        val down = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        ins.sendPointerSync(event(android.view.MotionEvent.ACTION_DOWN, fromX, fromY))
        delay(holdMs)
        val steps = 12
        for (i in 1..steps) {
            delay(moveMs / steps)
            ins.sendPointerSync(event(android.view.MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps))
        }
        ins.sendPointerSync(event(android.view.MotionEvent.ACTION_UP, toX, toY))
    }

    /** How many elements the page has for a CSS [selector]. */
    suspend fun count(selector: String): Int = js("document.querySelectorAll(${org.json.JSONObject.quote(selector)}).length").trim('"').toIntOrNull() ?: 0

    val shelf: com.pocketds.hub.reader.AnnotationShelf get() = current.field("annotations")

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
    fun anchored(): RectF? = current.field<com.pocketds.hub.reader.ReaderSelection?>("selection")?.rect?.let(::onScreen)

    fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /** The whole screen, kept in Downloads for the report. */
    fun shot(name: String) { shell("screencap -p /sdcard/Download/reader62-$name.png") }
}

private fun host(activity: ReaderFixtureActivity, pushed: MutableList<Any>, notes: MutableList<String>) =
    Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
        when (method.name) {
            "getViewContext" -> activity
            "back" -> true
            "push" -> { pushed += args!![0]; null }
            "notify" -> { notes += args!![0] as String; null }
            else -> null
        }
    } as ScreenHost

/** Runs [block] on the real reader showing [epub], with a stand-in hub; everything it changed on the device is put back. */
@OptIn(ExperimentalReadiumApi::class)
suspend fun withSelectionBook(
    epub: ByteArray = ReaderFixtures.selectionEpub(),
    prepare: HighlightsHub.() -> Unit = {},
    /** The audiobook's stand-in, when the book has one: it makes the book a read-along and an audio book as well. */
    stand: StandInHub? = null,
    workId: String = "sel-${System.nanoTime()}",
    sourceItemId: String = "edition",
    block: suspend SelectionBook.() -> Unit
) {
    val ins = InstrumentationRegistry.getInstrumentation()
    val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
    check(activity.packageName.endsWith(".uitest")) { "Only the isolated .uitest application may be driven" }
    val original = EpubAppearanceStore.load(activity)
    val oldInfo = PageInfoSettings.load(activity)
    val oldComfort = ComfortSettings.load(activity)
    val oldUrl = HubSettings.baseUrl(activity)
    val oldToken = HubSettings.token(activity)
    val oldColor = HighlightSettings.color(activity)
    val hub = HighlightsHub(epub, stand).apply(prepare)
    HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
    var screen: EpubReaderScreen? = null
    var shown: SelectionBook? = null
    val voice = RecordingVoice()
    try {
        EpubAppearanceStore.save(activity, EpubReaderPreferences())
        ComfortSettings.save(activity, com.pocketds.hub.ui.ScreenComfort())
        PageInfoSettings.save(activity, PageInfoChoice())
        lateinit var root: View
        val pushed = ArrayList<Any>()
        val notes = ArrayList<String>()
        val hosting = host(activity, pushed, notes)
        withContext(Dispatchers.Main) {
            val editions = stand != null
            screen = EpubReaderScreen(HubClient(activity), workId, sourceItemId, "The Lantern Keeper", { true }, bookPages = 120,
                readAlongAvailable = editions, ebookSourceItemId = sourceItemId,
                alignedEditions = if (editions) listOf(ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = sourceItemId, narrator = "A generated voice")) else emptyList(),
                audioEditions = if (editions) listOf(ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = sourceItemId, narrator = "A generated voice")) else emptyList())
            screen!!.javaClass.getDeclaredField("voiceFactory").apply { isAccessible = true }.set(screen, { _: android.content.Context, _: (String) -> Unit -> voice })
            root = screen!!.onCreateView(hosting, FrameLayout(activity)); activity.setContentView(root); screen!!.onShow()
        }
        val book = SelectionBook(activity, screen!!, root, hub, workId, voice, pushed, notes)
        shown = book
        book.until("the book") { activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().isNotEmpty() && book.run { screen!!.field<View>("loading").visibility != View.VISIBLE } }
        delay(1_200)
        book.block()
    } catch (failure: Throwable) {
        // Read to the end: the command is not done until it has been, and the page is gone by the time a closed stream is looked at.
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/reader62-failure.png")).use { it.readBytes() }
        throw failure
    } finally {
        withContext(Dispatchers.Main) {
            val last = shown?.current ?: screen
            last?.onHide(); last?.onDestroyView()
            // The audiobook plays on without its screen (it is a service); a test that began it ends it.
            if (com.pocketds.hub.reader.ReadingAudio.state.value.book != null) com.pocketds.hub.reader.ReadingAudio.stop()
            activity.finish()
        }
        for (i in 0 until 50) { if (com.pocketds.hub.reader.ReadingAudio.state.value.book == null) break; delay(100) }
        EpubAppearanceStore.save(activity, original)
        PageInfoSettings.save(activity, oldInfo)
        ComfortSettings.save(activity, oldComfort)
        HubSettings.save(activity, oldUrl, oldToken)
        HighlightSettings.setColor(activity, oldColor)
        hub.shutdown()
    }
}
