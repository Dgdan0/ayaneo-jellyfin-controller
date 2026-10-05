package com.pocketds.hub

import android.content.Context
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.FocusGuard
import com.pocketds.hub.ui.FloatingPlayerView
import com.pocketds.hub.ui.StripNav
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.PadEventRouter
import com.pocketds.hub.input.PadNames
import com.pocketds.hub.input.PadTicker
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ContentModeScreen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.nav.SectionStacks
import com.pocketds.hub.nav.StatusStripView
import com.pocketds.hub.nav.TopBarView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflinePrepareBody
import com.pocketds.hub.model.OfflinePrepareItem
import com.pocketds.hub.playback.PlaybackOptionsScreen
import com.pocketds.hub.playback.PlaybackService
import com.pocketds.hub.playback.PlayerScreen
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineCatalogProgress
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.screens.offline.OfflineSelectionScreen
import com.pocketds.hub.screens.offline.OfflineScreen
import com.pocketds.hub.screens.downloads.DownloadsScreen
import com.pocketds.hub.screens.discover.DiscoverScreen
import com.pocketds.hub.screens.home.HomeScreen
import com.pocketds.hub.screens.library.LibraryScreen
import com.pocketds.hub.screens.manage.ManageScreen
import com.pocketds.hub.screens.notifications.NotificationsScreen
import com.pocketds.hub.screens.settings.SettingsScreen
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.settings.HapticSettings
import com.pocketds.hub.settings.NotificationReadStore
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.settings.Look
import com.pocketds.hub.settings.LookSettings
import com.pocketds.hub.settings.ThemeSettings
import com.pocketds.hub.nav.PageArtwork
import com.pocketds.hub.ui.KeyHaptics
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.glass.AmbientLayerView
import com.pocketds.hub.ui.glass.ArtworkColors
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one Activity.
 *
 * Everything is a screen swapped into this window rather than a separate
 * Activity, because the input pipeline is per-window state that has to exist
 * exactly once: one router, one frame ticker, one dead-zone, one focus owner.
 * Separate Activities would rebuild the ticker on every navigation, re-run the
 * source latches, and re-inflate the chrome that is supposed to be the constant
 * thing on screen.
 *
 * The cost, stated plainly: a hand-rolled back stack, and no state restoration
 * after process death -- we restart at the section root. Accepted.
 */
@androidx.media3.common.util.UnstableApi
class HubActivity : AppCompatActivity(), ScreenHost {

    private val colors by lazy { Theme.colors(this) }

    private lateinit var router: PadEventRouter
    private lateinit var ticker: PadTicker
    private lateinit var sections: SectionStacks

    private lateinit var hintBar: HintBarView
    private lateinit var overlay: FrameLayout
    private lateinit var player: FloatingPlayerView

    /**
     * Whether the pad is driving the trailer window rather than the screen.
     *
     * An explicit mode, rather than making the window a focus target. It lives
     * in the chrome layer, outside the content that the focus guard confines
     * movement to, and letting directional search reach across that boundary is
     * exactly the class of bug that had the selection escaping into the tab bar.
     * Start takes control; X hands it back.
     */
    private var trailerMode = false
    private var trailerReturnFocus: View? = null
    private var enteringPictureInPicture = false
    private var pictureInPictureSessionActive = false
    private lateinit var statusStrip: StatusStripView
    private lateinit var topBar: TopBarView
    /** "‹ Title" under the tabs, for a pushed page that does not draw its own heading. */
    private lateinit var pageTitle: android.widget.TextView
    private lateinit var content: FrameLayout
    /**
     * Glass: the page behind everything (GLASS_PLAN.md) -- the artwork the
     * screen in front reports, blurred, over its dark colour. Null in Classic.
     */
    private var ambient: AmbientLayerView? = null
    /** The artwork the page shows, the colours it is tinted with, and the colour request still out. */
    private var shownArtwork: String? = null
    private var pagePalette = ArtworkPalette.NEUTRAL
    private var paletteWait: ((ArtworkPalette) -> Unit)? = null
    private var lastContentSection = 0
    private var utilityReturnFocus: View? = null

    private val focusTick = KeyHaptics.RepeatGate(80L)

    /**
     * The view each attached screen created. Kept here rather than on the Screen
     * interface: the host owns the view's attachment to the window, so it should
     * own the reference too, and implementers are not made to hold state they do
     * not manage.
     */
    private val views = HashMap<Screen, View>()

    private lateinit var api: HubApi
    private lateinit var offlineRoot: OfflineScreen
    private val chromeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** A second press while the server position is being checked opens nothing twice. */
    private val localPlanCheck = com.pocketds.hub.state.JobSlot()
    private val notificationBadge = com.pocketds.hub.state.Poller(com.pocketds.hub.state.PollCadence.BADGE)

    /**
     * The five tabs, then the three utility pages behind the top bar's icons.
     * Downloads is what is on this device; Activity is the server's transfers.
     */
    private val sectionTitles = listOf("Home", "Discover", "Library", "Downloads", "Activity",
        "Notifications", "Services", "Settings")

