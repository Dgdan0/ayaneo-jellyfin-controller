package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.animation.DecelerateInterpolator
import com.pocketds.hub.settings.ReadingPaceSettings
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.shared.util.AbsoluteUrl
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.FocusFinder
import android.widget.SeekBar
import android.widget.EditText
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.activateOnTap
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.pocketds.hub.settings.HighlightSettings
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import com.pocketds.hub.settings.ComfortSettings
import com.pocketds.hub.settings.PageInfoSettings
import com.pocketds.hub.ui.ComfortLayerView
import com.pocketds.hub.ui.ScreenComfort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.json.JSONArray
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme as ReadiumTheme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * Native reflowable EPUB reader. Readium renders the book; this screen owns
 * Pocket input and sync. Its keys are [ReaderPadMap]'s (#16): Ⓑ opens the
 * menu, the page shrinking inside it, and Ⓑ again leaves the book; every key
 * stays here; in continuous scrolling the D-pad and the right stick scroll;
 * read along, L3 goes back to the narrated sentence; R3 lists the keys. The
 * menu carries a row of the keys and a Keys control.
 *
 * The menu is bars of the cover's glass (#16, X7), and the page makes
 * room for them (the owner's choice for books): it shrinks with the menu round
 * it. Read along is the book with a player (#21): the glass narration dock is
 * the menu's lower bar, the page closed round it fills the screen with the
 * "Following" pill, and the sentence being read glows in the accent
 * ([ReadAlongGlow]). A tap on the page shows or hides the menu and leaves the
 * voice alone. Comfort (X3)
 * dims and warms the reader, can make the page black, and keeps the screen on
 * while narration plays.
 *
 * Reading with the sticks (#18, E1): with Scroll on, the D-pad's up and down
 * scroll a third of a screen and the right stick glides, both on into the next
 * part of the book at the end of one ([BookScroll]). Under the title, the time
 * left in the chapter and the book, from a pace learnt as you read
 * ([ReadingPace]) or, following the narration, from the narration (E3). A
 * footnote opens as a card over the page ([FootnoteCard]), and a link followed
 * leaves "Return to previous place" in the menu (E5).
 */
@OptIn(ExperimentalReadiumApi::class)
class EpubReaderScreen(
    private val api: HubApi,
    private val workId: String,
    private val sourceItemId: String,
    override val title: String,
    private val ringVisible: () -> Boolean,
    private val onProgressChanged: () -> Unit = {},
    private val readAlong: Boolean = false,
    private val readAlongAvailable: Boolean = false,
    private val alignedEditions: List<ReadingEdition> = emptyList(),
    private val audioEditions: List<ReadingEdition> = emptyList(),
    private val ebookSourceItemId: String = sourceItemId,
    /**
     * The book's own page count from the hub ([ReadingBookFacts.pages]), 0 when it has none: the corners'
     * "Page in book" counts those pages, as the book's page and Resume do, and Readium's positions only without (#42).
     */
    private val bookPages: Int = 0,
    /** Where this screen opens when it is the result of a switch of mode (#62): the sentence to begin at, or to mark. */
    private val entry: ModeEntry = ModeEntry()
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val immersive = true
    override val focusOnShow = false

    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    /** The page, which the menu shrinks: Readium's host inside it, and Kindle's corners over it (#42). */
    private lateinit var navigatorContainer: FrameLayout
    /** Where Readium's navigator lives, inset from the top and the foot by the strips the corners keep clear (#42). */
    private lateinit var pageHost: FrameLayout
    private lateinit var pageInfo: PageInfoView
    private var pageChoice = PageInfoChoice()
    /** The page's side inset in pixels, where a tap turns the page itself (#47). */
    private var insetPx = 0
    private lateinit var loading: TextView
    private lateinit var bars: ReaderBars
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var comfortLayer: ComfortLayerView
    private var comfort = ScreenComfort()
    private lateinit var position: TextView
    private lateinit var bookSeek: SeekBar
    private lateinit var returnButton: TextView
    private var bookPositions: List<Locator> = emptyList()
    private var bookSections: List<Locator> = emptyList()
    /**
     * The book's documents in reading order, each in [DocumentPath]'s one spelling (what the narration's timeline holds, #61),
     * and the way Readium spells each one's href, to hand back to it in a locator.
     */
    private var documentOrder: List<String> = emptyList()
    private var documentSpelling: Map<String, String> = emptyMap()
    private var returnLocator: Locator? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0
    private lateinit var overlay: ChoiceOverlay
    private lateinit var appearance: EpubAppearancePanel
    private lateinit var pagePreview: ReaderPagePreviewController
    private lateinit var colors: PocketColors
    private val controls = mutableListOf<View>()
    private var focusedControl = 0
    private var controlsVisible = true

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var progress: ReadingProgress
    private lateinit var readingSession: ReadingProgress.Session
    private lateinit var checkpointKey: ReadingCheckpointKey
    private lateinit var bookmarks: EpubBookmarkStore
    private lateinit var bookmarkButton: TextView
    private var checkpointErrorShown = false
    private var loadJob: Job? = null
    private var locatorJob: Job? = null
    private var navigator: EpubNavigatorFragment? = null
    private var publication: Publication? = null
    private var latestLocator: Locator? = null
    private var preferences = EpubReaderPreferences()
    private lateinit var preferenceState: EpubPreferenceState
    private var pageIndex = 0
    private var pageCount = 0
    private val json = Json { ignoreUnknownKeys = true }
    private var narration: ReadAlongPlayback? = null
    private val narrationCheckpoint = ReadAlongSession()
    private var narrationCompleted = false
    private var highlightJob: Job? = null
    private lateinit var narrationDock: ReadAlongDock
    private lateinit var dictionaryCard: DictionaryCard
    private lateinit var dictionary: OfflineEnglishDictionary
    private val selectionGate = NarrationSelectionGate()
    /** This profile's highlights of the book (#62), what is drawn of them, and the card's state (#62). */
    private lateinit var annotations: AnnotationShelf
    private var annotationJob: Job? = null
    private var drawnAnnotations: List<ReadingAnnotation> = emptyList()
    private var selection: ReaderSelection? = null
    private var shownHighlight: ReadingAnnotation? = null
    private var phraseAnswer: PhraseLookup.Answer? = null
    private var voice: SpeechVoice? = null
    /** The place another device reached, to ask about once the narration is ready: "You listened further on ..." (#62). */
    private class AwayCandidate(val away: ListenedFurther.Away, val ours: ReadingLocation?)
    private var awayCandidate: AwayCandidate? = null
    /** The one mode button (#62), the name it shows while open, and the words just selected when it was opened. */
    private lateinit var modeButton: ModeButtonView
    private lateinit var modeCaption: TextView
    private var capturedSelection: ReaderSelection? = null
    private var modeJob: Job? = null
    /** Where the voice stopped when the book was opened from Audio or Read along: the "Heard to here" mark (#62). */
    private var heardAnchor: SentenceAnchor? = null
    /** The read-along edition this screen narrates from, and the manifest that maps its audio onto the audiobook's tracks. */
    private var alignedFile: File? = null
    private var narrationManifest: ReadingAudioManifest? = null
    private val currentMode: ReadingMode get() = if (readAlong) ReadingMode.ALONG else ReadingMode.EBOOK
    /** The controller's text cursor (#62): set while the person is choosing words with the D-pad. */
    private var cursorWords: TextCursor? = null
    private var cursorJob: Job? = null
    /** How the speaker is made; a test sets a stand-in. */
    internal var voiceFactory: (Context, (String) -> Unit) -> SpeechVoice = { context, problem -> ReaderVoice(context, problem) }
    private var selectionJob: Job? = null
    private var dictionaryJob: Job? = null
    private var dockJob: Job? = null
    private var selectionGeneration = 0
    private var matchNarrationToPage = false
    /**
     * Read along (#49): the page and the voice move each other. What the page shows of the narration, probed after
     * every page change settles; null while a page is being turned or probed, so nothing acts on a page that is gone.
     */
    private var pageSpan: PageSpan? = null
    private var pageKey: String? = null
    private var probeJob: Job? = null
    private var probeFailures = 0
    private var followJob: Job? = null
    /** A page change of our own making (the voice turning the page, or the page brought to it) is expected until then. */
    private var ownMoveUntil = 0L
    /** The sentence the page was last sent to, and the page last turned from: neither is asked for twice. */
    private var lastSent: ReadAlongSegment? = null
    private var turnedFrom: PageSpan? = null
    private var lastFollowed: ReadAlongPosition? = null
    /** The screen is off or another app is in front (#49): the voice plays on, held by [NarrationService], the page waits. */
    private var backgrounded = false
    private var voiceAtBackground: ReadAlongPosition? = null
    private val narratedFragments = HashMap<String, List<String>>()
    /** Scrolling with the D-pad and the right stick (E1): whole pixels, and on into the next part at the end. */
    private var bookScroll = BookScroll(edgePx = 0f)
    private var stepAnimator: ValueAnimator? = null
    /** Time left (E3): the positions in each part, this book's pace, and where it was last measured from. */
    private var sectionSizes: List<Int> = emptyList()
    /** Where each part of the book starts in it, as how far through ([PageInfo.sectionSpan]). */
    private var sectionStarts: List<Double?> = emptyList()
    /**
     * The page each Contents entry starts on (#55), made once when the book opens. Entries that point into a file
     * wait for their anchor's share of it, read in the background ([contentsJob]); Contents never waits for that.
     */
    private var contentsPages: ContentsPages? = null
    private var contentsJob: Job? = null
    /** Contents' rows while it is on screen, so a number still being worked out is filled in when it arrives. */
    private var contentsRows: List<Pair<View, Link>> = emptyList()
    private var pace = ReadingPace()
    private var pacePrior = ReadingPace.DEFAULT_MINUTES_PER_POSITION
    private val paceTracker = ReadingPace.Tracker()
    /** Under the title: "12 min left in chapter · 4h 10m in book". */
    private lateinit var timeLeftView: TextView
    /** Footnotes and links (E5): the note's card, and where a link was followed from, for a moment. */
    private lateinit var footnoteCard: FootnoteCard
    @Volatile private var linkOrigin: Locator? = null
    @Volatile private var linkOriginAt = 0L

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        progress = ReadingProgress.get(host.viewContext)
        readingSession = progress.session()
        checkpointKey = readingSession.key(workId, sourceItemId, "epub")
        annotations = AnnotationRepository.get(host.viewContext).shelf(readingSession, workId)
        bookmarks = EpubBookmarkStore(File(host.viewContext.filesDir, "reading-bookmarks"))
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(Color.BLACK) }
        navigatorContainer = object : FrameLayout(host.viewContext) {
            private var longPressCheck: Runnable? = null
            /**
             * A touch that began where Readium's page is not (#47, #64): the side inset, or the strip above or below the page where the
             * corners live. It is the reader's own: a tap in the side inset turns the page, and so does a swipe from anywhere there.
             */
            private var owned = false
            private var ownedSide = PageGeometry.InsetTap.NONE
            private var ownedDownX = 0f
            private var ownedDownY = 0f

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (owned) return followOwnTouch(event)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // A turn still animating ends as the finger lands, so a quick swipe after a swipe turns the page it arrived on, not
                    // the one it was leaving, which Readium's pager lets a finger catch half way (#64).
                    PageTurns.finishRunning(this)
                    var place = PageTurns.place(this)
                    // In the middle of a burst the pager may have undone the last turn (it does, now and then): the page the account says first.
                    val expected = turnLedger.expected(SystemClock.uptimeMillis())
                    if (place != null && expected != null && place.item != expected && PageTurns.setItem(this, expected, smooth = false)) {
                        place = place.copy(item = expected)
                    }
                    flickFrom = place
                    flickDownX = event.x; flickDownY = event.y
                    longPressCheck?.let { removeCallbacks(it) }
                    longPressCheck = Runnable { inspectSelection() }.also {
                        postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong() + 80)
                    }
                }
                val inspect = event.actionMasked == MotionEvent.ACTION_UP &&
                    event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    longPressCheck?.let { removeCallbacks(it) }
                    longPressCheck = null
                }
                if (event.actionMasked == MotionEvent.ACTION_UP && turnPastTheEnd(event)) return true
                val consumed = super.dispatchTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP) countFlick(event)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) flickFrom = null
                if (inspect) postDelayed({ inspectSelection() }, 120)
                // Nothing under the finger took it: the margin or a strip, and it is ours.
                if (!consumed && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    longPressCheck?.let { removeCallbacks(it) }
                    longPressCheck = null
                    owned = true
                    ownedSide = PageGeometry.inset(event.x, width.toFloat(), insetPx.toFloat())
                    ownedDownX = event.x; ownedDownY = event.y
                    return true
                }
                return consumed
            }

            /**
             * A flick on the last page of a file, or the first, goes on into the next file or back into the one before. Readium's own
             * swipe does that at its own pace and loses some of a quick burst (4 swipes of 10 across the end of a chapter), where its
             * pad press, one for one, loses none. So the finger is let go of (cancelled, and the page springs back) and the reader makes
             * the turn the pad's way. True when it did.
             */
            private fun turnPastTheEnd(event: MotionEvent): Boolean {
                val from = flickFrom ?: return false
                val dx = event.x - flickDownX
                val dy = event.y - flickDownY
                if (!PageSwipe.isFlick(dx, dy, event.eventTime - event.downTime, resources.displayMetrics.density)) return false
                val rtl = navigator?.overflow?.value?.readingProgression == org.readium.r2.navigator.preferences.ReadingProgression.RTL
                val delta = if (PageSwipe.forward(dx < 0, rtl)) 1 else -1
                // Where the account says the page is, if a burst is running: the pager may not be there yet.
                val at = turnLedger.expected(SystemClock.uptimeMillis()) ?: from.item
                if (at + delta in 0 until from.pages) return false
                flickFrom = null
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                turn(delta)
                return true
            }

            /**
             * A flick is one turn, counted here ([TurnLedger]) and not by Readium's pager, which under quick swipes drops one now and then
             * (the page drags and springs back) or takes one and undoes it a moment later. The page the account says is where the pager is
             * put after the flick, and kept for a moment against a late undoing. A drag that is not a flick (the pager snaps it back by its
             * own rule), the end of a file (the pager of files has the swipe) and a pad press end the account.
             */
            private fun countFlick(event: MotionEvent) {
                val from = flickFrom ?: return
                flickFrom = null
                val dx = event.x - flickDownX
                val dy = event.y - flickDownY
                if (!PageSwipe.isFlick(dx, dy, event.eventTime - event.downTime, resources.displayMetrics.density)) { turnLedger.end(); return }
                val rtl = navigator?.overflow?.value?.readingProgression == org.readium.r2.navigator.preferences.ReadingProgression.RTL
                val target = turnLedger.flick(from, if (PageSwipe.forward(dx < 0, rtl)) 1 else -1, SystemClock.uptimeMillis()) ?: return
                fun keep() {
                    if (!turnLedger.stillWanted(target, SystemClock.uptimeMillis())) return
                    val now = PageTurns.place(this) ?: return
                    if (now.item != target) PageTurns.setItem(this, target, smooth = true)
                }
                keep()
                for (after in TurnLedger.KEEP_AT_MS) postDelayed({ keep() }, after)
            }

            private fun followOwnTouch(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        owned = false
                        val configuration = ViewConfiguration.get(context)
                        when (val outcome = PageSwipe.classify(event.x - ownedDownX, event.y - ownedDownY, event.eventTime - event.downTime,
                            width.toFloat(), configuration.scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())) {
                            PageSwipe.Outcome.Tap -> if (ownedSide != PageGeometry.InsetTap.NONE) onInsetTap(ownedSide)
                            is PageSwipe.Outcome.Swipe -> onSwipeTurn(outcome.leftwards)
                            PageSwipe.Outcome.Nothing -> Unit
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> owned = false
                }
                return true
            }
        }.apply {
            id = View.generateViewId()
            setBackgroundColor(Color.BLACK)
        }
        pageHost = FrameLayout(host.viewContext).apply { id = View.generateViewId() }
        navigatorContainer.addView(pageHost, FrameLayout.LayoutParams(MATCH, MATCH))
        pageInfo = PageInfoView(host.viewContext).apply { onCycle = ::cyclePageInfo; onSwipe = ::onSwipeTurn }
        navigatorContainer.addView(pageInfo, FrameLayout.LayoutParams(MATCH, MATCH))
        pageChoice = PageInfoSettings.load(host.viewContext)
        root.addView(navigatorContainer, FrameLayout.LayoutParams(MATCH, MATCH))
        loading = TextView(host.viewContext).apply {
            text = "Preparing book…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xDD101116.toInt())
        }
        root.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        bars = ReaderBars(host.viewContext, colors, ReaderBars.ROW_DP, BOTTOM_ROW_DP) { onPad(it) }
        buildTopBar()
        buildBottomBar()
        root.addView(bars.top, bars.topParams())
        root.addView(bars.bottom, bars.bottomParams())
        modeButton.attachScrim(root, bars.top)
        modeCaption = TextView(host.viewContext).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = ThemeGradientDrawable().apply { setColor(0xE60A0D12.toInt()); cornerRadius = dp(10).toFloat() }
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        root.addView(modeCaption, FrameLayout.LayoutParams(WRAP, WRAP))
        buildNarrationDock()
        preferences = loadPreferences()
        preferenceState = EpubPreferenceState(preferences)
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        appearance = EpubAppearancePanel(host.viewContext, colors, ringVisible)
        root.addView(appearance, FrameLayout.LayoutParams(MATCH, MATCH))
        dictionary = OfflineEnglishDictionary(host.viewContext)
        dictionaryCard = DictionaryCard(host.viewContext, colors, ringVisible).apply {
            onClose = { closeDictionary(resumeNarration = true) }
            onColor = { color -> shownHighlight?.let { recolour(it, color) } ?: highlightSelection(color, thenNote = false) }
            onNote = { shownHighlight?.let(::editNote) ?: highlightSelection(HighlightSettings.color(host.viewContext), thenNote = true) }
            onCopy = ::copySelection
            onSay = ::say
            onLookUp = ::lookUpSelection
            onRemove = { shownHighlight?.let(::removeHighlight) }
        }
        root.addView(dictionaryCard, FrameLayout.LayoutParams(MATCH, MATCH))
        footnoteCard = FootnoteCard(host.viewContext, colors, ringVisible).apply {
            onClose = ::closeFootnote
        }
        root.addView(footnoteCard, FrameLayout.LayoutParams(MATCH, MATCH))
        bookScroll = BookScroll(edgePx = host.viewContext.resources.displayMetrics.heightPixels * EDGE_SCREENS)
        pace = ReadingPaceSettings.book(host.viewContext, paceKey())
        pacePrior = ReadingPaceSettings.prior(host.viewContext)
        // Over everything the reader draws, the menu and its sheets too, as a backlight would dim.
        comfort = ComfortSettings.load(host.viewContext)
        comfortLayer = ComfortLayerView(host.viewContext).apply { apply(comfort) }
        root.addView(comfortLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        // The owner's choice for books (X7): the page makes room, shrinking with the menu round it.
        // Read along is the book with a player (#21): the narration's dock is the menu's lower bar,
        // so the page makes room for it as for the book's row, and it goes with the menu.
        pagePreview = ReaderPagePreviewController(root, navigatorContainer, bars.top, bars.bottom, listOf(overlay, appearance), corners = pageInfo)
        focusedControl = controls.indexOfLast { it.contentDescription == "Next page" }.coerceAtLeast(0)
        applyPageInfo()
        setControlsVisible(false)
        return root
    }

    override fun onShow() {
        val wasBackgrounded = backgrounded
        backgrounded = false
        // The time away from the book is not reading.
        paceTracker.restart()
        val shared = loadPreferences()
        val kept = ComfortSettings.load(host.viewContext)
        if (shared != preferences || kept != comfort) {
            comfort = kept; comfortLayer.apply(kept)
            preferences = shared; preferenceState = EpubPreferenceState(shared); applyPreferences()
        }
        PageInfoSettings.load(host.viewContext).let { if (it != pageChoice) { pageChoice = it; applyPageInfo() } }
        pageInfo.start()
        val previousAudio = ReadingEntryPreferences.get(host.viewContext, workId)?.audioSourceItemId.orEmpty()
        ReadingEntryPreferences.put(host.viewContext, workId,
            if (readAlong) ReadingEntryMode.READ_ALONG else ReadingEntryMode.READ,
            if (readAlong) sourceItemId else previousAudio)
        if ((navigator == null || (readAlong && narration == null)) && loadJob?.isActive != true) openBook()
        else if (narration != null) {
            startDockUpdates()
            if (wasBackgrounded) catchUpWithVoice() else scheduleProbe()
        }
    }

    override fun onHide() {
        pageInfo.stop()
        cancelSearch()
        stopCursor()
        modeButton.close()
        closeDictionary(resumeNarration = false)
        voice?.stop()
        if (::footnoteCard.isInitialized) footnoteCard.dismiss()
        stepAnimator?.end()
        // Leaving the book pauses the voice; the screen going off does not (#49): it plays on, held by NarrationService.
        if (!backgrounded) narration?.pause()
        root.keepScreenOn = false
        dockJob?.cancel()
        followJob?.cancel()
        probeJob?.cancel()
        pageSpan = null
        if (::appearance.isInitialized && appearance.isOpen) appearance.cancel()
        if (::overlay.isInitialized) overlay.dismiss()
        saveCurrent(immediate = true)
        cancelPlace()
        loadJob?.cancel()
    }

    override fun onDestroyView() {
        cancelSearch()
        closeDictionary(resumeNarration = false)
        saveCurrent(immediate = true)
        narration?.release()
        narration = null
        pagePreview.dispose()
        selectionJob?.cancel()
        dictionaryJob?.cancel()
        annotationJob?.cancel()
        voice?.release()
        voice = null
        dockJob?.cancel()
        followJob?.cancel()
        probeJob?.cancel()
        locatorJob?.cancel()
        removeNavigator()
        publication?.close()
        publication = null
        uiScope.cancel()
        controls.clear()
    }

    /**
     * The app left the front (#49): the screen went off, or another app came up. A voice that is playing plays on,
     * held by [NarrationService] with its notification, and the page is left alone until we are back; one that is
     * paused stays so.
     */
    override fun onAppBackgrounded() {
        // The place as the page shows it; from now on it is the narration's.
        saveCurrent(immediate = true)
        backgrounded = true
        voiceAtBackground = narration?.position
        probeJob?.cancel()
    }

    override val requiresTriggerHold: Boolean get() = navigator != null &&
        !appearance.isOpen && !overlay.isOpen && !dictionaryCard.isOpen && !footnoteCard.isOpen

    override fun onSystemBack(): Boolean {
        if (::modeButton.isInitialized && modeButton.isOpen) { modeButton.close(); return true }
        if (::footnoteCard.isInitialized && footnoteCard.isOpen) { closeFootnote(); return true }
        if (::dictionaryCard.isInitialized && dictionaryCard.isOpen) { closeDictionary(resumeNarration = true); return true }
        if (::appearance.isInitialized && appearance.isOpen) { appearance.cancel(); return true }
        if (::overlay.isInitialized && overlay.isOpen) {
            overlay.onPad(PadAction.Back)
            return true
        }
        return false
    }

    override fun hints(): List<ButtonHint> = if (::footnoteCard.isInitialized && footnoteCard.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Close note"))
    } else if (::appearance.isInitialized && appearance.isOpen) {
        listOf(ButtonHint.activate("Adjust"), ButtonHint.back("Close appearance"))
    } else if (::dictionaryCard.isInitialized && dictionaryCard.isOpen) {
        listOfNotNull(ButtonHint.activate("Choose"), ButtonHint.back(if (dictionaryCard.showsDefinitions) "Close definition" else "Close"),
            ButtonHint.secondary("Mode").takeIf { ::modeButton.isInitialized && modeButton.worthShowing })
    } else if (::overlay.isInitialized && overlay.isOpen) {
        listOf(
            ButtonHint.activate("Choose"),
            ButtonHint.back("Cancel")
        )
    } else ReaderPadMap.hints(padState())

    private fun padState() = ReaderPadState(
        ReaderKind.BOOK,
        controlsVisible = controlsVisible,
        scrolling = preferences.scroll && !preferences.onePagePerScreen,
        narration = narration != null,
        loading = navigator == null,
        cursor = cursorWords != null,
        anchored = cursorWords?.selecting == true,
        modes = ::modeButton.isInitialized && modeButton.worthShowing,
        picking = ::modeButton.isInitialized && modeButton.isOpen
    )

    override fun onPad(action: PadAction): Boolean {
        if (::footnoteCard.isInitialized && footnoteCard.onPad(action)) {
            host.refreshHints()
            return true
        }
        // Ⓨ on an open card is the mode button, with the words just selected the place the voice will start at (#62).
        if (action == PadAction.Secondary && ::dictionaryCard.isInitialized && dictionaryCard.isOpen && ::modeButton.isInitialized && modeButton.worthShowing) {
            openModes()
            return true
        }
        if (::dictionaryCard.isInitialized && dictionaryCard.onPad(action)) return true
        if (appearance.onPad(action)) return true
        if (::overlay.isInitialized && overlay.onPad(action)) {
            host.refreshHints()
            return true
        }
        when (val command = ReaderPadMap.command(padState(), action)) {
            ReaderCommand.Forward -> turn(1)
            is ReaderCommand.Page -> if (cursorWords != null) pageCursor(command.delta) else turn(command.delta)
            is ReaderCommand.Chapter -> changeChapter(if (command.delta > 0) Direction.DOWN else Direction.UP)
            is ReaderCommand.Controls -> setControlsVisible(command.visible)
            ReaderCommand.Leave -> host.back()
            ReaderCommand.Choose -> if (bookSeek.hasFocus()) seekBook()
                else (root.findFocus() ?: controls.getOrNull(focusedControl))?.performClick()
            is ReaderCommand.Focus -> if (bookSeek.hasFocus() && (command.direction == Direction.LEFT || command.direction == Direction.RIGHT)) {
                bookSeek.progress = (bookSeek.progress + if (command.direction == Direction.RIGHT) 1 else -1).coerceIn(0, bookSeek.max)
                position.text = "Browse · ${bookSeek.progress}% · A to jump"
            } else moveControlFocus(command.direction)
            ReaderCommand.Bookmark -> toggleBookmark()
            ReaderCommand.Contents -> {
                setControlsVisible(true)
                showNavigator()
            }
            ReaderCommand.Display -> {
                setControlsVisible(true)
                showAppearance()
            }
            ReaderCommand.Retry -> openBook()
            is ReaderCommand.Scroll -> step(if (command.direction == Direction.UP) -1 else 1)
            is ReaderCommand.Glide -> glide(command.dy)
            ReaderCommand.FollowNarration -> follow()
            is ReaderCommand.Sentence -> narration?.let { audio ->
                // The voice was moved on purpose: it and the page belong together again.
                matchNarrationToPage = false
                if (!audio.stepSentence(command.delta)) host.notify(if (command.delta > 0) "The last sentence" else "The first sentence")
                updateDock()
            }
            ReaderCommand.Keys -> showKeys()
            ReaderCommand.NextPageInfo -> cyclePageInfo()
            ReaderCommand.SelectText -> startCursor()
            is ReaderCommand.Cursor -> moveCursor(command.direction)
            is ReaderCommand.CursorAnchor -> anchorCursor(command.finish)
            is ReaderCommand.CursorGrow -> growCursor(command.paragraph)
            is ReaderCommand.CursorCancel -> if (command.anchored) cancelSelection() else stopCursor()
            ReaderCommand.Mode -> openModes()
            is ReaderCommand.ModeMove -> modeButton.move(command.delta)
            ReaderCommand.ModePick -> modeButton.pick()
            ReaderCommand.ModeClose -> modeButton.close()
            else -> Unit
        }
        return true
    }

    /** The Controls sheet: every key and what it does in this book, as it is set to read. */
    private fun showKeys() {
        setControlsVisible(true)
        ReaderKeys.show(overlay, padState().copy(controlsVisible = false))
    }

    /** Comfort (X3): the same glass sheet as every reader's, with the screen kept on while narrating. */
    private fun showComfort() {
        setControlsVisible(true)
        ComfortSheet.show(overlay, colors, ReaderKind.BOOK, ::applyComfort)
    }

    /** The brightness slider at the foot of Appearance (#47): kept for every reader, as Comfort's own was. */
    private fun setBrightness(brightness: Float) {
        val next = comfort.copy(brightness = brightness)
        ComfortSettings.save(host.viewContext, next)
        applyComfort(next)
    }

    private fun applyComfort(value: ScreenComfort) {
        comfort = value
        comfortLayer.apply(value)
        updateAwake()
    }

    /** The screen stays on while narration plays, if Comfort says so. */
    private fun updateAwake() {
        if (::root.isInitialized) root.keepScreenOn = comfort.keepsScreenOn(narration?.isPlaying == true)
    }

    /** The page's colours, the theme's: "Use system colours" is Paper by day and Dark at night (#47). */
    private fun pagePalette(value: EpubReaderPreferences): Pair<Int, Int> = EpubPagePalette.of(value.theme, isNight())

    /** Whether the system is at night: the system's own setting, since the app itself always runs in night mode. */
    private fun isNight(): Boolean =
        (android.content.res.Resources.getSystem().configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Readium's highlight is the read-along's glow ([ReadAlongGlow]): narration is its only highlight. Its underline is the
     * reader's marks (#62): the person's highlights, their note marks and "Heard to here", told apart by the decoration's kind.
     */
    private fun decorationTemplates(): HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates().copy().apply {
        set(Decoration.Style.Highlight::class, HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration -> ReadAlongGlow.element((decoration.style as? Decoration.Style.Highlight)?.tint ?: colors.accent) },
            stylesheet = ReadAlongGlow.STYLESHEET
        ))
        set(Decoration.Style.Underline::class, HtmlDecorationTemplate(
            layout = HtmlDecorationTemplate.Layout.BOXES,
            width = HtmlDecorationTemplate.Width.WRAP,
            element = { decoration ->
                ReaderMarks.element(decoration.extras[ReaderMarks.KIND] as? String, (decoration.style as? Decoration.Style.Underline)?.tint ?: colors.accent)
            },
            stylesheet = ReaderMarks.STYLESHEET
        ))
    }

    private fun refreshKeys() {
        if (::bars.isInitialized) bars.keys.setHints(ReaderPadMap.hints(padState().copy(controlsVisible = true)))
    }

    /**
     * Continuous scrolling, the D-pad (E1): a third of a screen, eased over
     * [STEP_MS] rather than jumped, and at the end of a part on into the next.
     * Readium scrolls each part of the book inside its own web view; the one
     * on screen takes the scroll.
     */
    private fun step(sign: Int) {
        val web = visibleWebView() ?: return
        // A step still easing in finishes first, so the ends are judged from where it lands.
        stepAnimator?.end()
        scroll(web, bookScroll.step(sign * (web.height * SCROLL_STEP).toInt(), web.canScrollVertically(1), web.canScrollVertically(-1)), ease = true)
    }

    /** The right stick (E1): [dy] stick-seconds, at most [GLIDE] screens a second, the parts of a pixel carried. */
    private fun glide(dy: Float) {
        val web = visibleWebView() ?: return
        stepAnimator?.end()
        scroll(web, bookScroll.glide(dy * GLIDE * web.height, web.canScrollVertically(1), web.canScrollVertically(-1)), ease = false)
    }

    private fun scroll(web: android.webkit.WebView, move: BookScroll.Move, ease: Boolean) {
        when (move) {
            is BookScroll.Move.By -> if (!ease) web.scrollBy(0, move.px) else {
                var moved = 0
                stepAnimator = ValueAnimator.ofInt(0, move.px).apply {
                    duration = STEP_MS
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { animation ->
                        val now = animation.animatedValue as Int
                        web.scrollBy(0, now - moved)
                        moved = now
                    }
                    start()
                }
            }
            BookScroll.Move.NextPart -> turn(1)
            BookScroll.Move.PreviousPart -> turn(-1)
            BookScroll.Move.Stay -> Unit
        }
    }

    private fun visibleWebView(): android.webkit.WebView? {
        fun all(view: View): Sequence<View> = sequenceOf(view) +
            if (view is ViewGroup) (0 until view.childCount).asSequence().flatMap { all(view.getChildAt(it)) } else emptySequence()
        val rect = android.graphics.Rect()
        return all(navigatorContainer).filterIsInstance<android.webkit.WebView>()
            .filter { it.isShown && it.getGlobalVisibleRect(rect) }
            .maxByOrNull { if (it.getGlobalVisibleRect(rect)) rect.width() * rect.height() else 0 }
    }

    private fun openBook(forceDownload: Boolean = false) {
        loadJob?.cancel()
        loading.visibility = View.VISIBLE
        loading.text = "Preparing book…"
        loadJob = uiScope.launch {
            // Read along (#19): the edition without its audio and the narration streamed from the
            // audiobook's tracks, when the hub can map them; the whole edition otherwise.
            var plan: NarrationPlan? = if (readAlong) {
                loading.text = "Preparing synchronized narration…"
                ReadAlongStream.plan(readingSession.api.readingAudioManifest(workId, sourceItemId))
            } else null
            val slimCache = editionCache("aligned-slim")
            val wholeCache = editionCache("aligned")
            // The hub could not be asked: the whole edition kept here reads along without it, the
            // slim one only as words.
            (plan as? NarrationPlan.Unreachable)?.let { unreachable ->
                plan = when {
                    !forceDownload && wholeCache.isComplete(workId, sourceItemId) -> NarrationPlan.Whole
                    !forceDownload && slimCache.isComplete(workId, sourceItemId) -> unreachable
                    else -> { showFailure(unreachable.failure.message); return@launch }
                }
            }
            val streamed = plan is NarrationPlan.Stream
            var file = editionFile(if (!readAlong) editionCache("") else if (streamed || plan is NarrationPlan.Unreachable) slimCache else wholeCache,
                forceDownload, omitAudio = streamed, revalidate = !readAlong || streamed)
            if (file == null && streamed && lastDownload?.code == AudiobookStream.NOT_STREAMABLE) {
                // The hub cannot cut this edition's audio out: the whole edition, as before.
                plan = NarrationPlan.Whole
                file = editionFile(wholeCache, forceDownload, omitAudio = false, revalidate = false)
            }
            if (file == null) { showFailure(lastDownload?.message ?: "The EPUB could not be downloaded"); return@launch }
            loading.text = "Opening book…"
            val settled = runCatching { progress.store.read(checkpointKey)?.takeIf { !it.pending }?.local }.getOrNull()
            val resume = try { progress.resume(readingSession, checkpointKey) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { showFailure("The saved reading position could not be read. It has been preserved."); return@launch }
            val completion = ReadingCompletionRepository.get(host.viewContext)
            val choice = if (completion.shouldStartAtBeginning(workId)) completion.ebookResume(workId, resume)
                else chooseReadingResume(overlay, progress, checkpointKey, resume)
                ?: run { showFailure("Choose a reading position to continue"); return@launch }
            val saved = choice.location?.locator?.let { parseSavedLocator(JSONObject(it.toString())) }
            awayCandidate = if (readAlong && entry.from == null && !resume.conflict)
                AwayPrompt.forText(settled, choice.location, progress.writerOf(checkpointKey), System.currentTimeMillis())?.let { AwayCandidate(it, settled) } else null
            if (readAlong) narrationCheckpoint.beginOpen()
            try {
                runCatching { attachNavigator(file, saved) }
                    .onFailure { showFailure("This EPUB could not be opened") }
                if (navigator != null && !readAlong) entry.anchor?.let { openAtVoice(it) }
                val narrated = plan
                if (navigator != null && readAlong) {
                    if (narrated is NarrationPlan.Unreachable) {
                        host.notify("The narration needs the hub. You can read this book meanwhile.")
                        loading.visibility = View.GONE
                    } else try { prepareNarration(file, saved, (narrated as? NarrationPlan.Stream)?.manifest) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) {
                        DebugLog.log("reader", "aligned narration setup failed: ${e.javaClass.simpleName}")
                        host.notify("Aligned narration could not be opened. You can still read this book.")
                        loading.visibility = View.GONE
                    }
                }
            } finally {
                if (readAlong) narrationCheckpoint.endOpenIfPending()
            }
        }
    }

    /**
     * A saved place as a locator. One saved by a build that wrote the narration's decoded document name into its href
     * (`Author - [Series 01] - Part_010.htm`, #61) is no valid href and does not parse as it is: its document is spelled
     * as Readium spells a path ([DocumentPath.encode]) and read again, so the book opens at the place and not at its start.
     */
    private fun parseSavedLocator(json: JSONObject): Locator? =
        Locator.fromJSON(json) ?: json.optString("href").takeIf(String::isNotBlank)?.let { href ->
            Locator.fromJSON(JSONObject(json.toString()).put("href", DocumentPath.encode(DocumentPath.of(href))))
        }

    /** The last download's failure, for the caller that tries another edition after a 409. */
    private var lastDownload: HubResult.Failed? = null

    /** This profile's copies of the book: the ebook ([kind] blank), the whole read-along edition, the slim one (#19). */
    private fun editionCache(kind: String) =
        EpubPackageCache(File(host.viewContext.cacheDir, "reading-epub/${readingSession.identity}" + if (kind.isEmpty()) "" else "/$kind"))

    /**
     * The edition from [cache], downloaded first when it is not complete there; null when it could not be.
     * A copy that is here is checked against the hub ([EpubEdition], #41) when [revalidate]: the ebook
     * and the slim read-along edition, which the hub rewrites. The whole read-along edition is not asked
     * about: the hub passes it through from Storyteller without conditional requests, so asking would cost
     * the hub a stream of hundreds of megabytes for nothing, and a change would be as much again to fetch.
     * Nor is an edition kept for a hub that could not be reached a moment ago.
     */
    private suspend fun editionFile(cache: EpubPackageCache, forceDownload: Boolean, omitAudio: Boolean, revalidate: Boolean): File? {
        lastDownload = null
        val opened = EpubEdition(cache, workId, sourceItemId).open(
            forceDownload, revalidate,
            fetch = { destination, check -> readingSession.api.downloadReadingEpub(workId, sourceItemId, destination, readAlong, omitAudio, check) },
            // From the transfer's thread when a newer edition begins to arrive.
            onStage = { stage ->
                val text = when {
                    stage == EpubEdition.Stage.UPDATING -> "Updating book…"
                    !readAlong -> "Downloading book…"
                    omitAudio -> "Downloading the book…"
                    else -> "Downloading aligned book and narration…"
                }
                loading.post { loading.text = text }
            }
        )
        return when (opened) {
            is EpubEdition.Opened.Ready -> {
                if (revalidate) DebugLog.log("reader", "epub ${opened.how.name.lowercase()}")
                opened.file
            }
            is EpubEdition.Opened.Failed -> { lastDownload = opened.failure; null }
        }
    }

    private suspend fun attachNavigator(file: File, initialLocator: Locator?) {
        removeNavigator()
        publication?.close()
        val context = host.viewContext
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val parser = DefaultPublicationParser(
            context, assetRetriever = retriever, httpClient = http, pdfFactory = null
        )
        val opener = PublicationOpener(publicationParser = parser)
        val asset = retriever.retrieve(file).getOrElse { error(it.toString()) }
        val opened = opener.open(asset, allowUserInteraction = false).getOrElse { error(it.toString()) }
        publication = opened
        bookPositions = withContext(Dispatchers.Default) { opened.positions() }
        bookSections = bookPositions.distinctBy { it.href }
        sectionSizes = bookSections.map { section -> bookPositions.count { it.href == section.href } }
        sectionStarts = bookSections.map { it.locations.totalProgression }
        documentOrder = opened.readingOrder.map { DocumentPath.of(it.href.toString()) }
        documentSpelling = HashMap<String, String>().also { spelled ->
            // The spelling the navigator reports for a page first, then the reading order's: both are what Readium resolves.
            bookSections.forEach { spelled.putIfAbsent(DocumentPath.of(it.href.toString()), it.href.toString()) }
            opened.readingOrder.forEach { spelled.putIfAbsent(DocumentPath.of(it.href.toString()), it.href.toString()) }
        }
        startContentsPages(opened)

        // The place in the spelling the book itself uses, whatever it was saved in (#61).
        val start = initialLocator?.let { saved ->
            documentSpelling[saved.document]?.takeIf { it != saved.href.toString() }
                ?.let { spelled -> Locator.fromJSON(JSONObject(saved.toJSON().toString()).put("href", spelled)) } ?: saved
        }
        val factory = EpubNavigatorFactory(opened).createFragmentFactory(
            initialLocator = start,
            initialPreferences = readiumPreferences(preferences),
            listener = linkListener,
            configuration = EpubNavigatorFragment.Configuration(
                decorationTemplates = decorationTemplates(),
                // The card is what a selection opens (#62): the system's own toolbar (Copy, Share, Select all) over it only gets in its way.
                selectionActionModeCallback = quietSelectionMenu,
                // Half the gap between two columns, in each column's padding: the rest of the outer margin is the
                // inset of pageHost (PageGeometry, #47). The CSS pixel is the dp.
                readiumCssRsProperties = RsProperties(pageGutter = Length.Px(PageGeometry.GUTTER_DP.toDouble()))
            ).also { EpubFontDeclarations.declare(it) },
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                    this@EpubReaderScreen.pageIndex = pageIndex
                    this@EpubReaderScreen.pageCount = totalPages
                    latestLocator = locator
                    updatePosition()
                    scheduleSave()
                    pageMoved()
                }
            }
        )
        val activity = context as? AppCompatActivity ?: error("EPUB reader requires a fragment host")
        val fragment = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name)
            as EpubNavigatorFragment
        activity.supportFragmentManager.beginTransaction()
            .add(pageHost.id, fragment, fragmentTag())
            .commitNowAllowingStateLoss()
        navigator = fragment
        fragment.addDecorationListener(ReaderMarks.GROUP, decorationTap)
        fragment.addDecorationListener(ReaderMarks.NOTES_GROUP, decorationTap)
        annotationJob?.cancel()
        annotationJob = uiScope.launch { annotations.live.collect { drawnAnnotations = it; drawAnnotations() } }
        syncAnnotations()
        fragment.addInputListener(object : org.readium.r2.navigator.input.InputListener {
            /** A drag began since the finger went down: Readium sends an End with every tap too (#21). */
            private var dragging = false

            override fun onDrag(event: org.readium.r2.navigator.input.DragEvent): Boolean {
                when (event.type) {
                    org.readium.r2.navigator.input.DragEvent.Type.Start, org.readium.r2.navigator.input.DragEvent.Type.Move -> dragging = true
                    org.readium.r2.navigator.input.DragEvent.Type.End -> {
                        // Only a real drag: a page dragged by hand, or a selection's handles. The End that
                        // comes with a tap took every tap for a page turned by hand, which paused the
                        // narration as the tap closed the menu (#21).
                        if (dragging && !matchNarrationToPage) inspectSelection(onNoSelection = { turnedByHand() })
                        dragging = false
                    }
                }
                return false
            }
            override fun onTap(event: org.readium.r2.navigator.input.TapEvent): Boolean {
                dragging = false
                val horizontal = event.point.x / navigatorContainer.width.coerceAtLeast(1)
                // The edges turn no page here; while the voice reads they leave it alone (#21).
                if (!EpubChromePolicy.handlesTap(horizontal, controlsVisible)) {
                    if (narration?.isOn != true) switchToReading()
                    return false
                }
                setControlsVisible(!controlsVisible)
                return true
            }
        })
        latestLocator = fragment.currentLocator.value
        refreshBookmarkButton()
        locatorJob = uiScope.launch {
            fragment.currentLocator.drop(1).collect { locator ->
                latestLocator = locator
                observePace()
                updatePosition()
                refreshBookmarkButton()
                scheduleSave()
                pageMoved()
            }
        }
        loading.visibility = View.GONE
        updatePosition()
        root.post { if (controlsVisible) controls.getOrNull(focusedControl)?.requestFocus() }
    }

    private fun removeNavigator() {
        val activity = if (::host.isInitialized) host.viewContext as? AppCompatActivity else null
        val fragment = navigator ?: activity?.supportFragmentManager?.findFragmentByTag(fragmentTag())
        if (fragment != null && activity != null) {
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        navigator = null
    }

    private fun turn(delta: Int) {
        bookScroll.reset()
        // While the voice reads, the page it lands on decides what the voice does (#49); otherwise the book is read on its own.
        movedByHand()
        val didMove = if (delta >= 0) navigator?.goForward(animated = true) else navigator?.goBackward(animated = true)
        if (didMove == false) host.notify(if (delta >= 0) "End of book" else "Start of book")
    }

    /**
     * A tap in the side inset (#47), which Readium never sees: the page turns the way the margin points, as with the
     * chrome open it is put away, and the edges leave a voice alone, as they do inside the page (#21).
     */
    private fun onInsetTap(side: PageGeometry.InsetTap) {
        if (controlsVisible) return setControlsVisible(false)
        if (narration?.isOn == true) return
        val rtl = navigator?.overflow?.value?.readingProgression == org.readium.r2.navigator.preferences.ReadingProgression.RTL
        val forward = (side == PageGeometry.InsetTap.FORWARD) != rtl
        turn(if (forward) 1 else -1)
    }

    /** The turns of a burst of quick swipes (#64). */
    private val turnLedger = TurnLedger()

    /** Where Readium's page was as a finger came down, to see whether the swipe it made turned it (#64). */
    private var flickFrom: PageTurns.Place? = null
    private var flickDownX = 0f
    private var flickDownY = 0f

    /** A swipe that began in the margin or a strip (#64), where Readium's page never saw it: it turns the page the way the finger went. */
    private fun onSwipeTurn(leftwards: Boolean) {
        // This swipe is turned: it is not one Readium dropped (the pager may not have moved yet when its touch ends).
        flickFrom = null
        val rtl = navigator?.overflow?.value?.readingProgression == org.readium.r2.navigator.preferences.ReadingProgression.RTL
        turn(if (PageSwipe.forward(leftwards, rtl)) 1 else -1)
    }

    private fun changeChapter(direction: Direction) {
        val book = publication ?: return
        val current = book.readingOrder.indexOfFirst { it.href.toString().substringBefore('#') == latestLocator?.href?.toString()?.substringBefore('#') }
        if (current < 0) return
        val next = current + if (direction == Direction.UP || direction == Direction.LEFT) -1 else 1
        val link = book.readingOrder.getOrNull(next) ?: return host.notify(if (next < 0) "First section" else "Last section")
        book.locatorFromLink(link)?.let { jumpTo(it) }
    }

    private fun scheduleSave() = saveCurrent(immediate = false)

    private fun saveCurrent(immediate: Boolean) {
        if (readAlong && !narrationCheckpoint.canSavePage(narration != null)) return
        val locator = latestLocator ?: return
        try {
            val document = locator.toJSON()
            document.optJSONObject("locations")?.remove("pocketdsAudio")
            var raw = json.parseToJsonElement(document.toString()).jsonObject
            val audioPosition = narrationCheckpoint.pointForSave(narration?.takeIf { it.isPlaying }?.position)
            audioPosition?.let { audio -> narration?.timeline?.let { timeline ->
                raw = ReadAlongLocation.save(raw, timeline, audio, narrationCompleted, ::spellDocument)
                // The page is not being followed with the screen off (#49): how far through the book is the narration's.
                if (backgrounded && !narrationCompleted) raw = progressWithoutPage(raw, timeline, audio)
            } }
            progress.save(checkpointKey, ReadingLocation(locator = raw))
            if (immediate) progress.requestSync(immediate = true)
            onProgressChanged()
        } catch (_: Exception) {
            if (!checkpointErrorShown) host.notify("Reading position could not be saved on this device")
            checkpointErrorShown = true
        }
    }

    /** A place saved while the page is not being followed: how far through the book the sentence is, from the narration. */
    private fun progressWithoutPage(locator: kotlinx.serialization.json.JsonObject, timeline: ReadAlongTimeline, audio: ReadAlongPosition): kotlinx.serialization.json.JsonObject {
        val href = (locator["href"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(DocumentPath::of) ?: return locator
        val section = bookSections.indexOfFirst { it.document == href }
        val (start, end) = PageInfo.sectionSpan(sectionStarts, section) ?: return locator
        val estimate = ReadAlongLocation.estimate(timeline, audio, start, end) ?: return locator
        return ReadAlongLocation.withProgress(locator, estimate)
    }

    private fun buildTopBar() {
        topBar = bars.topRow.apply {
            setPadding(dp(4), 0, dp(4), 0)
        }
        topBar.addView(control("×", "Close reader", click = { host.back() }))
        // The title, and under it the time left (E3), as the comic reader's issue and page.
        topBar.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            addView(TextView(host.viewContext).apply {
                text = title
                com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING, 15f)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            timeLeftView = TextView(host.viewContext).apply {
                textSize = 11.5f
                setTextColor(ReaderBars.SOFT_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
                contentDescription = "Time left"
                visibility = View.GONE
            }
            addView(timeLeftView)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        topBar.addView(control("☷", "Table of contents", click = {
            showNavigator()
        }))
        topBar.addView(control("search", "Search this book", { showSearch() }))
        // The one mode button (#62): the mode you are in, in the accent; it opens out to the others the book has.
        modeButton = ModeButtonView(host.viewContext, colors, ringVisible).apply {
            onPick = ::switchMode
            onPickCurrent = { if (readAlong && alignedEditions.size > 1) showNarrationChooser(ModeEntry()) }
            onCaption = ::showModeCaption
            onChanged = { host.refreshHints(); refreshKeys() }
            onFocus = { view -> focusedControl = controls.indexOf(view).coerceAtLeast(0) }
            configure(ReadingMode.available(ebook = true, audio = audioEditions.isNotEmpty(), aligned = alignedEditions.isNotEmpty() || readAlong), currentMode)
        }
        topBar.addView(modeButton, LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(4) })
        modeButton.currentSegment?.let { controls += it }
        bookmarkButton = control("☆", "Add bookmark", click = { toggleBookmark() })
        topBar.addView(bookmarkButton)
        topBar.addView(control("Aa", "Reading appearance", { showAppearance() }))
        topBar.addView(control("comfort", "Comfort", { showComfort() }))
        topBar.addView(control("pad", "Keys", { showKeys() }))
    }

    private fun buildBottomBar() {
        bottomBar = bars.bottomRow.apply {
            setPadding(dp(4), dp(4), dp(10), dp(4))
        }
        val navigationRow = LinearLayout(host.viewContext).apply { gravity = Gravity.CENTER_VERTICAL }
        bottomBar.addView(navigationRow, LinearLayout.LayoutParams(MATCH, dp(44)))
        navigationRow.addView(control("‹", "Previous page", { turn(-1) }))
        position = TextView(host.viewContext).apply {
            text = "Opening…"
            textSize = 12f
            setTextColor(ReaderBars.SOFT_TEXT)
            gravity = Gravity.CENTER
            contentDescription = "Reading position and navigation"
            Styler.makeFocusable(this); FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap { showPageNavigation() }
        }
        controls += position
        navigationRow.addView(position, LinearLayout.LayoutParams(0, MATCH, 1f))
        returnButton = control("return", "Return to previous place", {
            val target = returnLocator
            if (target != null && jumpTo(target, remember = false)) { returnLocator = null; updatePosition() }
        }).apply { visibility = View.GONE }
        navigationRow.addView(returnButton)
        navigationRow.addView(control("›", "Next page", { turn(1) }))
        bookSeek = SeekBar(host.viewContext).apply {
            max = 100; contentDescription = "Browse book percentage"; minimumHeight = dp(34)
            // The prototype's white line on a faint track.
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(64, 255, 255, 255))
            Styler.makeFocusable(this); FocusDecorator.attach(this, ringVisible, scale = false)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) position.text = "Browse · $value%"
                }
                override fun onStopTrackingTouch(bar: SeekBar) { seekBook() }
            })
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (!focused) updatePosition()
            }
        }
        controls += bookSeek
        bottomBar.addView(bookSeek, LinearLayout.LayoutParams(MATCH, dp(30)))
    }

    private fun buildNarrationDock() {
        val seek = com.pocketds.hub.settings.PlaybackSettings.seekSeconds(host.viewContext)
        narrationDock = ReadAlongDock(host.viewContext, colors, seek).apply {
            onBack = { narration?.jump(-seek * 1_000L) }
            // Video or an audiobook playing pauses as narration starts (AudioHandoff).
            onPlay = { if (matchNarrationToPage) seekNarrationToPage(play = true) else narration?.toggle() }
            onForward = { narration?.jump(seek * 1_000L) }
            onSpeed = { narration?.let { setNarrationSpeed(Listening.nextSpeed(it.speed)) } }
            onFollow = { follow() }
        }
        // The dock joins the bars as the lower bar once the narration is ready (prepareNarration). Nothing floats over
        // the page while it plays (#49): the corners show, and the menu brings the dock.
        narrationDock.focusableControls.forEach { view ->
            FocusDecorator.attach(view, ringVisible, scale = false)
            FocusDecorator.listen(view, ringVisible) { focusedView, focused ->
                if (focused) focusedControl = controls.indexOf(focusedView).coerceAtLeast(0)
            }
            controls += view
        }
    }

    private fun control(glyph: String, label: String, click: () -> Unit): TextView =
        TextView(host.viewContext).apply {
            val icon = when (glyph) { "×" -> AppIcon.CLOSE; "☷" -> AppIcon.CONTENTS; "search" -> AppIcon.SEARCH; "return" -> AppIcon.PREVIOUS_ITEM; "☆" -> AppIcon.BOOKMARK; "Aa" -> AppIcon.APPEARANCE; "‹" -> AppIcon.PREVIOUS; "▣" -> AppIcon.BOOK; "pad" -> AppIcon.PAD; "comfort" -> AppIcon.COMFORT; else -> AppIcon.NEXT }
            setTextColor(Color.WHITE)
            contentDescription = label
            // A 44dp disc with the 20dp icon in its middle (the layout params below say 44dp).
            com.pocketds.hub.ui.OverlayButtons.iconDisc(this, icon, colors.focusRing)
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) focusedControl = controls.indexOf(view).coerceAtLeast(0)
            }
            activateOnTap(click)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) }
            controls += this
        }

    /**
     * The narration (#19): streamed from the audiobook's tracks the hub mapped
     * the edition's audio onto ([manifest]), the slim edition's SMIL giving the
     * sentences; without a manifest, from the whole edition's own audio, taken
     * out of it here.
     */
    private suspend fun prepareNarration(file: File, saved: Locator?, manifest: ReadingAudioManifest?) {
        loading.visibility = View.VISIBLE
        loading.text = "Preparing synchronized narration…"
        val (timeline, sources) = withContext(Dispatchers.IO) {
            val context = coroutineContext
            if (manifest != null) {
                // A sentence past its file's audio is skipped, not the edition refused (an edition kept from before the hub mended its clips).
                val timeline = ReadAlongStream.fitted(ReadAlongPackage.read(file, requireAudio = false), manifest)
                    ?: error("This edition has no aligned narration")
                timeline to ReadAlongStream.sources(timeline, manifest, sourceItemId) {
                    readingSession.api.readingAudioTrackUrl(workId, sourceItemId, it, manifest.revision)
                }
            } else {
                val timeline = ReadAlongPackage.read(file)
                val folder = File(file.parentFile, file.nameWithoutExtension + "-audio")
                timeline to ReadAlongPackage.extractAudio(file, timeline, folder) { context.ensureActive() }
                    .map { NarrationSource(android.net.Uri.fromFile(it).toString()) }
            }
        }
        alignedFile = file
        narrationManifest = manifest
        // A switch of mode names the sentence to begin at (#62): the words selected, where the voice stopped, or where it is.
        val begun = if (entry.from != null) withContext(Dispatchers.IO) { entryPosition(timeline, file, manifest) } else null
        var place = saved
        awayCandidate?.let { candidate ->
            awayCandidate = null
            val there = saved?.let { ReadAlongLocation.resume(locatorJson(it), timeline) }
            val quote = there?.let { timeline.active(it.track, it.offsetMs) }?.let { sentence ->
                withContext(Dispatchers.IO) { AlignedBook(timeline, EpubMarkup(file)::read).anchorOf(sentence) }
            }?.quote?.highlight
            loading.visibility = View.GONE
            if (!AwayPrompt.ask(overlay, candidate.away, quote)) {
                // Stay here: the hub's place becomes this device's.
                place = candidate.ours?.locator?.let { parseSavedLocator(JSONObject(it.toString())) }
                candidate.ours?.let { progress.save(checkpointKey, it) }
                place?.let { goTo(it) }
            }
            loading.visibility = View.VISIBLE
        }
        val resume = begun ?: place?.let { ReadAlongLocation.resume(locatorJson(it), timeline) }
        matchNarrationToPage = begun == null && place != null && resume == null
        narration?.release()
        // Nothing known of the page belongs to the new voice (#49): it is looked at again below.
        pageSpan = null; pageKey = null; lastSent = null; turnedFrom = null; lastFollowed = null
        narratedFragments.clear()
        narration = ReadAlongPlayback(host.viewContext, timeline, sources, resume,
            onSegment = ::highlightNarration,
            onState = { playing ->
                if (playing) narrationCompleted = false
                updateDock()
                updateAwake()
            },
            onSave = { point, completed -> narrationCheckpoint.record(point); narrationCompleted = completed; saveCurrent(immediate = true) },
            onError = { host.notify("Narration playback failed. Your position is saved; reading is still available.") },
            title = title
        )
        narration?.speed = com.pocketds.hub.settings.ListeningSettings.speed(host.viewContext, workId)
        narrationCheckpoint.ready(resume)
        // The player is the menu's lower bar (#21): it shows and hides with the menu.
        bars.useAsLowerBar(narrationDock, ReadAlongDock.HEIGHT_DP)
        pagePreview.refresh()
        startDockUpdates()
        scheduleProbe()
        DebugLog.log("reader", "aligned narration ready: ${timeline.tracks.size} tracks, resumed=${resume != null}")
        loading.visibility = View.GONE
        setControlsVisible(true)
        if (entry.from != null) beginFromSwitch(begun)
    }

    /** The sentence of the narration a switch of mode begins at: a place in the audiobook, else the words, found in this edition by their text. */
    private fun entryPosition(timeline: ReadAlongTimeline, file: File, manifest: ReadingAudioManifest?): ReadAlongPosition? {
        val place = entry.audioPlace
        if (place != null && manifest != null) {
            val track = manifest.tracks.indexOfFirst { it.id == place.trackId }
            AlignedPlaces.segmentAt(manifest, timeline, track, place.offsetMs)?.let { return AlignedPlaces.position(timeline, it) }
        }
        val anchor = entry.anchor ?: return null
        return AlignedBook(timeline, EpubMarkup(file)::read).sentenceOf(anchor.document, anchor.quote)?.let { AlignedPlaces.position(timeline, it) }
    }

    /** Read along, opened by a switch: the page goes to the sentence and the voice begins there, or at the top of the page when no sentence was named (#62). */
    private fun beginFromSwitch(begun: ReadAlongPosition?) {
        val audio = narration ?: return
        val start = if (begun != null) entry.start else ModePlace.Start.TOP_OF_PAGE
        host.notify(ModePlace.note(start, ReadingMode.ALONG))
        if (begun != null) {
            val sentence = audio.timeline.active(begun.track, begun.offsetMs)
            highlightNarration(sentence)
            if (sentence != null) { lastSent = sentence; sendPageTo(sentence) }
            if (entry.playing && !audio.isPlaying) audio.toggle()
        } else if (entry.playing) seekNarrationToPage(play = true)
    }

    /** The sentence being read, as it was last handed to [highlightNarration]: a new look for the page draws it again. */
    private var highlightedSegment: ReadAlongSegment? = null

    /**
     * The wash on the sentence being read, behind its words (#52). Where the page goes is [followVoice]'s. The tint
     * is the accent let into this page's colour ([ReadAlongGlow.wash]), so it is drawn again when the theme changes.
     */
    private fun highlightNarration(segment: ReadAlongSegment?) {
        highlightJob?.cancel()
        highlightedSegment = segment
        if (backgrounded) return
        highlightJob = uiScope.launch {
            val reader = navigator ?: return@launch
            if (segment == null) { reader.applyDecorations(emptyList(), ReadAlongGlow.GROUP); return@launch }
            val locator = segmentLocator(segment) ?: return@launch
            val (page, ink) = pageColors()
            val tint = ReadAlongGlow.wash(colors.accent, page, ink)
            reader.applyDecorations(listOf(Decoration("narration", locator, Decoration.Style.Highlight(tint, isActive = true))), ReadAlongGlow.GROUP)
            // Each box made its line's line box, once the boxes are on the page (and again when it reflows): no gaps, nothing over the lines round it.
            runCatching { reader.evaluateJavascript(ReadAlongGlow.fitScript(segment.fragment)) }
        }
    }

    /**
     * The sentence as a locator Readium resolves: its document in the book's own spelling ([spellDocument]), not the timeline's
     * decoded one, which is no valid href once the name has a space in it, so no highlight or jump landed (#61).
     */
    private fun segmentLocator(segment: ReadAlongSegment): Locator? =
        Locator.fromJSON(JSONObject().put("href", spellDocument(segment.textHref)).put("type", "application/xhtml+xml")
            .put("locations", JSONObject().put("fragments", org.json.JSONArray().put(segment.fragment))))

    /** A document in [DocumentPath]'s spelling as Readium spells it: the book's own, else the encoding of its decoded path. */
    private fun spellDocument(document: String): String = documentSpelling[document] ?: DocumentPath.encode(document)

    /** The document a locator is in, in the one spelling the narration's timeline holds ([DocumentPath.of], #61). */
    private val Locator.document: String get() = DocumentPath.of(href.toString())

    private fun startDockUpdates() {
        dockJob?.cancel()
        dockJob = uiScope.launch {
            while (true) {
                updateDock()
                delay(500)
            }
        }
        followJob?.cancel()
        followJob = uiScope.launch {
            while (true) {
                delay(FOLLOW_MS)
                followVoice()
            }
        }
    }

    private fun updateDock() {
        if (!::narrationDock.isInitialized) return
        val audio = narration ?: return
        narrationDock.update(audio.isOn, audio.position, audio.timeline, audio.speed, if (audio.isOn) followLabel(audio) else "")
        updateTimeLeft()
    }

    /** "Following" while it plays, or "Alignment unavailable" on a page the narration never reaches. */
    private fun followLabel(audio: ReadAlongPlayback): String =
        ReadAlongFollow.label(latestLocator?.document?.let(audio.timeline::narrates) ?: true)

    /** L3, the dock's follow and "Return to narration": the page back to the sentence being read. */
    private fun follow() {
        val audio = narration ?: return
        val segment = audio.timeline.active(audio.position.track, audio.position.offsetMs)
        highlightNarration(segment)
        // The page and the voice are together again, for Play and for what is saved.
        matchNarrationToPage = false
        narrationCheckpoint.record(audio.position)
        val span = pageSpan
        if (segment != null && (span == null || span.href != segment.textHref || segment.fragment !in span.visible)) {
            lastSent = segment
            sendPageTo(segment)
        }
        updateDock()
    }

    // ---------------------------------------------------------------- page and voice (#49)

    /** The page changed, by whoever: nothing acts on the old one, and the new one is looked at once it has settled. */
    private fun pageMoved() {
        if (narration == null) return
        pageSpan = null
        scheduleProbe()
    }

    private fun ownMove() { ownMoveUntil = SystemClock.uptimeMillis() + OWN_MOVE_MS }

    private fun scheduleProbe(afterMs: Long = PROBE_MS) {
        if (narration == null || backgrounded) return
        probeJob?.cancel()
        probeJob = uiScope.launch {
            delay(afterMs)
            val audio = narration ?: return@launch
            val reader = navigator ?: return@launch
            val href = reader.currentLocator.value.document
            val probe = askPage(reader, audio, href)
            // Moved on while it was asked: the next change asks again.
            if (reader.currentLocator.value.document != href) return@launch
            if (probe == null) {
                if (++probeFailures <= PROBE_RETRIES) scheduleProbe(PROBE_RETRY_MS)
                return@launch
            }
            probeFailures = 0
            pageProbed(audio, probe)
        }
    }

    /** What the page in front shows of the narration; a part of the book with none is a page with no narrated text. */
    private suspend fun askPage(reader: EpubNavigatorFragment, audio: ReadAlongPlayback, href: String): PageProbe? {
        val ids = narratedFragments.getOrPut(href) { audio.timeline.fragments(href) }
        if (ids.isEmpty()) return PageProbe(href, null, null, emptyList())
        val raw = runCatching { reader.evaluateJavascript(ReadAlongPageProbe.script(ids)) }.getOrNull()
        // A look cancelled by the next page change is no failure to try again: runCatching took the cancellation too.
        coroutineContext.ensureActive()
        return ReadAlongPageProbe.parse(href, raw)
    }

    private fun pageProbed(audio: ReadAlongPlayback, probe: PageProbe) {
        var span = ReadAlongPageSync.span(audio.timeline, probe)
        val previous = pageKey
        pageKey = probe.key
        lastSent = null
        if (probe.key != previous) {
            val ours = SystemClock.uptimeMillis() < ownMoveUntil
            ownMoveUntil = 0L
            // A page turned by hand while the voice reads: the first look at a page (previous null) is not one.
            if (!ours && previous != null && audio.isOn && !matchNarrationToPage) span = pageTurnedByHand(audio, span)
        }
        pageSpan = span
        updateDock()
    }

    /** The new page decides (#49): the sentence being read is still on it and nothing restarts, or the voice goes to its first word. */
    private fun pageTurnedByHand(audio: ReadAlongPlayback, span: PageSpan): PageSpan =
        when (val decision = ReadAlongPageSync.afterManualTurn(audio.timeline, audio.position, span)) {
            ReadAlongPageSync.Manual.Keep -> ReadAlongPageSync.keptFor(span, audio.position)
            is ReadAlongPageSync.Manual.Jump -> { jumpVoice(audio, decision.to); span }
            ReadAlongPageSync.Manual.Nothing -> span
        }

    private fun jumpVoice(audio: ReadAlongPlayback, target: ReadAlongPosition) {
        matchNarrationToPage = false
        narrationCompleted = false
        audio.seek(target)
        narrationCheckpoint.record(target)
        highlightNarration(audio.timeline.active(target.track, target.offsetMs))
        saveCurrent(immediate = true)
    }

    /** The page follows the voice: every [FOLLOW_MS], and only when the voice has moved (paused, it is left where it is). */
    private fun followVoice() {
        val audio = narration ?: return
        val position = audio.position
        val moved = position != lastFollowed
        lastFollowed = position
        if (backgrounded || !moved) return
        val span = pageSpan ?: return
        if (SystemClock.uptimeMillis() < ownMoveUntil) return
        when (val step = ReadAlongPageSync.follow(audio.timeline, position, span)) {
            ReadAlongPageSync.Step.Stay -> Unit
            ReadAlongPageSync.Step.TurnPage -> if (turnedFrom != span) {
                turnedFrom = span
                ownMove()
                // At the end of the book there is no page to turn to, and no page change to wait for.
                if (navigator?.goForward(animated = false) != true) ownMoveUntil = 0L
            }
            is ReadAlongPageSync.Step.GoTo -> if (lastSent != step.segment) {
                lastSent = step.segment
                sendPageTo(step.segment)
            }
        }
    }

    private fun sendPageTo(segment: ReadAlongSegment) {
        val locator = segmentLocator(segment) ?: return
        ownMove()
        if (!goTo(locator)) ownMoveUntil = 0L
    }

    /** The app is back (#49): the voice went on without the page, which catches up to it. */
    private fun catchUpWithVoice() {
        val audio = narration ?: return
        pageSpan = null
        val moved = audio.position != voiceAtBackground
        voiceAtBackground = null
        val segment = audio.timeline.active(audio.position.track, audio.position.offsetMs)
        highlightNarration(segment)
        if (moved && segment != null) { lastSent = segment; sendPageTo(segment) }
        scheduleProbe()
    }

    /**
     * The page moved by hand (a turn, a drag, a link, the contents, a search, the slider, a bookmark). Paused, the book
     * is read on its own and Play starts from the page; while the voice reads, nothing is paused: the page it lands on
     * decides, once it shows (#49).
     */
    private fun movedByHand() {
        stopCursor()
        cancelPlace()
        turnLedger.end()
        if (narration?.isOn == true) closeDictionary(resumeNarration = false) else switchToReading()
    }

    /** A speed for this book's narration, kept for the book whichever way it is opened next (A5). */
    private fun setNarrationSpeed(speed: Float) {
        val audio = narration ?: return
        audio.speed = speed
        com.pocketds.hub.settings.ListeningSettings.setSpeed(host.viewContext, workId, audio.speed)
        updateDock()
    }

    /**
     * What is selected on the page (#62), once it has settled: one word opens the dictionary card at once, a phrase the dictionary
     * has as one entry opens it too, any other phrase gets the bar with Look up. A tap that selected nothing is [onNoSelection]'s.
     */
    private fun inspectSelection(onNoSelection: (() -> Unit)? = null) {
        selectionJob?.cancel()
        selectionJob = uiScope.launch {
            delay(160)
            val reader = navigator ?: return@launch
            val found = runCatching { reader.currentSelection() }.getOrNull()
            val text = found?.locator?.text?.highlight?.trim().orEmpty()
            if (found == null || text.isBlank()) {
                if (!dictionaryCard.isOpen) onNoSelection?.invoke()
                return@launch
            }
            if (dictionaryCard.isOpen) return@launch
            openSelection(reader, found)
        }
    }

    private suspend fun openSelection(reader: EpubNavigatorFragment, found: org.readium.r2.navigator.Selection) {
        val locator = found.locator
        val quote = AnnotationQuotes.of(locator.text.before, locator.text.highlight.orEmpty(), locator.text.after)
        if (quote.highlight.isBlank()) return
        val audio = narration
        if (selectionGate.begin(audio?.isPlaying == true)) audio?.pause(settle = false)
        val chosen = ReaderSelection(quote.highlight, quote, locator.document, JSONObject(locator.toJSON().toString()), pageRectToRoot(found.rect))
        selection = chosen
        shownHighlight = null
        phraseAnswer = null
        dictionaryCard.setInsets(cardTopInset(), cardBottomInset())
        val words = PhraseLookup.words(chosen.text)
        val last = HighlightSettings.color(host.viewContext)
        if (words.size == 1) {
            dictionaryCard.showLoading(words[0], chosen.rect, false, last)
            lookUp(chosen, force = true)
        } else {
            val worth = words.size in 2..PhraseLookup.MAX_PHRASE_WORDS
            dictionaryCard.showBar(chosen.text, chosen.rect, last, canLookUp = worth)
            if (worth) lookUp(chosen, force = false)
        }
        host.refreshHints()
    }

    /**
     * The dictionary's answer ([PhraseLookup]). [force]: a word, or Look up pressed, shows the card whatever the answer; otherwise
     * only a phrase the dictionary has as one entry does, and any other leaves the bar as it is, with its Look up.
     */
    private fun lookUp(chosen: ReaderSelection, force: Boolean) {
        val generation = ++selectionGeneration
        dictionaryJob?.cancel()
        dictionaryJob = uiScope.launch {
            val answer = runCatching { PhraseLookup.answer(chosen.text) { requested, terms -> dictionary.lookupTerms(requested, terms) } }.getOrNull()
            if (generation != selectionGeneration || !dictionaryCard.isOpen) return@launch
            if (answer == null) {
                if (force) dictionaryCard.showFailure("Offline dictionary could not be opened")
                return@launch
            }
            phraseAnswer = answer
            if (force || answer.opensCard) dictionaryCard.show(answer, chosen.text)
            host.refreshHints()
        }
    }

    /** Look up on the bar: the answer already found, else asked for now. The card says what it found, or that it found nothing. */
    private fun lookUpSelection() {
        val chosen = selection ?: return
        val answer = phraseAnswer
        if (answer != null) dictionaryCard.show(answer, chosen.text) else lookUp(chosen, force = true)
        host.refreshHints()
    }

    /** The selection's rectangle, which Readium gives in its own view, in the screen's own. */
    private fun pageRectToRoot(rect: RectF?): RectF {
        // Readium gives no rectangle for a selection it could not measure: the middle of the page, a line tall.
        val source = rect ?: RectF(root.width / 2f - 1f, root.height / 3f, root.width / 2f + 1f, root.height / 3f + dp(20))
        val page = navigator?.view ?: return RectF(source)
        val at = IntArray(2); val base = IntArray(2)
        page.getLocationInWindow(at); root.getLocationInWindow(base)
        return RectF(source).apply { offset((at[0] - base[0]).toFloat(), (at[1] - base[1]).toFloat()) }
    }

    /** The room the card keeps clear of the bars when the menu is up. */
    private fun cardTopInset(): Int = if (controlsVisible) bars.top.height else 0
    private fun cardBottomInset(): Int = if (controlsVisible) bars.bottom.height else 0

    /** A colour chosen for what is selected: the highlight is kept (made, or recoloured if the passage already has one), and remembered as the next. */
    private fun highlightSelection(color: HighlightColor, thenNote: Boolean) {
        val chosen = selection ?: return
        HighlightSettings.setColor(host.viewContext, color)
        val existing = annotations.live.value.firstOrNull { it.document == chosen.document && it.quote.highlight == chosen.quote.highlight && it.quote.before == chosen.quote.before }
        val base = existing ?: ReadingAnnotation(id = AnnotationIds.new(), document = chosen.document, quote = chosen.quote, locator = locatorHint(chosen))
        val kept = annotations.save(base.copy(color = color.id))
        if (kept == null) { host.notify("Highlight could not be saved on this device"); return }
        closeDictionary(resumeNarration = !thenNote)
        if (thenNote) editNote(kept) else host.notify("Highlighted")
    }

    // ---------------------------------------------------------------- the controller's text cursor (#62)

    /** The words of the page in front, from the page itself; null when the page has none. */
    private suspend fun readWords(): List<PageWord>? {
        val reader = navigator ?: return null
        val raw = runCatching { reader.evaluateJavascript(ReaderWordsScript.PAGE) }.getOrNull()
        coroutineContext.ensureActive()
        return ReaderWordsScript.parse(raw)?.takeIf { page -> page.any { it.visible } }
    }

    /** Ⓧ: the cursor comes to the first word on the page, or goes away again. */
    private fun startCursor() {
        if (cursorWords != null) return stopCursor()
        if (navigator == null || dictionaryCard.isOpen) return
        setControlsVisible(false)
        cursorJob?.cancel()
        cursorJob = uiScope.launch {
            val page = readWords() ?: return@launch host.notify("There is no text on this page to select")
            cursorWords = TextCursor(page)
            drawCursor()
            host.refreshHints()
        }
    }

    /** The cursor and the selection it has started, drawn on the page. */
    private fun drawCursor() {
        val cursor = cursorWords ?: return
        val reader = navigator ?: return
        val (at, anchor) = cursor.carry()
        uiScope.launch { runCatching { reader.evaluateJavascript(ReaderWordsScript.draw(at, anchor ?: -1, colors.accent)) } }
    }

    private fun stopCursor() {
        cursorJob?.cancel()
        if (cursorWords == null) return
        cursorWords = null
        if (::host.isInitialized) host.refreshHints()
        val reader = navigator ?: return
        uiScope.launch { runCatching { reader.evaluateJavascript(ReaderWordsScript.CLEAR) } }
    }

    private fun moveCursor(direction: Direction) {
        val cursor = cursorWords ?: return
        when (val step = when (direction) {
            Direction.LEFT -> cursor.previous()
            Direction.RIGHT -> cursor.next()
            Direction.UP -> cursor.line(down = false)
            Direction.DOWN -> cursor.line(down = true)
        }) {
            TextCursor.Step.Moved -> drawCursor()
            TextCursor.Step.Stuck -> Unit
            is TextCursor.Step.TurnPage -> turnCursorPage(step)
        }
    }

    /** The cursor reached the edge of the page: the next page is turned to, and the cursor goes on from its first word (the selection started goes with it). */
    private fun turnCursorPage(step: TextCursor.Step.TurnPage) {
        val reader = navigator ?: return
        val anchor = cursorWords?.carry()?.second
        val document = latestLocator?.document
        cursorJob?.cancel()
        cursorJob = uiScope.launch {
            val moved = if (step.forward) reader.goForward(animated = false) else reader.goBackward(animated = false)
            if (!moved) return@launch host.notify(if (step.forward) "End of book" else "Start of book")
            val page = awaitWords { words -> words.any { it.visible } && (document != latestLocator?.document || words.any { it.index == step.cursor && it.visible }) }
                ?: return@launch stopCursor()
            val sameFile = document == latestLocator?.document
            cursorWords = if (sameFile) TextCursor.restoring(page, step.cursor, anchor)
                else TextCursor(page, startIndex = if (step.forward) null else page.lastOrNull { it.visible }?.index)
            drawCursor()
            host.refreshHints()
        }
    }

    /** L2 and R2 with the cursor on: a whole page on or back, the cursor on its first word. */
    private fun pageCursor(delta: Int) {
        val reader = navigator ?: return
        cursorJob?.cancel()
        cursorJob = uiScope.launch {
            val moved = if (delta >= 0) reader.goForward(animated = false) else reader.goBackward(animated = false)
            if (!moved) return@launch host.notify(if (delta >= 0) "End of book" else "Start of book")
            val page = awaitWords { it.any { word -> word.visible } } ?: return@launch stopCursor()
            cursorWords = TextCursor(page)
            drawCursor()
            host.refreshHints()
        }
    }

    /** The page's words once the page has settled and [ready] likes them: a turn of the page takes a moment to be drawn. */
    private suspend fun awaitWords(ready: (List<PageWord>) -> Boolean): List<PageWord>? {
        delay(CURSOR_SETTLE_MS)
        repeat(CURSOR_TRIES) {
            readWords()?.takeIf(ready)?.let { return it }
            delay(CURSOR_SETTLE_MS)
        }
        return null
    }

    /** Ⓐ: the first press starts a selection at the cursor, the second finishes it and hands the words to the card, as a finger's selection is. */
    private fun anchorCursor(finish: Boolean) {
        val cursor = cursorWords ?: return
        if (!finish) {
            cursor.start()
            drawCursor()
            host.refreshHints()
            return
        }
        val range = cursor.finish() ?: return
        val reader = navigator ?: return
        cursorWords = null
        cursorJob?.cancel()
        cursorJob = uiScope.launch {
            runCatching { reader.evaluateJavascript(ReaderWordsScript.select(range.first, range.last)) }
            host.refreshHints()
            inspectSelection()
        }
    }

    private fun growCursor(paragraph: Boolean) {
        val cursor = cursorWords ?: return
        if ((if (paragraph) cursor.paragraph() else cursor.sentence()) == TextCursor.Step.Moved) {
            drawCursor()
            host.refreshHints()
        }
    }

    /** Ⓑ with a selection started: it is dropped and the cursor stays where it is. */
    private fun cancelSelection() {
        val cursor = cursorWords ?: return
        cursor.cancelSelection()
        drawCursor()
        host.refreshHints()
    }

    /** Readium's locator for the selection, as a hint for the next time this app opens the same edition. */
    private fun locatorHint(chosen: ReaderSelection) = runCatching { json.parseToJsonElement(chosen.locator.toString()).jsonObject }.getOrNull()

    private fun recolour(annotation: ReadingAnnotation, color: HighlightColor) {
        HighlightSettings.setColor(host.viewContext, color)
        if (annotations.save(annotation.copy(color = color.id)) == null) host.notify("Highlight could not be saved on this device")
        closeDictionary(resumeNarration = true)
    }

    private fun removeHighlight(annotation: ReadingAnnotation) {
        closeDictionary(resumeNarration = true)
        if (annotations.remove(annotation.id) == null) host.notify("Highlight could not be removed on this device") else host.notify("Highlight removed")
    }

    private fun copySelection() {
        val chosen = selection ?: return
        val clipboard = host.viewContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Selected text", chosen.text))
        closeDictionary(resumeNarration = true)
        host.notify("Copied")
    }

    private fun say(text: String) {
        val speaker = voice ?: voiceFactory(host.viewContext) { problem -> root.post { host.notify(problem) } }.also { voice = it }
        speaker.say(text)
    }

    /** The note's sheet (#62): the passage, a field, Save, and Remove note where there is one. */
    private fun editNote(annotation: ReadingAnnotation) {
        closeDictionary(resumeNarration = false)
        overlay.resetBody()
        overlay.open("Note", "\u201C${annotation.quote.highlight.take(160)}\u201D", onDismiss = { host.refreshHints() })
        val field = EditText(host.viewContext).apply {
            hint = "Write a note"; contentDescription = "Note"
            setTextColor(colors.primaryText); setHintTextColor(colors.mutedText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START; minimumHeight = dp(96)
            filters = arrayOf(android.text.InputFilter.LengthFilter(NOTE_CHARS))
            setText(annotation.note); setSelection(text.length)
        }
        overlay.body.addView(field)
        fun keep(note: String) {
            (host.viewContext.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
            val text = AnnotationLimits.clip(note.trim(), AnnotationLimits.NOTE_BYTES)
            if (annotations.save(annotation.copy(note = text)) == null) host.notify("Note could not be saved on this device")
            else host.notify(if (text.isNotEmpty()) "Note saved" else "Note removed")
            overlay.dismiss()
        }
        overlay.choice("Save") { keep(field.text.toString()) }
        if (annotation.hasNote) overlay.choice("Remove note", danger = true) { keep("") }
        overlay.choice("Cancel") { overlay.dismiss() }
        overlay.focusBody(field); host.refreshHints()
    }

    /** A selection's own menu, emptied: the handles stay to drag, and the card is the menu (#62). */
    private val quietSelectionMenu = object : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean { menu.clear(); return true }
        override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean { menu.clear(); return true }
        override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean = false
        override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
    }

    /** A tap on a highlight (or on its note mark): the menu to recolour it, write or change its note, or remove it. */
    private val decorationTap = object : DecorableNavigator.Listener {
        override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
            if (dictionaryCard.isOpen) return false
            val found = annotations[event.decoration.id.removePrefix("note:")]?.takeUnless { it.deleted } ?: return false
            val rect = pageRectToRoot(event.rect)
            root.post { openHighlightMenu(found, rect) }
            return true
        }
    }

    private fun openHighlightMenu(annotation: ReadingAnnotation, rect: RectF) {
        if (dictionaryCard.isOpen) return
        selection = null
        phraseAnswer = null
        shownHighlight = annotation
        dictionaryCard.setInsets(cardTopInset(), cardBottomInset())
        dictionaryCard.showHighlight(annotation, rect)
        host.refreshHints()
    }

    /** The passage as a locator Readium finds by its text, in the book's own spelling of the file (#62). */
    private fun annotationLocator(annotation: ReadingAnnotation, quote: AnnotationQuote = annotation.quote): Locator? =
        Locator.fromJSON(JSONObject().put("href", spellDocument(annotation.document)).put("type", "application/xhtml+xml")
            .put("text", JSONObject().put("before", quote.before).put("highlight", quote.highlight).put("after", quote.after)))

    /** The last word of a highlight, as a locator: where its note mark sits. */
    private fun endOfAnnotation(annotation: ReadingAnnotation): Locator? {
        val words = annotation.quote.highlight.trimEnd()
        val cut = words.lastIndexOf(' ') + 1
        val tail = words.substring(cut)
        val lead = AnnotationQuotes.tail(annotation.quote.before + " " + words.substring(0, cut), AnnotationQuotes.CONTEXT)
        return annotationLocator(annotation, AnnotationQuote(lead, tail, annotation.quote.after))
    }

    /** Draws the person's highlights and the marks of their notes (#62); Readium finds each by its text and draws it when its file is on screen. */
    private fun drawAnnotations() {
        val reader = navigator ?: return
        val (page, ink) = pageColors()
        val highlights = ArrayList<Decoration>()
        val marks = ArrayList<Decoration>()
        drawnAnnotations.forEach { annotation ->
            val locator = annotationLocator(annotation) ?: return@forEach
            highlights += Decoration(annotation.id, locator, Decoration.Style.Underline(ReaderMarks.tint(annotation.highlightColor, page, ink)),
                mapOf(ReaderMarks.KIND to ReaderMarks.HIGHLIGHT))
            if (annotation.hasNote) endOfAnnotation(annotation)?.let { end ->
                marks += Decoration("note:" + annotation.id, end, Decoration.Style.Underline(ReaderMarks.base(annotation.highlightColor)),
                    mapOf(ReaderMarks.KIND to ReaderMarks.NOTE))
            }
        }
        uiScope.launch {
            reader.applyDecorations(highlights, ReaderMarks.GROUP)
            reader.applyDecorations(marks, ReaderMarks.NOTES_GROUP)
        }
    }

    /** One pass with the hub for this book's highlights: what waits to be sent, then what the other devices did. */
    private fun syncAnnotations() {
        uiScope.launch {
            val result = withContext(Dispatchers.IO) { annotations.syncNow(readingSession.api.annotationRemote()) }
            if (result.retry) progress.requestSync()
        }
    }

    private fun closeDictionary(resumeNarration: Boolean) {
        if (!::dictionaryCard.isInitialized) return
        if (!dictionaryCard.isOpen) { selectionGate.cancel(); return }
        selectionGeneration++
        dictionaryJob?.cancel()
        voice?.stop()
        dictionaryCard.dismiss()
        navigator?.clearSelection()
        selection = null
        shownHighlight = null
        phraseAnswer = null
        val resume = if (resumeNarration) selectionGate.dismiss() else { selectionGate.cancel(); false }
        if (resume && narration?.isPlaying == false) narration?.toggle()
        host.refreshHints()
    }

    private fun showNarrationOptions() {
        val audio = narration ?: return host.notify("Narration is still preparing")
        overlay.show("Narration", "Recorded audiobook · synchronized text", listOf(
            ChoiceOverlay.Choice("here", "Listen from this page", "Moves the narration to the first visible aligned sentence"),
            ChoiceOverlay.Choice("follow", "Return to narration", "Show the sentence currently being read")
        ) + Listening.SPEEDS.map { ChoiceOverlay.Choice("speed:$it", "${com.pocketds.hub.playback.PlayerLabels.rate(it)} speed", selected = kotlin.math.abs(audio.speed - it) < 0.01f) }) { id ->
            when {
                id.startsWith("speed:") -> setNarrationSpeed(id.removePrefix("speed:").toFloat())
                id == "follow" -> follow()
                id == "here" -> seekNarrationToPage(play = false)
            }
        }
    }

    /**
     * Play from the page: the voice goes to the first word on it, which may be inside a sentence the page cuts (#49). A page
     * with no narrated text on it (the Prologue, a title page, an image) is not refused (#61): the voice goes to the nearest
     * narrated sentence, the first after the page in reading order (else the last before it), the page goes there too, and a
     * short note says so.
     */
    private fun seekNarrationToPage(play: Boolean) {
        uiScope.launch {
            val audio = narration ?: return@launch
            val reader = navigator ?: return@launch
            val here = reader.currentLocator.value
            val target = askPage(reader, audio, here.document)?.let { ReadAlongPageSync.startOf(audio.timeline, it) }
            if (target != null) {
                jumpVoice(audio, target)
                if (play && !audio.isPlaying) audio.toggle()
                return@launch
            }
            val nearest = ReadAlongPageSync.nearest(audio.timeline, documentOrder, here.document, here.locations.progression ?: 0.0)
            if (nearest == null) {
                host.notify("This book has no aligned narration to start from.")
                return@launch
            }
            host.notify("This page has no narration. Starting from the nearest narrated sentence.")
            jumpVoice(audio, nearest)
            // The voice is not on this page: the page goes to it.
            audio.timeline.active(nearest.track, nearest.offsetMs)?.let { sentence -> lastSent = sentence; sendPageTo(sentence) }
            if (play && !audio.isPlaying) audio.toggle()
        }
    }

    private fun switchToReading() {
        closeDictionary(resumeNarration = false)
        if (narration == null) return
        narration?.pause(settle = false)
        narrationCheckpoint.switchToText()
        narrationCompleted = false
        matchNarrationToPage = true
        highlightNarration(null)
    }

    // ---------------------------------------------------------------- the mode button (#62)

    /** Ⓨ: the button opens out, over the menu (the page makes room for it); the words just selected are kept for the voice to start at. */
    private fun openModes() {
        if (!::modeButton.isInitialized || !modeButton.worthShowing) return
        capturedSelection = selection
        if (dictionaryCard.isOpen) closeDictionary(resumeNarration = false)
        stopCursor()
        setControlsVisible(true)
        modeButton.post { modeButton.open() }
    }

    /** The name of the mode in focus, under the button; nothing once it closes. */
    private fun showModeCaption(name: String) {
        if (name.isEmpty()) { modeCaption.visibility = View.GONE; return }
        modeCaption.text = name
        modeCaption.visibility = View.VISIBLE
        modeCaption.post {
            val at = IntArray(2); val base = IntArray(2)
            modeButton.getLocationInWindow(at); root.getLocationInWindow(base)
            modeCaption.x = (at[0] - base[0] + modeButton.width - modeCaption.width).toFloat().coerceAtLeast(dp(8).toFloat())
            modeCaption.y = (at[1] - base[1] + modeButton.height + dp(6)).toFloat()
        }
    }

    /**
     * A switch of mode (#62), the place by one rule ([ModePlace]): from the page, the sentence of the words just selected, else where the
     * voice stopped when it was heard on this page, else the top of it; from Read along, the page opens where the voice is; between
     * Read along and Audio the voice keeps going. The place is kept and sent to the hub first, so the next screen finds it there.
     */
    private fun switchMode(to: ReadingMode) {
        val from = currentMode
        if (to == from) return
        modeJob?.cancel()
        val chosen = capturedSelection
        capturedSelection = null
        val audio = narration
        val voicePlaying = audio?.isPlaying == true
        val voiceSegment = audio?.let { it.timeline.active(it.position.track, it.position.offsetMs) ?: it.timeline.tracks.getOrNull(it.position.track)?.segments?.firstOrNull() }
        val timeline = audio?.timeline
        val file = alignedFile
        val manifest = narrationManifest
        modeJob = uiScope.launch {
            val heard = if (from == ReadingMode.EBOOK) heardAnchor?.takeIf { heardOnPage() } else null
            val made = withContext(Dispatchers.IO) {
                when (from) {
                    ReadingMode.EBOOK -> when (ModePlace.startingFromPage(chosen != null, heard != null)) {
                        ModePlace.Start.SELECTED_SENTENCE -> ModeEntry(from, ModePlace.Start.SELECTED_SENTENCE, chosen?.let { SentenceAnchor(it.document, it.quote) }, playing = true)
                        ModePlace.Start.WHERE_VOICE_STOPPED -> ModeEntry(from, ModePlace.Start.WHERE_VOICE_STOPPED, heard, playing = true)
                        ModePlace.Start.TOP_OF_PAGE -> ModeEntry(from, ModePlace.Start.TOP_OF_PAGE, playing = true)
                    }
                    else -> {
                        // Read along: where the voice is, as a place in the audiobook, or as words the ebook can find.
                        if (to == ReadingMode.AUDIO) ModeEntry(from, ModePlace.Start.WHERE_VOICE_STOPPED,
                            audioPlace = if (voiceSegment != null && manifest != null) AlignedPlaces.audioPlace(manifest, voiceSegment) else null, playing = voicePlaying)
                        else ModeEntry(from, ModePlace.Start.WHERE_VOICE_STOPPED,
                            anchor = if (voiceSegment != null && timeline != null && file != null) AlignedBook(timeline, EpubMarkup(file)::read).anchorOf(voiceSegment) else null)
                    }
                }
            }
            saveCurrent(immediate = true)
            // The place is on the hub before the next screen asks it where the book is.
            withTimeoutOrNull(FLUSH_MS) { withContext(Dispatchers.IO) { progress.flush() } }
            when (to) {
                ReadingMode.EBOOK -> replaceReaderMode(false, ebookSourceItemId, made)
                ReadingMode.ALONG -> if (alignedEditions.size > 1) showNarrationChooser(made)
                    else replaceReaderMode(true, alignedEditions.firstOrNull()?.sourceItemId ?: sourceItemId, made)
                ReadingMode.AUDIO -> showListeningEditions(made)
            }
        }
    }

    /**
     * The ebook, opened from Audio or Read along (#62): the page is where the voice was, found by the words of its sentence (the
     * edition's own ids are not this one's), and "Heard to here" marks the sentence. A book whose file names differ is searched for it.
     */
    private fun openAtVoice(anchor: SentenceAnchor) {
        heardAnchor = anchor
        host.notify(ModePlace.noteToEbook(entry.from ?: ReadingMode.ALONG))
        uiScope.launch {
            var target = if (anchor.document in documentOrder) annotationLocator(ReadingAnnotation(id = "heard", document = anchor.document, quote = anchor.quote)) else null
            if (target == null) {
                val book = publication
                target = if (book == null) null else runCatching { EpubBookSearch.find(book, anchor.quote.highlight.take(80), limit = 1).firstOrNull() }.getOrNull()
            }
            target?.let { goTo(it) }
            drawHeard(target ?: return@launch)
        }
    }

    /** "Heard to here" over the sentence, in the accent. */
    private fun drawHeard(at: Locator) {
        val reader = navigator ?: return
        uiScope.launch {
            reader.applyDecorations(listOf(Decoration("heard", at, Decoration.Style.Underline(colors.accent), mapOf(ReaderMarks.KIND to ReaderMarks.HEARD))), ReaderMarks.HEARD_GROUP)
        }
    }

    /** Whether the "Heard to here" mark is on the page in front. */
    private suspend fun heardOnPage(): Boolean {
        val reader = navigator ?: return false
        val raw = runCatching { reader.evaluateJavascript(HEARD_ON_PAGE) }.getOrNull()
        return raw?.trim('"') == "1"
    }

    private fun showListeningEditions(made: ModeEntry) {
        if (audioEditions.size == 1) return openAudioEdition(audioEditions.first(), made)
        overlay.show("Audiobook editions", "Choose a narration", audioEditions.mapIndexed { index, edition ->
            ChoiceOverlay.Choice(index.toString(), edition.narrator.ifBlank { "Audio edition ${index + 1}" },
                edition.format.ifBlank { "Audio" }.uppercase())
        }) { selected -> audioEditions.getOrNull(selected.toIntOrNull() ?: -1)?.let { openAudioEdition(it, made) } }
    }

    private fun openAudioEdition(edition: ReadingEdition, made: ModeEntry) {
        narration?.pause()
        host.back()
        host.push(AudiobookScreen(api, workId, edition, title, ringVisible, audioEditions,
            ReadingEdition(source = edition.source, kind = "ebook", sourceItemId = ebookSourceItemId),
            alignedEditions, onProgressChanged, entry = made))
    }

    private fun showNarrationChooser(made: ModeEntry) {
        if (alignedEditions.isEmpty()) return
        overlay.show("Audiobook narration", "Choose the synchronized edition", alignedEditions.mapIndexed { index, edition ->
            ChoiceOverlay.Choice(index.toString(), edition.narrator.ifBlank { "Narration ${index + 1}" },
                selected = edition.sourceItemId == sourceItemId)
        }) { selected ->
            alignedEditions.getOrNull(selected.toIntOrNull() ?: -1)?.let { choice ->
                if (!readAlong || choice.sourceItemId != sourceItemId) replaceReaderMode(true, choice.sourceItemId, made)
            }
        }
    }

    private fun replaceReaderMode(aligned: Boolean, targetSourceItemId: String, made: ModeEntry) {
        saveCurrent(immediate = true)
        narration?.pause()
        host.back()
        host.push(EpubReaderScreen(api, workId, targetSourceItemId, title, ringVisible,
            onProgressChanged, readAlong = aligned, readAlongAvailable = readAlongAvailable,
            alignedEditions = alignedEditions, audioEditions = audioEditions, ebookSourceItemId = ebookSourceItemId,
            bookPages = bookPages, entry = made))
    }

    private fun locatorJson(locator: Locator): kotlinx.serialization.json.JsonObject =
        json.parseToJsonElement(locator.toJSON().toString()).jsonObject

    private fun refreshBookmarkButton() {
        if (!::bookmarkButton.isInitialized || !::bookmarks.isInitialized) return
        val saved = runCatching { latestLocator?.let { bookmarks.contains(checkpointKey, locatorJson(it)) } == true }
            .getOrDefault(false)
        bookmarkButton.contentDescription = if (saved) "Remove bookmark" else "Add bookmark"
        com.pocketds.hub.ui.OverlayButtons.setDiscIcon(bookmarkButton, if (saved) AppIcon.BOOKMARK_FILLED else AppIcon.BOOKMARK)
    }

    private fun toggleBookmark() {
        val locator = latestLocator ?: return host.notify("The book is still opening")
        try {
            val added = bookmarks.toggle(checkpointKey, locatorJson(locator))
            refreshBookmarkButton()
            host.notify(if (added) "Bookmark added" else "Bookmark removed")
        } catch (_: Exception) { host.notify("Bookmark could not be saved on this device") }
    }

    private fun showNavigator() {
        showTableOfContents()
    }

    private fun cancelSearch() { searchGeneration++; searchJob?.cancel(); searchJob = null }

    private fun jumpTo(target: Locator, remember: Boolean = true): Boolean {
        val previous = latestLocator
        paceTracker.restart()
        bookScroll.reset()
        movedByHand()
        if (!goTo(target)) {
            host.notify("This reading position could not be opened")
            return false
        }
        if (remember && previous != null) returnLocator = previous
        overlay.dismiss(); updatePosition(); host.refreshHints()
        return true
    }

    /** A place in another file being gone to in two steps ([goTo]); cancelled by anything that moves the page after it. */
    private var placeJob: Job? = null

    /**
     * Goes to [target], and lands exactly on it (#59). Readium's `go` is not reliable to a place in another file (a Contents
     * entry into the middle of a chapter, a bookmark, a search result, the slider): from an earlier file it lands on the place or
     * on the top of the file, from a later one at the end of the file. A place in another file is therefore gone to in two
     * steps, each of which lands ([AnchorJump]): the file from its top, waited for until it has loaded and its columns have
     * stopped moving, then the place, which is a jump within the file on screen. The page is hidden in between, so what shows
     * is the page it arrives on. False where Readium refused the first step.
     */
    private fun goTo(target: Locator): Boolean {
        val reader = navigator ?: return false
        cancelPlace()
        val locations = target.locations
        val place = AnchorJump.namesPlace(locations.fragments, locations.progression, locations.otherLocations.isNotEmpty(),
            !target.text.highlight.isNullOrBlank())
        if (!AnchorJump.resourceFirst(latestLocator?.document, target.document, place)) return reader.go(target, animated = false)
        if (!reader.go(target.copy(locations = Locator.Locations(progression = 0.0), text = Locator.Text()), animated = false)) return false
        pageHost.alpha = 0f
        val generation = ++placeGeneration
        placeJob = uiScope.launch {
            try {
                awaitLaidOut(reader, target.document)
                reader.go(target, animated = false)
                // The element it names is on the page, or the jump is made again: with the machine busy the first has come before the page was ready.
                val fragment = target.locations.fragments.firstOrNull { it.isNotBlank() }
                // Looked at until it has been right twice running (200 ms apart): with the machine busy the page has been seen to put
                // itself back at the top of the file a moment after the jump, which one look at the right page would have missed.
                var tries = 0
                var right = 0
                while (right < AnchorJump.RIGHT_LOOKS && tries++ < AnchorJump.RETRIES) {
                    delay(AnchorJump.LOOK_AFTER_MS)
                    val landed = if (fragment != null)
                        AnchorJump.landed(runCatching { reader.evaluateJavascript(AnchorJump.landedScript(fragment)) }.getOrNull())
                    else AnchorJump.landedByProgress(target.locations.progression, reader.currentLocator.value.locations.progression)
                    when (landed) {
                        null -> right = AnchorJump.RIGHT_LOOKS
                        true -> right++
                        false -> { right = 0; reader.go(target, animated = false) }
                    }
                }
                // The place is scrolled to once Readium has been told; a moment for it to be drawn before the page is shown.
                delay(PLACE_SETTLE_MS)
            } finally {
                // Not a place a newer jump has taken over.
                if (generation == placeGeneration) pageHost.alpha = 1f
            }
        }
        return true
    }

    /** Waits until [document] is the file in front, loaded, with its fonts in and its columns no longer changing; gives up after [AnchorJump.TIMEOUT_MS]. */
    private suspend fun awaitLaidOut(reader: EpubNavigatorFragment, document: String) {
        val script = AnchorJump.script(document)
        var before: AnchorJump.State? = null
        val waited = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - waited < AnchorJump.TIMEOUT_MS) {
            val now = AnchorJump.parse(runCatching { reader.evaluateJavascript(script) }.getOrNull())
            if (AnchorJump.settled(before, now)) return
            before = now
            delay(AnchorJump.POLL_MS)
        }
    }

    private var placeGeneration = 0

    private fun cancelPlace() {
        placeGeneration++
        placeJob?.cancel(); placeJob = null
        if (::pageHost.isInitialized) pageHost.alpha = 1f
    }

    private fun seekBook() {
        if (bookSections.isEmpty()) return
        val fraction = bookSeek.progress / 100.0
        val index = bookSections.indexOfLast { (it.locations.totalProgression ?: 0.0) <= fraction }.coerceAtLeast(0)
        val section = bookSections[index]
        val start = section.locations.totalProgression ?: 0.0
        val end = bookSections.getOrNull(index + 1)?.locations?.totalProgression ?: 1.0
        val local = if (end > start) ((fraction - start) / (end - start)).coerceIn(0.0, 1.0) else 0.0
        jumpTo(section.copy(locations = Locator.Locations(progression = local), text = Locator.Text()))
    }

    // Readium's stable positions can be sparse in short/compressed chapters. Interpolate the
    // display and scrubber within each resource while leaving saved Readium locators untouched.
    private fun bookProgress(): Double? {
        val current = latestLocator ?: return null
        val span = PageInfo.sectionSpan(sectionStarts, bookSections.indexOfFirst { it.href == current.href })
            ?: return current.locations.totalProgression
        return PageInfo.within(span, current.locations.progression ?: 0.0)
    }

    private fun showPageNavigation() {
        cancelSearch()
        overlay.resetBody()
        overlay.open("Reading position", "Screen pages are within this section. Hold L2/R2 to change sections; L1/R1 turn pages.", onDismiss = { host.refreshHints() })
        if (pageCount > 0) {
            val page = EditText(host.viewContext).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText((pageIndex + 1).toString()); setTextColor(colors.primaryText)
                contentDescription = "Section page number, 1 to $pageCount"
                selectAll(); minimumHeight = dp(48)
            }
            overlay.body.addView(page)
            overlay.choice("Go to section page", "1–$pageCount") {
                val number = page.text.toString().toIntOrNull()
                val current = latestLocator
                if (number == null || number !in 1..pageCount || current == null) host.notify("Enter a section page from 1 to $pageCount")
                else jumpTo(current.copy(locations = Locator.Locations(progression = (number - 1).toDouble() / pageCount), text = Locator.Text()))
            }
        }
        overlay.choice("Contents", "Jump to a chapter") { showTableOfContents() }
        overlay.choice("Bookmarks", "Saved places in this book") { showBookmarks() }
        returnLocator?.let { saved -> overlay.choice("Return to previous place") {
            if (jumpTo(saved, remember = false)) { returnLocator = null; updatePosition() }
        } }
        overlay.focusBody(); host.refreshHints()
    }

    private fun showSearch() {
        val book = publication ?: return host.notify("The book is still opening")
        cancelSearch()
        overlay.resetBody()
        overlay.open("Search this book", "Find a passage in this edition", onDismiss = { cancelSearch(); host.refreshHints() })
        val query = EditText(host.viewContext).apply {
            hint = "Word or phrase"; contentDescription = "Search this book"
            setTextColor(colors.primaryText); setHintTextColor(colors.mutedText); isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(200)); imeOptions = EditorInfo.IME_ACTION_SEARCH
            minimumHeight = dp(48)
        }
        overlay.body.addView(query)
        fun submit() {
            val phrase = query.text.toString().trim()
            if (phrase.isEmpty()) { host.notify("Enter a word or phrase"); return }
            cancelSearch()
            val generation = searchGeneration
            (host.viewContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(query.windowToken, 0)
            overlay.resetBody()
            overlay.choice("Searching…") { }
            searchJob = uiScope.launch {
                try {
                    val results = EpubBookSearch.find(book, phrase)
                    if (generation != searchGeneration || !overlay.isOpen) return@launch
                    overlay.resetBody()
                    overlay.choice("Search again", if (results.size == 100) "First 100 matches · narrow your search" else "${results.size} matches") { showSearch() }
                    results.forEach { locator ->
                        overlay.choice(locator.title?.takeIf { it.isNotBlank() } ?: "Matching passage",
                            listOfNotNull(locator.text.before, locator.text.highlight, locator.text.after).joinToString("").take(240)) {
                            jumpTo(locator)
                        }
                    }
                    overlay.focusBody()
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    if (generation == searchGeneration && overlay.isOpen) {
                        overlay.resetBody(); overlay.choice("Search took too long", "Try a more specific phrase") { showSearch() }; overlay.focusBody()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (generation == searchGeneration && overlay.isOpen) {
                        overlay.resetBody(); overlay.choice("Could not search this edition", "Choose to try again") { showSearch() }; overlay.focusBody()
                    }
                }
            }
        }
        query.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        overlay.choice("Search") { submit() }
        overlay.focusBody(query); host.refreshHints()
    }

    private fun showBookmarks() {
        cancelSearch()
        val entries = try { bookmarks.list(checkpointKey) }
            catch (_: Exception) { return host.notify("Bookmarks could not be read on this device") }
        overlay.resetBody()
        overlay.open("Navigator", "${entries.size} saved locations", onDismiss = { host.refreshHints() })
        overlay.tabs(NAVIGATOR_TABS, "bookmarks") {
            if (it == "contents") showTableOfContents() else showHighlights()
        }
        if (entries.isEmpty()) overlay.choice("No bookmarks in this book") { Unit }
        entries.forEach { entry ->
            overlay.choice(entry.label, "Choose to jump or delete") {
            overlay.show("Bookmark", entry.label, listOf(
                ChoiceOverlay.Choice("jump", "Go to bookmark"),
                ChoiceOverlay.Choice("delete", "Delete bookmark", danger = true)
            ), onCancel = { showBookmarks() }) { action ->
                if (action == "delete") {
                    try {
                        bookmarks.remove(checkpointKey, entry.anchor)
                        refreshBookmarkButton()
                        showBookmarks()
                    } catch (_: Exception) { host.notify("Bookmark could not be deleted") }
                } else {
                    val locator = runCatching { Locator.fromJSON(JSONObject(entry.locator.toString())) }.getOrNull()
                    if (locator == null || !jumpTo(locator))
                        host.notify("This bookmark could not be opened")
                    else overlay.dismiss()
                }
                host.refreshHints()
            }
            host.refreshHints()
            }
        }
        overlay.focusBody()
        host.refreshHints()
    }

    /**
     * The book's highlights and notes (#62): the quote in its colour, the note under it, the page. A row jumps to the passage; a passage
     * the book's text no longer holds is kept and listed with "Can't find this passage". Filters: All, a colour, With notes.
     */
    private fun showHighlights(filter: HighlightFilter = HighlightFilter.ALL) {
        cancelSearch()
        val book = publication ?: return host.notify("The book is still opening")
        val all = annotations.live.value
        val shown = filter.apply(all)
        overlay.resetBody()
        overlay.open("Navigator", "${all.size} highlights", onDismiss = { host.refreshHints() })
        overlay.tabs(NAVIGATOR_TABS, "highlights") {
            if (it == "contents") showTableOfContents() else showBookmarks()
        }
        val chips = LinearLayout(host.viewContext).apply { orientation = LinearLayout.HORIZONTAL }
        HighlightFilter.entries.forEach { option ->
            val chip = TextView(host.viewContext).apply {
                text = option.label
                contentDescription = option.description
                com.pocketds.hub.ui.PillButton.control(this, colors)
                com.pocketds.hub.ui.PillButton.setPrimary(this, option == filter)
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                activateOnTap { showHighlights(option) }
            }
            chips.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(2) })
        }
        overlay.body.addView(com.pocketds.hub.ui.FocusHorizontalScrollView(host.viewContext).apply {
            isHorizontalScrollBarEnabled = false; addView(chips)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
        if (shown.isEmpty()) overlay.choice(if (all.isEmpty()) "No highlights in this book" else "Nothing with this filter",
            if (all.isEmpty()) "Select words and pick a colour, and they show here on every device." else "") { Unit }
        val rows = ArrayList<Pair<View, ReadingAnnotation>>()
        shown.forEach { annotation ->
            val dot = ThemeGradientDrawable.oval(ReaderMarks.base(annotation.highlightColor))
            val row = overlay.choice("\u201C" + annotation.quote.highlight.take(140) + "\u201D", annotation.note.take(160),
                icon = dot, trailing = "", trailingSpoken = "") {
                openHighlight(annotation)
            }
            rows += row to annotation
        }
        overlay.focusBody(rows.firstOrNull()?.first)
        host.refreshHints()
        // Each passage is looked for in its file's text, once, off the main thread: a page number for the ones found, and a note under the rest.
        val generation = ++highlightRowsGeneration
        highlightRowsJob?.cancel()
        highlightRowsJob = uiScope.launch {
            val places = withContext(Dispatchers.Default) { placeHighlights(book, shown) }
            if (generation != highlightRowsGeneration || !overlay.isOpen) return@launch
            rows.forEach { (row, annotation) ->
                val place = places[annotation.id]
                if (place == null) overlay.setDetail(row, "Can\u2019t find this passage", warn = true)
                else if (place.page != null) overlay.setTrailing(row, place.page.toString(), "page ${place.page}")
            }
        }
    }

    private var highlightRowsJob: Job? = null
    private var highlightRowsGeneration = 0

    /** Where each highlight is in the book now: the page it is on, or null for a passage this edition's text does not hold. */
    private suspend fun placeHighlights(book: Publication, list: List<ReadingAnnotation>): Map<String, HighlightPlace?> {
        val texts = HashMap<String, String?>()
        val found = HashMap<String, HighlightPlace?>()
        for (annotation in list) {
            coroutineContext.ensureActive()
            val link = book.readingOrder.firstOrNull { DocumentPath.of(it.href.toString()) == annotation.document }
            val text = texts.getOrPut(annotation.document) { link?.let { documentText(book, it.url()) } }
            val at = text?.let { AnnotationFinder.find(it, annotation.quote, annotation.locator?.let(::progressionOf)) }
            found[annotation.id] = if (at == null) null else HighlightPlace(pageAt(annotation.document, at.share))
        }
        return found
    }

    private fun progressionOf(locator: kotlinx.serialization.json.JsonObject): Double? =
        ((locator["locations"] as? kotlinx.serialization.json.JsonObject)?.get("progression") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    /** The page of the book the part [share] of the way through a document is on, by the corners' own count (#42). */
    private fun pageAt(document: String, share: Double): Int? {
        if (bookPages <= 0) return null
        val section = bookSections.indexOfFirst { it.document == document }
        val (start, end) = PageInfo.sectionSpan(sectionStarts, section) ?: return null
        val through = (start + (end - start) * share).coerceIn(0.0, 1.0)
        return (through * bookPages).toInt().coerceIn(0, bookPages - 1) + 1
    }

    /** A document's text for the search for a passage in it (#62): markup and entities taken out, as the page shows it. */
    private suspend fun documentText(book: Publication, href: org.readium.r2.shared.util.Url): String? =
        readText(book, href)?.let(DocumentText::plain)

    private data class HighlightPlace(val page: Int?)

    /** Goes to a highlight: the passage on the page, as a search result is. */
    private fun openHighlight(annotation: ReadingAnnotation) {
        val target = annotationLocator(annotation) ?: return host.notify("This highlight could not be opened")
        if (!jumpTo(target)) return
        overlay.dismiss()
    }

    private fun showTableOfContents() {
        cancelSearch()
        val book = publication ?: return host.notify("The book is still opening")
        val links = flattenLinks(book.tableOfContents.ifEmpty { book.readingOrder })
        val currentHref = latestLocator?.href?.toString()
        overlay.resetBody()
        overlay.open("Navigator", "${links.size} sections", onDismiss = { host.refreshHints() })
        overlay.tabs(NAVIGATOR_TABS, "contents") {
            if (it == "bookmarks") showBookmarks() else showHighlights()
        }
        var currentRow: View? = null
        if (links.isEmpty()) overlay.choice("This book has no table of contents") { Unit }
        val rows = ArrayList<Pair<View, Link>>()
        links.forEachIndexed { index, (depth, link) ->
            val active = link.href.toString() == currentHref
            // The page the entry starts on, as the corners count it; none yet for one still being worked out (#55).
            val page = contentsPages?.page(link.href.toString())
            val row = overlay.choice("  ".repeat(depth) + (link.title ?: "Section ${index + 1}"),
                if (active) "Current section" else "", selected = active,
                trailing = page?.toString().orEmpty(), trailingSpoken = page?.let { "page $it" }.orEmpty()) {
                book.locatorFromLink(link)?.let { jumpTo(it) }
                host.refreshHints()
            }
            rows += row to link
            if (active) currentRow = row
        }
        contentsRows = rows
        overlay.focusBody(currentRow)
        host.refreshHints()
    }

    /**
     * The page each Contents entry starts on (#55), made when the book opens: the numbers for entries that name a
     * file are known at once, and the anchors of those that point into one are looked for in the background, in the
     * text of each such file, once. Contents is built from whatever is known when it opens and never waits; the
     * rows still without a number are filled in if it is still open when the rest arrives.
     */
    private fun startContentsPages(book: Publication) {
        contentsJob?.cancel()
        val pages = ContentsPages(bookPages, bookSections.map { it.href.toString() }, sectionSizes, sectionStarts)
        contentsPages = pages
        contentsRows = emptyList()
        val sections = bookSections
        val wanted = pages.wanted(flattenLinks(book.tableOfContents.ifEmpty { book.readingOrder }).map { it.second.href.toString() })
        if (wanted.isEmpty()) return
        contentsJob = uiScope.launch {
            val found = withContext(Dispatchers.Default) {
                wanted.mapNotNull { (section, anchors) ->
                    val markup = sections.getOrNull(section)?.let { readText(book, it.href) } ?: return@mapNotNull null
                    section to AnchorShares.of(markup, anchors)
                }.toMap()
            }
            if (contentsPages !== pages) return@launch
            pages.learn(found)
            val rows = contentsRows
            rows.forEach { (row, link) ->
                pages.page(link.href.toString())?.let { overlay.setTrailing(row, it.toString(), "page $it") }
            }
        }
    }

    /** A document of the book as text, null if it cannot be read: the anchors' shares are an estimate and do without. */
    private suspend fun readText(book: Publication, href: org.readium.r2.shared.util.Url): String? {
        val resource = book.get(href) ?: return null
        return try {
            resource.read().getOrNull()?.toString(Charsets.UTF_8)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            resource.close()
        }
    }

    private fun flattenLinks(links: List<Link>, depth: Int = 0): List<Pair<Int, Link>> = buildList {
        links.forEach { link ->
            add(depth to link)
            addAll(flattenLinks(link.children, depth + 1))
        }
    }

    private fun showAppearance() {
        cancelSearch()
        appearance.show(preferences, onChanged = {
            preferences = it
            preferenceState.preview(it)
            if (preferenceState.commit()) {
                persistPreferences(preferenceState.saved)
                preferenceState.markPersisted()
            }
            applyPreferences()
        }, onClose = {
            host.refreshHints()
        }, pageInfo = pageChoice, onPageInfoChanged = ::setPageInfo,
            brightness = comfort.brightness, onBrightness = ::setBrightness)
        host.refreshHints()
    }

    private fun applyPreferences() {
        navigator?.submitPreferences(readiumPreferences(preferences))
        // A reflow moves every page break: the page is looked at again once it has settled.
        if (narration != null) { pageSpan = null; scheduleProbe(REFLOW_PROBE_MS) }
        // The wash is mixed with the page's colour: a new theme draws the sentence again.
        if (narration != null) highlightedSegment?.let(::highlightNarration)
        // The corners' ink and the strips' colour follow the page.
        if (::pageInfo.isInitialized) applyPageInfo()
        // The highlights are mixed with the page's colour too (#62).
        drawAnnotations()
    }

    /** The page, and the ink on it. */
    private fun pageColors(): Pair<Int, Int> = pagePalette(preferences)

    /**
     * Kindle's corners (#42): the strips the page keeps clear of the text (the navigator is inset by them,
     * and they take the page's colour), the corners' ink, and what they say.
     */
    private fun applyPageInfo() {
        navigatorContainer.setBackgroundColor(pageColors().first)
        val strip = dp(PageInfo.STRIP_DP)
        val top = if (pageChoice.topStrip) strip else 0
        val bottom = if (pageChoice.bottomStrip) strip else 0
        // Kindle's margins (#47): Readium keeps half the gap at each side of a column (attachNavigator), and the
        // rest of the outer margin is this inset, so two columns are a gap apart and the outer edge is the margin.
        val side = dp(PageGeometry.insetDp(preferences.pageMargins))
        insetPx = side
        (pageHost.layoutParams as FrameLayout.LayoutParams).let { margins ->
            if (margins.topMargin != top || margins.bottomMargin != bottom || margins.leftMargin != side || margins.rightMargin != side) {
                margins.topMargin = top; margins.bottomMargin = bottom; margins.leftMargin = side; margins.rightMargin = side
                pageHost.requestLayout()
            }
        }
        refreshPageInfo()
    }

    /** What the corners say now: the same place and time left the menu's line is made from. */
    private fun refreshPageInfo() {
        if (!::pageInfo.isInitialized) return
        val current = latestLocator
        val section = if (current == null) -1 else bookSections.indexOfFirst { it.href == current.href }
        val place = if (current == null) PagePlace() else PageInfo.place(
            bookPages, sectionSizes, section, current.locations.progression ?: 0.0,
            PageInfo.sectionSpan(sectionStarts, section), bookProgress(), currentTimeLeft()
        )
        // The corners line up with the text's outer edge, which is the outer margin from the screen's.
        pageInfo.show(pageChoice, place, title, PageInfo.ink(pageColors().second),
            dp(PageGeometry.outerMarginDp(preferences.pageMargins)), dp(PageInfo.STRIP_DP))
    }

    private fun setPageInfo(value: PageInfoChoice) {
        pageChoice = value
        PageInfoSettings.save(host.viewContext, value)
        applyPageInfo()
    }

    /** A tap on the bottom left, or L3: the next choice, as on Kindle (#42). */
    private fun cyclePageInfo() = setPageInfo(pageChoice.copy(corner = PageInfo.next(pageChoice.corner)))

    private fun loadPreferences() = EpubAppearanceStore.load(host.viewContext)

    private fun persistPreferences(value: EpubReaderPreferences) = EpubAppearanceStore.save(host.viewContext, value)

    private fun readiumPreferences(value: EpubReaderPreferences) = EpubPreferences(
        theme = when (EpubPagePalette.resolve(value.theme, isNight())) {
            EpubTheme.SEPIA -> ReadiumTheme.SEPIA
            EpubTheme.DARK, EpubTheme.BLACK, EpubTheme.BLUE -> ReadiumTheme.DARK
            else -> ReadiumTheme.LIGHT
        },
        backgroundColor = org.readium.r2.navigator.preferences.Color(pagePalette(value).first),
        textColor = org.readium.r2.navigator.preferences.Color(pagePalette(value).second),
        columnCount = when (if (value.onePagePerScreen) EpubColumns.ONE else value.columns) {
            EpubColumns.AUTO -> ColumnCount.AUTO
            EpubColumns.ONE -> ColumnCount.ONE
            EpubColumns.TWO -> ColumnCount.TWO
        },
        // Literata, Charis, Atkinson Hyperlegible: bundled and declared in attachNavigator; null is the book's own font.
        fontFamily = EpubFontDeclarations.family(value.fontFamily),
        fontSize = value.fontScale.toDouble(),
        lineHeight = value.lineHeight.toDouble(),
        // Always the same: the margin is chosen by the page's inset (PageGeometry), not by Readium's multiplier.
        pageMargins = PageGeometry.READIUM_MARGIN_FACTOR,
        publisherStyles = value.publisherStyles,
        hyphens = value.hyphenation,
        scroll = value.scroll && !value.onePagePerScreen,
        textAlign = when (value.textAlignment) {
            "justify" -> TextAlign.JUSTIFY
            "center" -> TextAlign.CENTER
            else -> TextAlign.START
        }
    )

    private fun setControlsVisible(visible: Boolean) {
        if (visible) stopCursor()
        controlsVisible = visible
        // The bars, the narration's dock among them, show and hide together.
        pagePreview.setControlsVisible(visible)
        updateDock()
        pagePreview.refresh()
        refreshKeys()
        if (!visible) root.findFocus()?.clearFocus() else root.post { controls.getOrNull(focusedControl)?.requestFocus() }
        if (::host.isInitialized) host.refreshHints()
    }

    private fun moveControlFocus(direction: Direction) {
        val focused = root.findFocus()
        val axis = when (direction) { Direction.LEFT -> View.FOCUS_LEFT; Direction.RIGHT -> View.FOCUS_RIGHT; Direction.UP -> View.FOCUS_UP; Direction.DOWN -> View.FOCUS_DOWN }
        // The page is never where the menu's focus goes, and the search must not find it: with a strip kept above
        // the text for the corners (#42) its top lies below the top bar's buttons, which made it the nearest thing
        // under them, ahead of the position row.
        pageHost.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val next = try { FocusFinder.getInstance().findNextFocus(root, focused, axis) }
            finally { pageHost.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS }
        if (next != null && next in controls && next.isShown) { focusedControl = controls.indexOf(next); next.requestFocus() }
    }

    private fun updatePosition() {
        if (!::position.isInitialized) return
        val progression = bookProgress()
        position.text = buildList {
            add(latestLocator?.title?.takeIf { it.isNotBlank() } ?: "Reading")
            if (pageCount > 0) add("Section page ${pageIndex + 1}/$pageCount")
            if (progression != null) add("${com.pocketds.hub.state.Fmt.readingPercentLabel(progression)} of book")
        }.joinToString(" · ")
        position.maxLines = 2
        if (::bookSeek.isInitialized) {
            bookSeek.isEnabled = bookPositions.isNotEmpty()
            if (!bookSeek.isPressed && !bookSeek.hasFocus()) bookSeek.progress = com.pocketds.hub.state.Fmt.readingPercent(progression ?: 0.0)
        }
        if (::returnButton.isInitialized) returnButton.visibility = if (returnLocator == null) View.GONE else View.VISIBLE
        updateTimeLeft()
    }

    /** Where in the whole book the page is, in positions. */
    private fun bookPosition(): Double? {
        val current = latestLocator ?: return null
        return TimeLeft.position(sectionSizes, bookSections.indexOfFirst { it.href == current.href }, current.locations.progression ?: 0.0)
    }

    /**
     * The pace learns from the reading (E3): each new place on the page, and
     * when it was reached. The page turning with the narration is listening,
     * not reading, so it teaches nothing.
     */
    private fun observePace() {
        val position = bookPosition() ?: return
        if (narration?.isOn == true) { paceTracker.restart(); return }
        val reading = paceTracker.at(position, SystemClock.elapsedRealtime()) ?: return
        pace = ReadingPaceSettings.record(host.viewContext, paceKey(), pace, reading)
    }

    /**
     * Under the title: the time left in the chapter and the book (E3). Reading
     * along with the page following the voice, the narration's own; otherwise
     * the pace's over the positions left.
     */
    private fun updateTimeLeft() {
        if (!::timeLeftView.isInitialized) return
        val left = currentTimeLeft()
        timeLeftView.text = left?.label().orEmpty()
        timeLeftView.visibility = if (left == null) View.GONE else View.VISIBLE
        refreshPageInfo()
    }

    /** The one measure of time left, for the menu's line and the page's corner (#42): no second pace. */
    private fun currentTimeLeft(): TimeLeft? {
        val audio = narration
        val current = latestLocator
        return if (audio != null && !matchNarrationToPage) TimeLeft.ofNarration(audio.timeline, audio.position, audio.speed)
            else if (current == null) null
            else TimeLeft.ofPositions(sectionSizes, bookSections.indexOfFirst { it.href == current.href },
                current.locations.progression ?: 0.0, pace.minutesPerPosition(pacePrior))
    }

    private fun paceKey(): String = "$workId:$sourceItemId"

    /**
     * Readium's link listener (E5). A footnote reference opens its note as a
     * card and stays put; any other link in the book is followed, remembering
     * where it was followed from for "Return to previous place". A footnote's
     * is asked on the web view's own thread, so the card is posted.
     */
    private val linkListener = object : EpubNavigatorFragment.Listener {
        override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
            if (context is HyperlinkNavigator.FootnoteContext) {
                val note = context.noteContent
                root.post { showFootnote(note, link) }
                return false
            }
            linkOrigin = latestLocator
            linkOriginAt = SystemClock.uptimeMillis()
            root.post { turnedByHand() }
            return true
        }

        override fun onJumpToLocator(locator: Locator) {
            val origin = linkOrigin ?: return
            linkOrigin = null
            if (SystemClock.uptimeMillis() - linkOriginAt > LINK_MS) return
            root.post {
                returnLocator = origin
                paceTracker.restart()
                updatePosition()
                host.notify("Return to previous place is in the menu")
            }
        }

        override fun onExternalLinkActivated(url: AbsoluteUrl) {
            root.post { host.notify("This link leads out of the book, so it is not opened here") }
        }
    }

    /** The page moved by hand, by a link followed or a drag (A5, #49): see [movedByHand]. */
    private fun turnedByHand() = movedByHand()

    /** A footnote's card (E5); an empty note is simply followed. */
    private fun showFootnote(html: String, link: Link) {
        val text = FootnoteText.plain(html)
        val target = publication?.locatorFromLink(link)
        if (text.isBlank()) {
            target?.let { jumpTo(it) }
            return
        }
        footnoteCard.onFollow = {
            closeFootnote()
            target?.let { jumpTo(it) }
        }
        footnoteCard.show(text, canFollow = target != null)
        host.refreshHints()
    }

    private fun closeFootnote() {
        if (!::footnoteCard.isInitialized || !footnoteCard.isOpen) return
        footnoteCard.dismiss()
        host.refreshHints()
    }

    private fun showFailure(message: String) {
        loading.visibility = View.VISIBLE
        loading.text = "$message\n\nSelect retries"
    }

    private fun fragmentTag() = "epub:$workId:$sourceItemId"
    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        /** Continuous scrolling: the D-pad moves a third of a screen, the right stick at full push 1.5 screens a second. */
        const val SCROLL_STEP = 1f / 3f
        const val GLIDE = 1.5f
        /** How long the D-pad's step eases, and how far the stick pushes past the end of a part before it goes on. */
        const val STEP_MS = 160L
        const val EDGE_SCREENS = 0.5f
        /** A jump within this long of a link being followed is the link's. */
        const val LINK_MS = 3_000L
        /** The lower bar: the position row over the book's line, with their padding. */
        const val BOTTOM_ROW_DP = 44 + 30 + 8
        /** Read along (#49): how long after a page change it settles before the page is looked at, and after a reflow. */
        const val PROBE_MS = 90L
        const val REFLOW_PROBE_MS = 450L
        const val PROBE_RETRIES = 4
        const val PROBE_RETRY_MS = 400L
        /** How often the page asks whether the voice has moved on, and how long a page change of its own is expected. */
        const val FOLLOW_MS = 100L
        const val OWN_MOVE_MS = 1_500L
        /** After the place in a file has been gone to, the page is drawn this long before it is shown again (#59). */
        const val PLACE_SETTLE_MS = 120L
        /** How long a switch of mode waits for the hub to take the place it kept, before it opens the next screen anyway. */
        const val FLUSH_MS = 2_500L
        /** Whether the "Heard to here" mark has a box on the page in front, as 1 or 0. */
        const val HEARD_ON_PAGE = """(function(){var b=document.querySelectorAll('[data-group="heard"] [data-style] > *');
            for(var i=0;i<b.length;i++){var r=b[i].getBoundingClientRect();if(r.width>0&&r.right>0&&r.left<window.innerWidth&&r.bottom>0&&r.top<window.innerHeight)return 1;}return 0;})()"""
        /** The cursor's turn of a page: how long it waits for the page to be drawn, and how many times it looks. */
        const val CURSOR_SETTLE_MS = 160L
        const val CURSOR_TRIES = 12
        /** A note's room in the field, in characters; the hub keeps [AnnotationLimits.NOTE_BYTES] bytes of it. */
        const val NOTE_CHARS = 1_500
        val NAVIGATOR_TABS = listOf("contents" to "Contents", "highlights" to "Highlights", "bookmarks" to "Bookmarks")
    }
}