    override fun onCreate(savedInstanceState: Bundle?) {
        // An EPUB navigator has constructor dependencies supplied by Readium's
        // factory. Install its safe restoration factory before FragmentActivity
        // restores state; this Activity intentionally rebuilds its own screen
        // stack after process death.
        supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
        // Glass is always dark, so its night resources (the services' logos)
        // apply whatever the Theme setting says; that setting is Classic's.
        val look = LookSettings.get(this)
        AppCompatDelegate.setDefaultNightMode(
            if (look == Look.GLASS) AppCompatDelegate.MODE_NIGHT_YES else when (ThemeSettings.getMode(this)) {
                ThemeSettings.Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeSettings.Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeSettings.Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
        builtLook = look
        builtDark = Theme.isDark(this)
        super.onCreate(savedInstanceState)

        // Intent extras first: scripts/dev.sh seed pushes the URL and token in
        // this way, because typing a 43-character token on a handheld after every
        // clean install is a reason not to test.
        seedFromIntent()
        api = HubClient.shared(this)
        requestDownloadNotificationPermission()

        router = PadEventRouter(triggerHoldContext = {
            (sections.stack().peek() as? Screen)?.takeIf { it.requiresTriggerHold && !trailerMode }
        }, emit = ::onPadAction)
        ticker = PadTicker(router)
        sections = SectionStacks(sectionTitles.size)

        setContentView(buildChrome())
        // hints() reads the focused item, so every path that moves focus had to
        // refresh the bar afterwards, and the ones that forgot left it stale (a
        // menu returning from a submenu kept the bell's hints). Following every
        // focus change here covers them all; one refresh per frame at most.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            window.decorView.removeCallbacks(focusSettled)
            window.decorView.post(focusSettled)
        }
        if (savedInstanceState != null) {
            supportFragmentManager.fragments
                .filterIsInstance<EpubNavigatorFragment>()
                .forEach { fragment ->
                    supportFragmentManager.beginTransaction()
                        .remove(fragment)
                        .commitNowAllowingStateLoss()
                }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleSystemBack()
        })

        sections.push(HomeScreen(api, ::ringVisible).also { attach(it) })
        sections.select(1); sections.push(DiscoverScreen(api, ::ringVisible).also { attach(it) })
        sections.select(2); sections.push(LibraryScreen(api, ::ringVisible).also { attach(it) })
        offlineRoot = OfflineScreen(api, ::ringVisible).also { attach(it) }
        sections.select(3); sections.push(offlineRoot)
        sections.select(4); sections.push(com.pocketds.hub.screens.downloads.ActivityScreen(api, ::ringVisible).also { attach(it) })
        sections.select(NOTIFICATIONS_SECTION); sections.push(
            NotificationsScreen(api, ::ringVisible) { count ->
                if (::topBar.isInitialized) topBar.setBadge(count)
            }.also { attach(it) }
        )
        sections.select(6); sections.push(ManageScreen(api, ::ringVisible).also { attach(it) })
        sections.select(7); sections.push(SettingsScreen(api, ::ringVisible).also { attach(it) })
        sections.select(
            savedInstanceState?.getInt(STATE_SECTION, 0)
                ?.coerceIn(sectionTitles.indices) ?: 0
        )

        showCurrent()
        openAlertIntent()
        com.pocketds.hub.alerts.TransferAlertJob.schedule(this)
        val offlineRepository = OfflineRepository.get(this)
        if (offlineRepository.batches().any { batch ->
                !batch.paused && batch.jobs.any { it.state != com.pocketds.hub.offline.OfflineState.COMPLETE }
            } || offlineRepository.outbox().isNotEmpty() || offlineRepository.nextSubtitleSyncRetryAt() != null
        ) OfflineDownloadService.start(this)
        DebugLog.log("nav", "HubActivity created with ${sectionTitles.size} sections")
    }

    /**
     * launchMode is singleTask, so a second `am start` on an already-running app
     * arrives here rather than at onCreate. Without this override, `dev.sh seed`
     * silently does nothing whenever the app happens to be open — which is
     * almost always, since deploy launches it.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        seedFromIntent()
        (sections.stack().peek() as? Screen)?.onShow()
        openAlertIntent()
        com.pocketds.hub.alerts.TransferAlertJob.schedule(this)
    }

    private fun openAlertIntent() {
        val id=intent?.getStringExtra(com.pocketds.hub.settings.LocalAlerts.EXTRA_ID) ?: return
        val profile=intent?.getStringExtra(com.pocketds.hub.settings.LocalAlerts.EXTRA_SCOPE)
        intent.removeExtra(com.pocketds.hub.settings.LocalAlerts.EXTRA_ID)
        if(profile!=com.pocketds.hub.settings.LocalAlerts.scope(this)) {
            notify("This alert belongs to another Hub or profile. Switch to that profile to open it.");return
        }
        val alert=com.pocketds.hub.settings.LocalAlerts.list(this).firstOrNull {it.id==id}
        if(alert==null) {notify("This alert is no longer available.");return}
        com.pocketds.hub.settings.LocalAlerts.markSeen(this,id)
        com.pocketds.hub.screens.notifications.openLocalAlert(this,api,alert,::ringVisible)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::sections.isInitialized) outState.putInt(STATE_SECTION, sections.current)
        super.onSaveInstanceState(outState)
    }

    /** Accepts `-e hub_url ... -e hub_token ...` from dev.sh seed. */
    private fun seedFromIntent() {
        val url = intent?.getStringExtra("hub_url")
        val token = intent?.getStringExtra("hub_token")
        if (!url.isNullOrBlank() && !token.isNullOrBlank()) {
            HubSettings.save(this, url, token)
            DebugLog.log("auth", "hub seeded from intent: $url")
        } else if (
            applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            !url.isNullOrBlank()
        ) {
            // Hardware tests often tunnel a development Hub through `adb
            // reverse`. A debug build may repoint the existing credential
            // without ever reading it into the desktop shell. Release builds
            // require URL and token together so another app cannot redirect a
            // valid credential to an address it controls.
            HubSettings.token(this).takeIf { it.isNotBlank() }?.let {
                HubSettings.setDebugBaseUrl(url)
                DebugLog.log("auth", "debug hub URL seeded from intent: $url")
            }
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun buildChrome(): View {
        // The chrome sits inside a frame so the floating trailer window can be
        // laid over all of it, hint bar included.
        overlay = FrameLayout(this)
        if (Theme.isGlass(this)) {
            // The page itself, under the content, the tabs and the hint bar.
            // Nothing above it paints a page colour (Theme's Glass palette has
            // none); the frame's own dark is only what shows while an
            // immersive screen has the page hidden.
            overlay.setBackgroundColor(ArtworkPalette.NEUTRAL.dark)
            ambient = AmbientLayerView(this, api).also {
                overlay.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
        val glass = ambient != null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }

        // The tabs float over the content rather than sitting above it, so
        // Home's hero can run to the top edge under a see-through bar. Every
        // other screen is pushed down by the bar's height (layoutScreen).
        val stage = FrameLayout(this).apply { clipChildren = false }
        content = FrameLayout(this).apply { clipChildren = false }
        stage.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        topBar = TopBarView(this, colors, ::ringVisible, sectionTitles.take(CONTENT_SECTION_COUNT), glass).apply {
            onModeSelected = { mode ->
                ContentModeSettings.set(this@HubActivity, mode)
                refreshAppearance()
                (sections.stack().peek() as? ContentModeScreen)?.selectContentMode(mode)
                setMode(mode)
                post { focusMode(mode) }
                refreshHints()
            }
            onSelect = { index ->
                router.onPointer()
                if (sections.select(index)) showCurrent()
                else if (index < CONTENT_SECTION_COUNT && sections.depth > 1) {
                    // The tab you are already on takes you back to its top.
                    while (sections.depth > 1) back()
                }
            }
            onFocused = { refreshHints() }
        }
        stage.addView(topBar, FrameLayout.LayoutParams(MATCH, Styler.dpInt(this, TopBarView.HEIGHT_DP), android.view.Gravity.TOP))
        com.pocketds.hub.ui.TopChrome.register(topBar)
        // The audiobook playing while you browse (#16, A1): the reading-audio player's state, in the bar.
        topBar.miniPlayer.onOpen = ::openListening
        topBar.miniPlayer.onToggle = { com.pocketds.hub.reader.ReadingAudio.touched(); com.pocketds.hub.reader.ReadingAudio.toggle() }
        chromeScope.launch { com.pocketds.hub.reader.ReadingAudio.state.collect { showListening() } }

        pageTitle = android.widget.TextView(this).apply {
            com.pocketds.hub.ui.Type.apply(this, com.pocketds.hub.ui.Type.Role.HEADING, 19f)
            setTextColor(colors.primaryText)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(Styler.dpInt(this@HubActivity, 22f), 0, Styler.dpInt(this@HubActivity, 22f), 0)
            // A small round back mark before the title, like the player's, rather than "‹".
            val mark = Styler.dpInt(this@HubActivity, 28f)
            val inset = Styler.dpInt(this@HubActivity, 8f)
            setCompoundDrawables(android.graphics.drawable.LayerDrawable(arrayOf(
                com.pocketds.hub.ui.ThemeGradientDrawable.oval(androidx.core.graphics.ColorUtils.setAlphaComponent(colors.primaryText, 0x1F)),
                android.graphics.drawable.InsetDrawable(com.pocketds.hub.ui.AppIconDrawable(com.pocketds.hub.ui.AppIcon.PREVIOUS, colors.primaryText), inset)
            )).apply { setBounds(0, 0, mark, mark) }, null, null, null)
            compoundDrawablePadding = Styler.dpInt(this@HubActivity, 10f)
            setBackgroundColor(colors.background)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            // A tap target for going back, for anyone without the pad in hand.
            isClickable = true
            setOnClickListener { router.onPointer(); back() }
        }
        stage.addView(pageTitle, FrameLayout.LayoutParams(MATCH, Styler.dpInt(this, PAGE_TITLE_DP), android.view.Gravity.TOP).apply {
            topMargin = Styler.dpInt(this@HubActivity, TopBarView.HEIGHT_DP)
        })
        root.addView(stage, LinearLayout.LayoutParams(MATCH, 0, 1f))

        hintBar = HintBarView(this, colors, glass).apply {
            // A pointer user reaches every contextual action through the same
            // widget that labels it for a pad user.
            onAction = { action ->
                router.onPointer()
                onPadAction(action)
            }
        }
        root.addView(hintBar)

        overlay.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        statusStrip = StatusStripView(this, colors)
        overlay.addView(statusStrip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Styler.dpInt(this@HubActivity, HintBarView.HEIGHT_DP + 10f)
            leftMargin = Styler.dpInt(this@HubActivity, 24f); rightMargin = leftMargin
        })

        player = FloatingPlayerView(
            context = this,
            colors = colors,
            onClose = { closeTrailer() },
            onOpenExternally = { url -> openExternally(url) },
            safeArea = { trailerSafeArea() },
            onChanged = { refreshHints() }
        ).apply { layoutParams = FrameLayout.LayoutParams(0, 0) }
        overlay.addView(player)
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (player.isOpen) player.applyBounds()
        }

        return overlay
    }

    override fun openTrailer(key: String, watchUrl: String, title: String) {
        if (key.isEmpty()) {
            openExternally(watchUrl)
            return
        }
        trailerReturnFocus = content.findFocus()
        player.open(key, watchUrl, title)
        trailerMode = true
        content.findFocus()?.clearFocus()
        refreshHints()
    }

    private fun closeTrailer() {
        player.close()
        trailerMode = false
        player.setControlMode(false)
        refreshHints()
        // Focus was cleared when the trailer took over, so put it back rather
        // than leaving the window with no owner and the next press doing nothing.
        restoreTrailerFocus()
    }

    private fun restoreTrailerFocus() {
        val remembered = trailerReturnFocus
        if (remembered != null && remembered.isShown && remembered.requestFocus()) {
            refreshHints()
            return
        }
        returnFocusToScreen()
    }

    /** The content rectangle excludes tabs, status, and the bottom hint bar. */
    private fun trailerSafeArea(): android.graphics.Rect {
        if (!::content.isInitialized || !::overlay.isInitialized || overlay.width <= 0) {
            return android.graphics.Rect()
        }
        val outer = IntArray(2)
        val inner = IntArray(2)
        overlay.getLocationInWindow(outer)
        content.getLocationInWindow(inner)
        val left = inner[0] - outer[0]
        val top = inner[1] - outer[1] + if (topBar.visibility == View.VISIBLE) topBar.height else 0
        return android.graphics.Rect(left, top, left + content.width, inner[1] - outer[1] + content.height)
    }

    private fun returnFocusToScreen() {
        val top = sections.stack().peek() as? Screen ?: return
        if (!top.requestInitialFocus()) views[top]?.let { focusFirst(it) }
    }

    private fun openExternally(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                )
            )
        } catch (e: android.content.ActivityNotFoundException) {
            notify("Nothing on this device opens YouTube links")
        }
    }

    /** What the pad does while the trailer has control. */
    private fun trailerHints(): List<ButtonHint> = listOf(
        ButtonHint.activate(if (player.window.fullscreen) "Shrink" else "Fullscreen"),
        ButtonHint.back("Close"),
        ButtonHint.secondary("Size: " + player.window.sizeLabel()),
        ButtonHint.primary("Back to app")
    )

    private fun attach(screen: Screen) {
        // Views are created eagerly so a section switch is instant. The screens
        // hold no data yet, so this costs nothing; A2 moves it to on-first-show.
        val view = screen.onCreateView(this, content)
        com.pocketds.hub.ui.AccentRebinder.track(view,colors)
        view.visibility = View.GONE
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        views[screen] = view
    }

    private fun detach(screen: Screen) {
        views.remove(screen)?.let { content.removeView(it) }
    }

    override fun setTopBarOverArtwork(over: Boolean) {
        val top = sections.stack().peek() as? Screen ?: return
        topBar.setOverArtwork(over && top.drawsUnderTopBar)
    }

    override fun refreshChrome() {
        val top = sections.stack().peek() as? Screen ?: return
        views[top]?.let { layoutScreen(top, it) }
        topBar.setOverArtwork(top.drawsUnderTopBar)
    }

    /**
     * An accent change repaints the views in place. Dark to light (or back)
     * rebuilds the Activity, as Android does for night mode: a live repaint
     * only maps colours it can recognise, so lines drawn in a shade derived
     * from the old palette (the Home hero's facts, a panel's heading) stayed
     * pale grey on the new white until the app restarted. The section comes
     * back from the saved state, so Settings reopens where it was.
     */
    override fun refreshAppearance() {
        if ((builtDark != null && builtDark != Theme.isDark(this)) ||
            (builtLook != null && builtLook != LookSettings.get(this))
        ) {
            recreate()
            return
        }
        if (::overlay.isInitialized) Theme.refresh(this, overlay, (sections.stack().peek() as? Screen)?.contentDomain ?: ContentModeSettings.get(this))
    }

    /** Whether the views were built dark, and in which look; null until onCreate has run. */
    private var builtDark: Boolean? = null
    private var builtLook: Look? = null

    private fun showCurrent() {
        refreshAppearance()
        val top = sections.stack().peek() as? Screen ?: return
        if (sections.current < CONTENT_SECTION_COUNT) lastContentSection = sections.current
        applyImmersive(top.immersive)
        // Clear focus before hiding: a GONE view can keep window focus, and the
        // next directional press then searches outward from something invisible
        // and appears to do nothing. This was the search box on the screen we
        // just left still holding focus behind a detail page.
        content.findFocus()?.clearFocus()
        for (i in 0 until content.childCount) {
            content.getChildAt(i).visibility = View.GONE
        }
        val view = views[top]
        view?.visibility = View.VISIBLE
        view?.let { layoutScreen(top, it) }
        topBar.setCurrent(sections.current)
        topBar.setMode(if (top is ContentModeScreen) ContentModeSettings.get(this) else null)
        topBar.setOverArtwork(top.drawsUnderTopBar)
        showListening()
        hintBar.setHints(top.hints())
        showArtwork()
        view?.post {
            if (top.focusOnShow && view.findFocus() == null) {
                if (!top.requestInitialFocus()) focusFirst(view)
            }
            // Again, after focus has actually landed. Contextual hints are read
            // off the focused item, and the setHints above runs a frame too
            // early -- on the downloads screen X read blank even though the
            // selected transfer could plainly be stopped.
            hintBar.setHints(top.hints())
        }
    }

    /**
     * The mini player (#16, A1) shows while an audiobook is on the reading-audio
     * player, except on an audiobook's own screen, which has the whole of it.
     */
    private fun showListening() {
        if (!::topBar.isInitialized) return
        val listening = com.pocketds.hub.reader.ReadingAudio.state.value
        val book = listening.book?.takeIf { (sections.stack().peek() as? Screen) !is com.pocketds.hub.reader.AudiobookScreen }
        val left = listening.bookLeftMs ?: listening.partLeftMs.takeIf { listening.partMs > 0 }
        val wasShown = topBar.miniPlayer.visibility == View.VISIBLE
        topBar.miniPlayer.show(book?.title, left?.let { "${com.pocketds.hub.state.Fmt.runtime((it / 1_000).coerceAtLeast(60))} left" }.orEmpty(),
            listening.playing)
        if (topBar.miniPlayer.hasFocus() || wasShown != (book != null)) refreshHints()
    }

    /** Ⓐ on the mini player: the audiobook's own screen, on top of wherever you are. */
    private fun openListening() {
        val screen = com.pocketds.hub.reader.ReadingAudio.state.value.book?.reopen?.invoke() ?: return
        returnFocusFromUtilities()
        push(screen)
    }

    /** Below the tabs, and below "‹ Title" for a pushed page with no heading of its own. */
    private fun layoutScreen(screen: Screen, view: View) {
        val titled = !screen.immersive && sections.depth > 1 && !screen.showsOwnTitle
        pageTitle.visibility = if (titled) View.VISIBLE else View.GONE
        if (titled) { pageTitle.text = screen.title; pageTitle.contentDescription = "Back from ${screen.title}" }
        val top = when {
            screen.immersive || screen.drawsUnderTopBar -> 0f
            titled -> TopBarView.HEIGHT_DP + PAGE_TITLE_DP
            else -> TopBarView.HEIGHT_DP
        }.let { Styler.dpInt(this, it) }
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.topMargin != top) {
            params.topMargin = top
            view.layoutParams = params
        }
    }

    private fun applyImmersive(active: Boolean) {
        // Full-screen media draws over video or a page: never the blurred page.
        ambient?.visibility = if (active) View.GONE else View.VISIBLE
        topBar.visibility = if (active) View.GONE else View.VISIBLE
        if (active) pageTitle.visibility = View.GONE
        statusStrip.setChromeVisible(!active)
        hintBar.visibility = if (active) View.GONE else View.VISIBLE
        WindowCompat.setDecorFitsSystemWindows(window, !active)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (active) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Give the screen a starting focus.
     *
     * requestFocus on a ViewGroup descends into its children, which is what is
     * wanted here: a screen whose root is a ScrollView has its focusables buried
     * several levels down, and reaching only for getChildAt(0) finds a
     * non-focusable LinearLayout and stops.
     */
    private fun focusFirst(root: View?) {
        if (root == null) return
        if (!root.requestFocus()) {
            (root as? ViewGroup)?.getChildAt(0)?.requestFocus()
        }
    }

    // ------------------------------------------------------------------ input

    /**
     * Note what this cannot do: an accessibility service declaring
     * `flagRequestFilterKeyEvents` is consulted before the foreground app and
     * can consume an event outright. Measured on this device it does not touch
     * the gamepad -- but the sticks and the D-pad hat arrive as *motion* events,
     * which such a service structurally cannot intercept, so navigation would
     * survive even if that changed.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val screen = sections.stack().peek() as? Screen
        if (screen?.onRawKeyEvent(event) == true) return true

        // The Pocket DS edge swipe is Android BACK (key code 4), while the
        // physical B button is BUTTON_B. Keep them separate: the gesture means
        // leave the player immediately; B can still dismiss player chrome first.
        if (event.keyCode == PadNames.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleSystemBack()
            return true
        }

        if (event.action == KeyEvent.ACTION_UP && router.onKeyUp(event.keyCode, event.deviceId)) return true
        if (event.action == KeyEvent.ACTION_DOWN && router.onKeyDown(event.keyCode, event.deviceId, event.eventTime, event.repeatCount)) {
            ticker.ensureRunning()
            return true
        }
        // Directional keys are ours even when the router declined this one --
        // the source latch drops duplicates on purpose, and letting the event
        // fall through means the *framework* performs its own focus search,
        // which is global and unguarded. That is how the selection kept
        // escaping a poster row and landing on a tab: the guard in moveFocus
        // was working, and some presses were never reaching it.
        //
        // A text field is the exception: left and right move the caret there,
        // and stealing them would make the search box uneditable.
        if (isDirectionalKey(event.keyCode) && currentFocus !is android.widget.EditText) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDirectionalKey(keyCode: Int): Boolean = when (keyCode) {
        PadNames.KEYCODE_DPAD_UP, PadNames.KEYCODE_DPAD_DOWN,
        PadNames.KEYCODE_DPAD_LEFT, PadNames.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val screen = sections.stack().peek() as? Screen
        if (screen?.onRawMotionEvent(event) == true) return true

        if (!PadNames.has(event.source, PadNames.SOURCE_JOYSTICK)) {
            return super.dispatchGenericMotionEvent(event)
        }
        router.onMotion(
            x = event.getAxisValue(PadNames.AXIS_X),
            y = event.getAxisValue(PadNames.AXIS_Y),
            hatX = event.getAxisValue(PadNames.AXIS_HAT_X),
            hatY = event.getAxisValue(PadNames.AXIS_HAT_Y),
            leftTriggerValue = event.getAxisValue(PadNames.AXIS_BRAKE),
            rightTriggerValue = event.getAxisValue(PadNames.AXIS_GAS),
            nowMs = event.eventTime,
            deviceId = event.deviceId,
            // The right stick is ABS_Z/ABS_RZ on this handheld (measured), not RX/RY.
            rightX = event.getAxisValue(PadNames.AXIS_Z),
            rightY = event.getAxisValue(PadNames.AXIS_RZ)
        )
        ticker.ensureRunning()
        return true
    }

    /** Any touch -- a finger, or the bottom-screen trackpad's cursor. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) router.onPointer()
        return super.dispatchTouchEvent(event)
    }

    private fun ringVisible(): Boolean = router.inputMode.showFocusRing

    private fun onPadAction(action: PadAction) {
        // The trailer window gets first refusal while it has control, so a
        // directional press moves the window rather than the selection behind it.
        if (trailerMode && player.isOpen) {
            if (action == PadAction.Primary) {
                trailerMode = false
                refreshHints()
                restoreTrailerFocus()
                return
            }
            if (player.onPad(action)) {
                refreshHints()
                return
            }
        }
        // Start is how control comes back without closing the trailer.
        if (action == PadAction.Menu && player.isOpen && !trailerMode) {
            trailerReturnFocus = content.findFocus()
            trailerMode = true
            content.findFocus()?.clearFocus()
            refreshHints()
            return
        }
        if (topBar.hasFocus()) {
            when (action) {
                is PadAction.Step -> { moveFocus(action.direction); return }
                PadAction.Activate -> { currentFocus?.performClick(); return }
                PadAction.Back -> { returnFocusFromUtilities(); return }
                PadAction.Primary -> if (topBar.miniPlayer.hasFocus()) { topBar.miniPlayer.onToggle(); refreshHints(); return }
                is PadAction.Section -> {
                    if (sections.switchWithin(action.delta, CONTENT_SECTION_COUNT)) showCurrent()
                    return
                }
                else -> Unit
            }
        }
        val screen = sections.stack().peek() as? Screen
        if (screen?.onPad(action) == true) return

        when (action) {
            is PadAction.Step -> moveFocus(action.direction)
            is PadAction.Page -> page(action.direction)
            is PadAction.Section -> if (sections.switchWithin(action.delta, CONTENT_SECTION_COUNT)) showCurrent()
            PadAction.Activate -> currentFocus?.performClick()
            PadAction.Back -> if (!back()) DebugLog.log("nav", "back at section root")
            PadAction.Primary -> notify("X does nothing yet")
            PadAction.Secondary -> notify("Y does nothing yet")
            PadAction.Menu -> Unit
            PadAction.Refresh -> notify("refresh")
            // The right stick and the stick clicks belong to readers; elsewhere they do nothing.
            is PadAction.Pan, is PadAction.Click -> Unit
        }
    }

    private fun handleSystemBack() {
        if (::topBar.isInitialized && topBar.hasFocus()) {
            returnFocusFromUtilities()
            return
        }
        val screen = sections.stack().peek() as? Screen
        if (screen != null && views[screen]?.let(com.pocketds.hub.ui.SidePanelView::dismissTopIn) == true) { refreshHints(); return }
        if (screen?.onSystemBack() == true) return
        if (trailerMode && player.isOpen) {
            closeTrailer()
            return
        }
        if (!back()) finish()
    }

    private fun moveFocus(direction: Direction) {
        if (topBar.hasFocus()) {
            when (direction) {
                Direction.LEFT -> topBar.moveHorizontal(-1)
                Direction.RIGHT -> topBar.moveHorizontal(1)
                Direction.DOWN -> returnFocusFromUtilities()
                Direction.UP -> Unit
            }
            return
        }
        // A focused view that is no longer on screen is not somewhere to search
        // from. Belt and braces alongside clearing focus on a screen switch.
        val from = (currentFocus ?: content.findFocus())?.takeIf { it.isShown }
        if (from == null) {
            // Nothing is focused yet, so there is nowhere to search *from* and a
            // directional press would silently do nothing. This is not a corner
            // case: a detail screen opens with only its cast cards focusable and
            // those start below the fold, so the pad appeared dead until a
            // trackpad tap gave focus somewhere. Take the first focusable in the
            // pressed direction instead.
            //
            // The screen gets first refusal, because it knows its own layout;
            // a ScrollView's own focus search picks by scroll proximity and
            // lands somewhere arbitrary.
            val screen = sections.stack().peek() as? Screen
            if (screen?.requestInitialFocus() != true) content.requestFocus()
            return
        }
        val mode = (sections.stack().peek() as? Screen)?.horizontalMode
            ?: com.pocketds.hub.input.HorizontalMode.CONFINED
        // Horizontal movement inside a row is done by adapter position, never
        // by focus search. Asking the framework is what breaks: its
        // onFocusSearchFailed scrolls while hunting for a candidate, the scroll
        // detaches the card holding focus, and Android hands focus to the first
        // focusable view in the window. See StripNav.
        if (mode == com.pocketds.hub.input.HorizontalMode.CONFINED &&
            StripNav.step(from, direction)
        ) {
            if (focusTick.allow(android.os.SystemClock.uptimeMillis())) {
                KeyHaptics.perform(from, HapticSettings.strength(this))
            }
            refreshHints()
            return
        }
        val candidate = from.focusSearch(direction.toFocusConstant())
        if (direction == Direction.UP && candidate != null &&
            generateSequence(candidate) { it.parent as? View }.any { it === topBar }
        ) {
            utilityReturnFocus = from
            if (topBar.focusFirst()) refreshHints()
            return
        }
        val next = candidate?.takeIf {
            FocusGuard.accepts(direction, screenRect(from), screenRect(it), mode)
        }
        if (candidate != null && next == null) {
            // Low volume and worth keeping: this is the only visible trace of a
            // press that deliberately did nothing, and "the pad feels dead" is
            // otherwise indistinguishable from "the guard refused a wrap".
            DebugLog.log(
                "nav",
                "focus $direction refused ${candidate.javaClass.simpleName} mode=$mode"
            )
        }
        if (next != null && next !== from && next.requestFocus()) {
            // Gated, or a held stick becomes a continuous buzz against the palm
            // rather than feedback. Written for held backspace in the sibling
            // project and it transfers unchanged.
            if (focusTick.allow(android.os.SystemClock.uptimeMillis())) {
                KeyHaptics.perform(next, HapticSettings.strength(this))
            }
            // The contextual buttons belong to whatever is selected, so moving
            // the selection has to redraw them. Without this, X still reads
            // "Stop" after the cursor moves onto an item that is already stopped.
            refreshHints()
        }
    }

    private fun returnFocusFromUtilities() {
        val remembered = utilityReturnFocus
        if (remembered != null && remembered.isShown && remembered.requestFocus()) {
            utilityReturnFocus = null
            refreshHints()
            return
        }
        utilityReturnFocus = null
        (sections.stack().peek() as? Screen)?.requestInitialFocus()
        refreshHints()
    }

    /**
     * Where a view actually is, in window coordinates.
     *
     * Window rather than local, because the two views being compared live in
     * different parents -- two rows of a vertical list, or a card and a tab.
     */
    private fun screenRect(view: View): com.pocketds.hub.input.FocusRect {
        val out = IntArray(2)
        view.getLocationInWindow(out)
        return com.pocketds.hub.input.FocusRect(
            left = out[0],
            top = out[1],
            right = out[0] + view.width,
            bottom = out[1] + view.height
        )
    }

    /**
     * L2/R2: a screenful up or down, with the selection coming along.
     *
     * This used to scroll without moving focus. The focused poster scrolled
     * away, RecyclerView detached it, and Android handed focus to the first
     * focusable in the window -- a Library grid ended up on its Sort button.
     * It also paged the nearest list, which on Discover is a horizontal row.
     * Now the nearest vertical list jumps a page and the card at the same spot
     * on screen takes focus; at either end, the last or first row does.
     */
    private fun page(direction: Direction) {
        val focused = currentFocus ?: return
        val list = generateSequence(focused.parent as? View) { it.parent as? View }
            .filterIsInstance<RecyclerView>()
            .firstOrNull { it.layoutManager?.canScrollVertically() == true } ?: return
        val spot = android.graphics.Rect()
        focused.getDrawingRect(spot)
        list.offsetDescendantRectToMyCoords(focused, spot)
        val by = if (direction == Direction.UP) -list.height else list.height
        val before = list.computeVerticalScrollOffset()
        list.scrollBy(0, by)
        val moved = list.computeVerticalScrollOffset() - before
        list.post {
            val atEnd = moved != by
            val target = list.findChildViewUnder(spot.exactCenterX(), spot.exactCenterY())
                ?.takeUnless { atEnd }
                ?: list.getChildAt(if (direction == Direction.UP) 0 else list.childCount - 1)
            target?.requestFocus()
            refreshHints()
        }
    }

    private fun Direction.toFocusConstant(): Int = when (this) {
        Direction.UP -> View.FOCUS_UP
        Direction.DOWN -> View.FOCUS_DOWN
        Direction.LEFT -> View.FOCUS_LEFT
        Direction.RIGHT -> View.FOCUS_RIGHT
    }

    // ------------------------------------------------------------- ScreenHost

    override val viewContext: Context get() = this

    override fun push(screen: Screen) {
        attach(screen)
        sections.push(screen)
        showCurrent()
    }

    override fun back(): Boolean {
        val leaving = sections.stack().peek() as? Screen
        if (!sections.back()) {
            if (sections.current < CONTENT_SECTION_COUNT) return false
            if (sections.select(lastContentSection)) showCurrent()
            return true
        }
        leaving?.let { detach(it) }
        showCurrent()
        return true
    }

    override fun switchSection(delta: Int) {
        if (sections.switchWithin(delta, CONTENT_SECTION_COUNT)) showCurrent()
    }

    override fun openOfflineManager() {
        if (sections.select(3)) showCurrent()
        while (sections.depth > 1) back()
        offlineRoot.openManager()
    }

    override fun selectJellyfinUser(id: String, name: String) {
        HubSettings.selectUser(this, id, name)
        DebugLog.log("user", "selected Jellyfin profile $name")
        // Every section keeps loaded pages and its own stack. Recreating here
        // prevents watch state from the previous profile surviving anywhere.
        recreate()
    }

    override fun notify(message: String) = statusStrip.flash(message)

    override fun playItem(itemId: String, startMode: String) {
        if (itemId.isEmpty()) {
            notify("This Jellyfin item is no longer available")
            return
        }
        if (player.isOpen) closeTrailer()
        withLocalPlan(itemId, startMode) { local ->
            push(PlayerScreen(api, itemId, startMode, local, ::ringVisible))
        }
    }

    override fun openPlaybackOptions(itemId: String, startMode: String) {
        if (itemId.isEmpty()) return
        if (player.isOpen) closeTrailer()
        withLocalPlan(itemId, startMode) { local ->
            push(PlaybackOptionsScreen(api, itemId, startMode, ::ringVisible, local))
        }
    }

    /**
     * A download plays from the file even when the hub is reachable, so before
     * resuming one this asks the hub -- for at most two seconds, since an
     * unreachable hub is the usual reason to play a download -- whether it was
     * watched further elsewhere since. It used to reopen at the position from
     * download time or the last offline session, behind a later watch on the TV.
     */
    private fun withLocalPlan(itemId: String, startMode: String, open: (PlaybackPrepareResponse?) -> Unit) {
        val repository = OfflineRepository.get(this)
        if (startMode != "resume" || repository.playbackPlan(itemId, startMode) == null) {
            open(repository.playbackPlan(itemId, startMode))
            return
        }
        localPlanCheck.launch(chromeScope) {
            val server = withTimeoutOrNull(2_000L) { api.libraryItem(itemId) }
            (server as? com.pocketds.hub.net.HubResult.Ok)?.value?.item?.takeIf { it.lastPlayedAt > 0 }?.let { item ->
                repository.adoptServerWatch(itemId, OfflineCatalogProgress.fromServer(
                    item.positionSeconds * 1_000L, item.runtimeSeconds * 1_000L, item.played, item.lastPlayedAt
                ))
            }
            open(repository.playbackPlan(itemId, startMode))
        }
    }

    override fun playPrepared(plan: PlaybackPrepareResponse) {
        push(PlayerScreen(api, plan.item.id, "resume", plan, ::ringVisible))
    }

    override fun downloadItem(item: LibraryItem, seasonId: String) {
        if (item.type == "series") {
            push(OfflineSelectionScreen(api, item.id, item.title, seasonId, ::ringVisible))
            return
        }
        if (item.type != "movie" && item.type != "episode") {
            notify("Only movies and episodes can be stored offline")
            return
        }
        if (!OfflineRepository.get(this).selectedStorageAvailable()) {
            notify("Choose an available download location in Settings")
            return
        }
        val batchKey = "offline-${System.currentTimeMillis()}-${item.id.take(8)}"
        chromeScope.launch {
            when (val result = api.prepareOffline(OfflinePrepareBody(
                batchKey = batchKey,
                seriesId = item.seriesId,
                items = listOf(OfflinePrepareItem("$batchKey-item", item.id))
            ))) {
                is com.pocketds.hub.net.HubResult.Ok -> {
                    val label = item.seriesTitle.ifBlank { item.title }
                    val count = OfflineRepository.get(this@HubActivity).enqueue(
                        label, item.seriesId, result.value.items
                    )
                    if (count > 0) {
                        OfflineDownloadService.start(this@HubActivity)
                        notify("Added ${item.title} to downloads")
                    } else notify("${item.title} is already downloaded or queued")
                }
                is com.pocketds.hub.net.HubResult.Failed -> notify(result.message)
            }
        }
    }

    private fun requestDownloadNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2401)
    }

    override fun enterPictureInPicture(source: View): Boolean {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            notify("Picture-in-picture is unavailable on this device")
            return false
        }
        val sourceBounds = Rect()
        source.getGlobalVisibleRect(sourceBounds)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setSourceRectHint(sourceBounds)
            .build()
        enteringPictureInPicture = true
        return runCatching { enterPictureInPictureMode(params) }
            .onFailure {
                enteringPictureInPicture = false
                notify("Could not open picture-in-picture")
            }
            .getOrDefault(false)
            .also { if (!it) enteringPictureInPicture = false }
    }

    /** After focus lands: the hints and the page's artwork both follow the selection. */
    private val focusSettled = Runnable {
        refreshHints()
        showArtwork()
    }

    override fun pageArtworkChanged() = showArtwork()

    override fun prefetchArtwork(paths: Collection<String>) {
        if (ambient != null && paths.isNotEmpty()) ArtworkColors.shared(this, api).prefetch(paths)
    }

    /**
     * Glass: the page shows the artwork the screen in front reports, tinted
     * with its colours, and keeps the last artwork when the screen reports
     * none ([PageArtwork]). Until the hub has the colours the page uses the
     * neutral tint, as GLASS_PLAN.md asks, with the picture already showing.
     */
    private fun showArtwork() {
        val ambient = ambient ?: return
        val top = sections.stack().peek() as? Screen ?: return
        val path = PageArtwork.next(shownArtwork, top.pageArtwork) ?: return
        val colours = ArtworkColors.shared(this, api)
        paletteWait?.let { colours.cancel(shownArtwork, it) }
        paletteWait = null
        shownArtwork = path
        val known = colours.peek(path)
        // Over video the page itself is hidden and only the controls carry its
        // colours: they keep the ones they have until the playing title's
        // arrive, rather than going grey between.
        if (known != null || !top.immersive) tintPage(ambient, path, known ?: ArtworkPalette.NEUTRAL)
        if (known != null) return
        val wait: (ArtworkPalette) -> Unit = { palette -> if (shownArtwork == path) tintPage(ambient, path, palette) }
        paletteWait = wait
        colours.request(path, wait)
    }

    private fun tintPage(ambient: AmbientLayerView, path: String, palette: ArtworkPalette) {
        ambient.show(path, palette)
        if (palette == pagePalette) return
        pagePalette = palette
        // The bars, the sheets and every glass control follow the page from here.
        GlassPage.set(this, palette)
    }

    override fun refreshHints() {
        if (!::player.isInitialized) return
        if (trailerMode && player.isOpen) {
            hintBar.setHints(trailerHints())
            player.setControlMode(true)
            return
        }
        player.setControlMode(false)
        val top = sections.stack().peek() as? Screen
        if (top?.immersive == true) {
            hintBar.setHints(emptyList())
            return
        }
        if (topBar.hasFocus()) {
            if (topBar.miniPlayer.hasFocus()) {
                hintBar.setHints(listOf(ButtonHint.activate("Open"), ButtonHint.primary(topBar.miniPlayer.toggleLabel),
                    ButtonHint.back("Return to content")))
                return
            }
            val action = currentFocus?.contentDescription?.toString().orEmpty().removeSuffix(", selected")
            hintBar.setHints(listOf(ButtonHint.activate(action.ifBlank { "Open" }),
                ButtonHint.back("Return to content")))
            return
        }
        val hints = (top?.hints() ?: emptyList()).toMutableList()
        // One chip per button, in the order Start is actually handled: an open
        // trailer takes it first, then the screen, and only then the rail. The
        // download picker showed "Start · Select all" and "Start · Expand menu".
        if (player.isOpen) {
            hints.removeAll { it.action == PadAction.Menu }
            hints.add(ButtonHint("⏵", "Trailer", PadAction.Menu))
        }
        hintBar.setHints(hints)
    }

    // ------------------------------------------------------------- lifecycle

    override fun onResume() {
        super.onResume()
        com.pocketds.hub.reader.ReadingProgress.get(this).requestSync(immediate = true)
        if (!isInPictureInPictureMode) pictureInPictureSessionActive = false
        if (::player.isInitialized) player.resumePlayback()
        (sections.stack().peek() as? Screen)?.onShow()
        startNotificationBadgePolling()
    }

    override fun onPause() {
        // A backgrounded screen must not keep a frame callback alive, and a
        // trailer must not keep playing audio over whatever is now in front.
        ticker.stop()
        router.reset()
        notificationBadge.stop()
        if (::player.isInitialized) player.pausePlayback()
        if (!isInPictureInPictureMode && !enteringPictureInPicture) {
            (sections.stack().peek() as? Screen)?.let {
                it.onAppBackgrounded()
                it.onHide()
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        notificationBadge.stop()
        // The colours client outlives this Activity (a look change rebuilds it);
        // an ask still out must not keep the old window and its page alive.
        paletteWait?.let { ArtworkColors.shared(this, api).cancel(shownArtwork, it) }
        paletteWait = null
        chromeScope.cancel()
        super.onDestroy()
    }

    private fun startNotificationBadgePolling() {
        notificationBadge.stop()
        if (!HubSettings.isConfigured(this)) return
        // Started in onResume and stopped in onPause, so resumed is visible.
        notificationBadge.start(chromeScope, { true }) {
            when (val result = api.notifications(NotificationSettings.limits(this@HubActivity))) {
                is com.pocketds.hub.net.HubResult.Ok -> {
                    val unread = NotificationReadStore(this@HubActivity).observe(result.value.sections)
                    topBar.setBadge(unread.size + com.pocketds.hub.settings.LocalAlerts.unread(this@HubActivity))
                    com.pocketds.hub.state.PollOutcome(ok = true)
                }
                is com.pocketds.hub.net.HubResult.Failed -> com.pocketds.hub.state.PollOutcome(ok = false)
            }
        }
    }

    override fun onStop() {
        // Expanding PiP returns directly to onResume. Dismissing its window
        // stops this still-active task instead; close the Jellyfin session so
        // audio/transcoding cannot continue after the window disappears.
        if (pictureInPictureSessionActive && !isChangingConfigurations) {
            PlaybackService.stop(this)
            finishAndRemoveTask()
        }
        super.onStop()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPictureInPicture = false
        if (isInPictureInPictureMode) pictureInPictureSessionActive = true
        (sections.stack().peek() as? Screen)?.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val NOTIFICATIONS_SECTION = 5
        const val CONTENT_SECTION_COUNT = 5
        const val PAGE_TITLE_DP = 40f
        const val STATE_SECTION = "current_section"
    }
}
