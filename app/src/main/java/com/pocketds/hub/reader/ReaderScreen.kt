package com.pocketds.hub.reader

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlin.math.roundToInt

/** Shared R1 shell. It deliberately accepts only a deterministic fixture engine. */
class ReaderScreen(
    private val engine: FakeReaderEngine,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title: String = engine.title
    override val immersive = true
    override val focusOnShow = false

    private lateinit var host: ScreenHost
    private lateinit var root: FrameLayout
    private lateinit var contentLayer: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var position: TextView
    private lateinit var previewBubble: TextView
    private lateinit var sheetLayer: FrameLayout
    private lateinit var bookmarkButton: TextView
    private lateinit var audioButton: TextView
    private lateinit var colors: PocketColors

    private val controller = ReaderController(engine.profile)
    private val focusGraph = ReaderFocusGraph(engine.profile)
    private val positions = ReaderPositionState(engine.locator())
    private val controls = linkedMapOf<ReaderControl, View>()
    private val sheetOptions = mutableListOf<View>()
    private var focusedControl = focusGraph.initial
    private var sheetFocus = 0
    private var preview: ReaderPreview? = null
    private var seekingByTouch = false
    private var bookmarked = false
    private var audioPlaying = false
    private var twoColumns = true
    private var pageTheme = if (engine.profile in setOf(ReaderProfile.BOOK, ReaderProfile.READ_ALONG)) {
        PageTheme.SEPIA
    } else {
        PageTheme.DARK
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        root = FrameLayout(host.viewContext).apply { setBackgroundColor(Color.BLACK) }
        contentLayer = FrameLayout(host.viewContext).apply {
            isClickable = true
            setOnTouchListener(::onPageTouch)
        }
        root.addView(contentLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        buildTopBar()
        buildBottomBar()
        previewBubble = TextView(host.viewContext).apply {
            visibility = View.GONE
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(colors.primaryText)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = Styler.cardBackground(context, colors, 10f)
        }
        root.addView(previewBubble, FrameLayout.LayoutParams(dp(310), dp(58), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(132)
        })
        sheetLayer = FrameLayout(host.viewContext).apply {
            visibility = View.GONE
            setBackgroundColor(0x77000000)
        }
        root.addView(sheetLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        setControlsVisible(false)
        render()
        return root
    }

    override fun onShow() {
        if (::root.isInitialized) render()
    }

    override fun onHide() = rememberPosition()

    override fun onDestroyView() {
        rememberPosition()
        controls.clear()
        sheetOptions.clear()
    }

    override fun onAppBackgrounded() = rememberPosition()

    override fun onPad(action: PadAction): Boolean {
        execute(controller.dispatch(action))
        return true
    }

    override fun onSystemBack(): Boolean {
        val command = controller.dispatch(PadAction.Back)
        if (command == ReaderCommand.Exit) return false
        execute(command)
        return true
    }

    private fun execute(command: ReaderCommand) {
        when (command) {
            is ReaderCommand.ShowOverlay -> when (command.overlay) {
                ReaderOverlay.CONTROLS -> {
                    cancelPreview()
                    showControls()
                }
                ReaderOverlay.NAVIGATOR,
                ReaderOverlay.APPEARANCE,
                ReaderOverlay.AUDIO,
                ReaderOverlay.CONFLICT -> showSheet(command.overlay)
                ReaderOverlay.HIDDEN -> {
                    cancelPreview()
                    setControlsVisible(false)
                }
            }
            ReaderCommand.HideControls -> {
                cancelPreview()
                setControlsVisible(false)
            }
            ReaderCommand.Exit -> host.back()
            is ReaderCommand.Navigate -> navigate(command.direction)
            is ReaderCommand.MoveFocus -> moveFocus(command.direction)
            is ReaderCommand.TurnPage -> movePage(command.delta)
            is ReaderCommand.ChangeChapter -> movePage(command.delta * 4)
            is ReaderCommand.Zoom -> host.notify(if (command.delta > 0) "Reader zoom increased" else "Reader zoom decreased")
            ReaderCommand.Advance -> movePage(1)
            ReaderCommand.ToggleBookmark -> toggleBookmark()
            ReaderCommand.ActivateFocused -> activateFocused()
        }
    }

    private fun buildTopBar() {
        topBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0xE51A1B20.toInt())
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, dp(66), Gravity.TOP))
        topBar.addView(control(ReaderControl.CLOSE, "×", "Close reader", { host.back() }, dp(58)))
        topBar.addView(TextView(host.viewContext).apply {
            text = engine.title
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        bookmarkButton = control(ReaderControl.BOOKMARK, "☆", "Toggle bookmark", ::toggleBookmark, dp(58))
        topBar.addView(bookmarkButton)
        topBar.addView(control(ReaderControl.NAVIGATOR, "☷", "Open navigator", {
            execute(controller.openOverlay(ReaderOverlay.NAVIGATOR))
        }, dp(58)))
        topBar.addView(control(ReaderControl.APPEARANCE, "Aa", "Reading appearance", {
            execute(controller.openOverlay(ReaderOverlay.APPEARANCE))
        }, dp(62), 18f))
    }

    private fun buildBottomBar() {
        bottomBar = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(5))
            setBackgroundColor(0xE51A1B20.toInt())
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(MATCH, dp(120), Gravity.BOTTOM))
        bottomBar.addView(LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(control(ReaderControl.PREVIOUS, "‹", "Previous page", { movePage(-1) }, dp(54), 31f))
            seek = SeekBar(context).apply {
                max = 1_000
                progress = progressFor(engine.locator())
                contentDescription = "Reading position"
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                setOnFocusChangeListener { view, focused ->
                    FocusDecorator.refresh(view, focused && ringVisible())
                    if (focused) focusedControl = ReaderControl.SCRUBBER
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        seekingByTouch = true
                    }

                    override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val candidate = engine.preview(value / seekBar.max.toDouble())
                        preview = candidate
                        positions.beginPreview(candidate.locator)
                        previewBubble.text = "${candidate.title}\nSaved · ${positions.saved.label}"
                        previewBubble.visibility = View.VISIBLE
                        render(candidate.pageIndex, previewing = true)
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        preview?.let {
                            engine.seek(it.locator)
                            positions.commitPreview()
                        }
                        preview = null
                        seekingByTouch = false
                        previewBubble.visibility = View.GONE
                        rememberPosition()
                        render()
                    }
                })
            }
            controls[ReaderControl.SCRUBBER] = seek
            addView(seek, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            })
            position = TextView(context).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = dp(112)
            }
            addView(position, LinearLayout.LayoutParams(dp(122), MATCH))
            addView(control(ReaderControl.NEXT, "›", "Next page", { movePage(1) }, dp(54), 31f))
            if (engine.profile == ReaderProfile.READ_ALONG) {
                audioButton = control(ReaderControl.AUDIO, "♪", "Read-along audio", {
                    execute(controller.openOverlay(ReaderOverlay.AUDIO))
                }, dp(58), 23f)
                addView(audioButton)
            }
        }, LinearLayout.LayoutParams(MATCH, dp(58)))
        bottomBar.addView(TextView(host.viewContext).apply {
            text = "A choose  ·  B hide  ·  X bookmark  ·  Y navigator  ·  Select appearance"
            textSize = 10.5f
            setTextColor(0xFFB8B8C0.toInt())
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun control(
        id: ReaderControl,
        glyph: String,
        label: String,
        click: () -> Unit,
        size: Int,
        glyphSize: Float = 25f
    ): TextView = TextView(host.viewContext).apply {
        text = glyph
        textSize = glyphSize
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = label
        background = readerControlBackground()
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        setOnFocusChangeListener { view, focused ->
            FocusDecorator.refresh(view, focused && ringVisible())
            if (focused) focusedControl = id
        }
        activateOnTap(click)
        controls[id] = this
        layoutParams = LinearLayout.LayoutParams(size, MATCH)
    }

    private fun showControls() {
        dismissSheet()
        setControlsVisible(true)
        focusedControl = focusGraph.initial
        controls[focusedControl]?.post { controls[focusedControl]?.requestFocus() }
    }

    private fun setControlsVisible(visible: Boolean) {
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) {
            dismissSheet()
            root.findFocus()?.clearFocus()
        }
    }

    private fun moveFocus(direction: Direction) {
        if (sheetLayer.visibility == View.VISIBLE) {
            moveSheetFocus(direction)
            return
        }
        focusedControl = focusGraph.move(focusedControl, direction)
        controls[focusedControl]?.requestFocus()
    }

    private fun activateFocused() {
        if (sheetLayer.visibility == View.VISIBLE) {
            sheetOptions.getOrNull(sheetFocus)?.performClick()
        } else {
            controls[focusedControl]?.performClick()
        }
    }

    private fun navigate(direction: Direction) {
        when (direction) {
            Direction.LEFT -> movePage(-1)
            Direction.RIGHT -> movePage(1)
            Direction.UP -> if (engine.profile == ReaderProfile.READ_ALONG) {
                host.notify("Read-along speed ${if (audioPlaying) "1.0×" else "paused"}")
            } else Unit
            Direction.DOWN -> if (engine.profile == ReaderProfile.READ_ALONG) {
                execute(controller.openOverlay(ReaderOverlay.AUDIO))
            } else Unit
        }
    }

    private fun movePage(delta: Int) {
        if (delta == 0 || !engine.move(delta)) {
            host.notify(if (delta < 0) "Beginning of publication" else "End of publication")
            return
        }
        positions.acceptSettled(positions.generation, engine.locator())
        rememberPosition()
        render()
    }

    private fun beginNavigatorPreview(progression: Double) {
        val candidate = engine.preview(progression)
        positions.beginPreview(candidate.locator)
        preview = candidate
        previewBubble.text = "${candidate.title}\nSaved · ${positions.saved.label}"
        previewBubble.visibility = View.VISIBLE
        render(candidate.pageIndex, previewing = true)
        showPreviewDecision(candidate)
    }

    private fun showPreviewDecision(candidate: ReaderPreview) {
        setControlsVisible(true)
        sheetLayer.removeAllViews()
        sheetOptions.clear()
        sheetFocus = 0
        sheetLayer.visibility = View.VISIBLE
        val panel = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xFA202126.toInt())
        }
        sheetLayer.addView(panel, FrameLayout.LayoutParams(dp(430), MATCH, Gravity.END))
        panel.sheetHeading("Preview location", "${candidate.title}. Your saved position is still ${positions.saved.label}.")
        panel.sheetOption("Go here", "Replace the saved position with ${candidate.locator.label}") {
            engine.seek(candidate.locator)
            positions.commitPreview()
            preview = null
            previewBubble.visibility = View.GONE
            rememberPosition()
            returnToControls()
            render()
        }
        panel.sheetOption("Return to saved", positions.saved.label) {
            cancelPreview()
            returnToControls()
        }
        sheetOptions.firstOrNull()?.post { sheetOptions.firstOrNull()?.requestFocus() }
    }

    private fun cancelPreview() {
        if (preview == null) return
        positions.cancelPreview()
        preview = null
        previewBubble.visibility = View.GONE
        render()
    }

    private fun returnToControls() {
        execute(controller.openOverlay(ReaderOverlay.CONTROLS))
    }

    private fun jumpTo(progression: Double) {
        val candidate = engine.preview(progression)
        positions.beginPreview(candidate.locator)
        engine.seek(candidate.locator)
        positions.commitPreview()
        rememberPosition()
        returnToControls()
        render()
    }

    private fun toggleBookmark() {
        bookmarked = !bookmarked
        bookmarkButton.text = if (bookmarked) "★" else "☆"
        bookmarkButton.contentDescription = if (bookmarked) "Remove bookmark" else "Add bookmark"
        host.notify(if (bookmarked) "Page bookmarked" else "Bookmark removed")
    }

    private fun render(pageIndex: Int = engine.currentIndex, previewing: Boolean = false) {
        val model = engine.renderModel(pageIndex)
        contentLayer.removeAllViews()
        when (model.profile) {
            ReaderProfile.COMIC, ReaderProfile.MANGA -> renderComic(model)
            ReaderProfile.BOOK, ReaderProfile.READ_ALONG -> renderBook(model)
        }
        position.text = if (previewing) "Preview ${model.pageIndex + 1} / ${model.pageCount}" else engine.locator().label
        if (!seekingByTouch) seek.progress = progressFor(engine.locator())
    }

    private fun renderComic(model: ReaderRenderModel) {
        contentLayer.setBackgroundColor(Color.BLACK)
        contentLayer.addView(
            FakeComicPageView(host.viewContext, model, model.profile == ReaderProfile.MANGA),
            FrameLayout.LayoutParams(MATCH, MATCH).apply {
                setMargins(dp(74), dp(32), dp(74), dp(32))
            }
        )
    }

    private fun renderBook(model: ReaderRenderModel) {
        val background = when (pageTheme) {
            PageTheme.WHITE -> 0xFFF8F6F0.toInt()
            PageTheme.SEPIA -> 0xFFF5E8CB.toInt()
            PageTheme.DARK -> 0xFF202124.toInt()
        }
        val foreground = if (pageTheme == PageTheme.DARK) 0xFFE8E5DF.toInt() else 0xFF5B4935.toInt()
        contentLayer.setBackgroundColor(background)
        val page = FrameLayout(host.viewContext)
        val columns = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(66), dp(76), dp(66), dp(62))
            if (twoColumns) {
                addView(
                    bookText(model.firstColumn, foreground, model.highlightedToken),
                    LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginEnd = dp(30) }
                )
                addView(
                    bookText(model.secondColumn, foreground, model.highlightedToken),
                    LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(30) }
                )
            } else {
                addView(
                    bookText("${model.firstColumn} ${model.secondColumn}", foreground, model.highlightedToken),
                    LinearLayout.LayoutParams(MATCH, MATCH)
                )
            }
        }
        page.addView(columns, FrameLayout.LayoutParams(MATCH, MATCH))
        page.addView(TextView(host.viewContext).apply {
            text = model.chapter
            textSize = 11f
            setTextColor(foreground)
            alpha = 0.72f
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(31) })
        page.addView(TextView(host.viewContext).apply {
            text = "${model.pageIndex + 1} / ${model.pageCount}"
            textSize = 11f
            setTextColor(foreground)
            alpha = 0.78f
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(42)
            bottomMargin = dp(24)
        })
        if (model.profile == ReaderProfile.READ_ALONG) {
            page.addView(TextView(host.viewContext).apply {
                text = if (audioPlaying) "Narrating · ${model.highlightedToken}" else "Read along paused"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                this.background = GradientDrawable().apply {
                    cornerRadius = Styler.dp(context, 14f)
                    setColor(0xCC6B442D.toInt())
                }
            }, FrameLayout.LayoutParams(WRAP, dp(36), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            })
        }
        contentLayer.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun bookText(value: String, foreground: Int, highlighted: String): TextView =
        TextView(host.viewContext).apply {
            text = highlight(value, highlighted)
            textSize = 19f
            setLineSpacing(0f, 1.28f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            setTextColor(foreground)
            gravity = Gravity.TOP
        }

    private fun highlight(value: String, token: String): CharSequence {
        if (!audioPlaying || token.isBlank()) return value
        val start = value.indexOf(token, ignoreCase = true)
        if (start < 0) return value
        return SpannableString(value).apply {
            setSpan(BackgroundColorSpan(0xFFFFC857.toInt()), start, start + token.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(0xFF201A12.toInt()), start, start + token.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun showSheet(type: ReaderOverlay) {
        setControlsVisible(true)
        sheetLayer.removeAllViews()
        sheetOptions.clear()
        sheetFocus = 0
        sheetLayer.visibility = View.VISIBLE
        val panel = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            setBackgroundColor(0xFA202126.toInt())
        }
        sheetLayer.addView(panel, FrameLayout.LayoutParams(dp(430), MATCH, Gravity.END))
        when (type) {
            ReaderOverlay.NAVIGATOR -> buildNavigator(panel)
            ReaderOverlay.APPEARANCE -> buildAppearance(panel)
            ReaderOverlay.AUDIO -> buildAudio(panel)
            ReaderOverlay.CONFLICT -> buildConflict(panel)
            else -> Unit
        }
        sheetOptions.firstOrNull()?.post { sheetOptions.firstOrNull()?.requestFocus() }
    }

    private fun buildNavigator(panel: LinearLayout) {
        panel.sheetHeading("Navigator", "Previewing a location does not replace the saved position.")
        panel.sheetOption("Table of contents", "${engine.renderModel().chapter} selected") {
            host.notify("The real table of contents arrives with the publication engine")
        }
        panel.sheetOption("Preview 25%", "Saved position remains ${positions.saved.label}") { beginNavigatorPreview(0.25) }
        panel.sheetOption("Preview 50%", "Saved position remains ${positions.saved.label}") { beginNavigatorPreview(0.50) }
        panel.sheetOption("Preview 75%", "Saved position remains ${positions.saved.label}") { beginNavigatorPreview(0.75) }
        panel.sheetOption("Bookmarks", if (bookmarked) "This page is bookmarked" else "No fixture bookmark") {
            toggleBookmark()
        }
        panel.sheetOption("Progress conflict example", "Compare this device with a newer server location") {
            execute(controller.openOverlay(ReaderOverlay.CONFLICT))
        }
    }

    private fun buildAppearance(panel: LinearLayout) {
        panel.sheetHeading("Appearance", "Changes apply without reopening the publication.")
        if (engine.profile in setOf(ReaderProfile.BOOK, ReaderProfile.READ_ALONG)) {
            panel.sheetOption("White page", if (pageTheme == PageTheme.WHITE) "Selected" else "") {
                pageTheme = PageTheme.WHITE; render(); showSheet(ReaderOverlay.APPEARANCE)
            }
            panel.sheetOption("Sepia page", if (pageTheme == PageTheme.SEPIA) "Selected" else "") {
                pageTheme = PageTheme.SEPIA; render(); showSheet(ReaderOverlay.APPEARANCE)
            }
            panel.sheetOption("Dark page", if (pageTheme == PageTheme.DARK) "Selected" else "") {
                pageTheme = PageTheme.DARK; render(); showSheet(ReaderOverlay.APPEARANCE)
            }
            panel.sheetOption("Columns", if (twoColumns) "Two columns" else "One column") {
                twoColumns = !twoColumns; render(); showSheet(ReaderOverlay.APPEARANCE)
            }
            panel.sheetOption("Typography", "Bookerly-like serif · 19 sp · comfortable spacing") {
                host.notify("Font and spacing controls arrive with the EPUB navigator")
            }
        } else {
            panel.sheetOption("Fit screen", "Selected") { host.notify("Fit screen") }
            panel.sheetOption("Fit width", "Keep manual zoom per orientation") { host.notify("Fit width") }
            panel.sheetOption("Reading direction", if (engine.profile == ReaderProfile.MANGA) "Right to left" else "Left to right") {
                host.notify("Direction remains scoped to this series")
            }
            panel.sheetOption("Crop borders", "Off") { host.notify("Crop preview") }
            panel.sheetOption("Color correction", "White balance · vibrance · gamma") { host.notify("Color correction preview") }
        }
    }

    private fun buildAudio(panel: LinearLayout) {
        panel.sheetHeading("Read-along audio", "Audio and text share one stable reading position.")
        panel.sheetOption(if (audioPlaying) "Pause" else "Play", "Narration at 1.0×") {
            audioPlaying = !audioPlaying
            render()
            showSheet(ReaderOverlay.AUDIO)
        }
        panel.sheetOption("Speed", "1.0×") { host.notify("Playback speed 1.0×") }
        panel.sheetOption("Sleep timer", "Off") { host.notify("Sleep timer is off") }
        panel.sheetOption("Previous chapter", engine.renderModel().chapter) { movePage(-4) }
        panel.sheetOption("Next chapter", engine.renderModel().chapter) { movePage(4) }
    }

    private fun buildConflict(panel: LinearLayout) {
        panel.sheetHeading("Reading position changed", "Choose explicitly; opening this sheet changes neither position.")
        panel.sheetOption("Continue here", "Pocket DS · ${positions.saved.label} · now") {
            returnToControls()
        }
        panel.sheetOption("Use server position", "Other device · Page 18 · 1 day ago") { jumpTo(0.72) }
        panel.sheetOption("Keep both bookmarks", "Save both locations and continue here") {
            bookmarked = true; bookmarkButton.text = "★"; returnToControls()
        }
    }

    private fun LinearLayout.sheetHeading(title: String, subtitle: String) {
        addView(TextView(context).apply {
            text = title
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        addView(TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(0xFFB8B8C0.toInt())
            setPadding(0, dp(4), 0, dp(15))
        })
    }

    private fun LinearLayout.sheetOption(title: String, detail: String, click: () -> Unit) {
        val option = TextView(context).apply {
            text = if (detail.isBlank()) title else "$title\n$detail"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = darkCardBackground()
            contentDescription = if (detail.isBlank()) title else "$title. $detail"
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            activateOnTap(click)
        }
        sheetOptions += option
        addView(option, LinearLayout.LayoutParams(MATCH, dp(66)).apply { bottomMargin = dp(8) })
    }

    private fun moveSheetFocus(direction: Direction) {
        if (sheetOptions.isEmpty()) return
        sheetFocus = when (direction) {
            Direction.UP, Direction.LEFT -> (sheetFocus - 1).coerceAtLeast(0)
            Direction.DOWN, Direction.RIGHT -> (sheetFocus + 1).coerceAtMost(sheetOptions.lastIndex)
        }
        sheetOptions[sheetFocus].requestFocus()
    }

    private fun dismissSheet() {
        if (!::sheetLayer.isInitialized) return
        sheetLayer.visibility = View.GONE
        sheetLayer.removeAllViews()
        sheetOptions.clear()
    }

    private fun rememberPosition() {
        if (!::host.isInitialized) return
        ReaderLabProgressStore.remember(
            host.viewContext,
            HubSettings.userId(host.viewContext),
            engine.profile,
            engine.locator()
        )
        positions.takePendingProgress()
    }

    private fun onPageTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        if (sheetLayer.visibility == View.VISIBLE) return true
        if (topBar.visibility == View.VISIBLE) {
            execute(controller.dispatch(PadAction.Menu))
            return true
        }
        when {
            event.x < view.width / 3f -> movePage(-1)
            event.x > view.width * 2f / 3f -> movePage(1)
            else -> execute(controller.dispatch(PadAction.Menu))
        }
        return true
    }

    private fun progressFor(locator: ReaderLocator): Int =
        (locator.progression * 1_000).roundToInt().coerceIn(0, 1_000)

    private fun readerControlBackground(): StateListDrawable {
        fun face(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 10f)
            setColor(fill)
            if (stroke > 0) setStroke(dp(2), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(0x443CDBC9))
            addState(intArrayOf(android.R.attr.state_focused), face(0x2216B9A8, colors.focusRing))
            addState(intArrayOf(), face(Color.TRANSPARENT))
        }
    }

    private fun darkCardBackground(): StateListDrawable {
        fun face(fill: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
            cornerRadius = Styler.dp(host.viewContext, 10f)
            setColor(fill)
            if (stroke > 0) setStroke(dp(3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(0xFF3B3D44.toInt()))
            addState(intArrayOf(android.R.attr.state_focused), face(0xFF263E3B.toInt(), colors.focusRing))
            addState(intArrayOf(), face(0xFF303137.toInt()))
        }
    }

    private fun dp(value: Int): Int = Styler.dpInt(host.viewContext, value.toFloat())

    private enum class PageTheme { WHITE, SEPIA, DARK }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

private class FakeComicPageView(
    context: android.content.Context,
    private val model: ReaderRenderModel,
    private val rightToLeft: Boolean
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelColors = intArrayOf(
        0xFF4E8DA8.toInt(),
        0xFFD8A047.toInt(),
        0xFFAD5B63.toInt(),
        0xFF536A4B.toInt(),
        0xFF735C91.toInt(),
        0xFF35746D.toInt()
    )

    init {
        contentDescription = "${if (rightToLeft) "Manga" else "Comic"} fixture, ${model.pageIndex + 1} of ${model.pageCount}"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFFF2EEE4.toInt())
        val gap = width * 0.018f
        val outer = width * 0.035f
        val top = height * 0.055f
        val footer = height * 0.075f
        val usableHeight = height - top - footer - gap * 2
        val usableWidth = width - outer * 2 - gap
        val cellW = usableWidth / 2f
        val cellH = usableHeight / 3f
        for (row in 0..2) {
            for (logicalColumn in 0..1) {
                val column = if (rightToLeft) 1 - logicalColumn else logicalColumn
                val index = row * 2 + logicalColumn
                val left = outer + column * (cellW + gap)
                val rect = RectF(left, top + row * (cellH + gap), left + cellW, top + row * (cellH + gap) + cellH)
                paint.style = Paint.Style.FILL
                paint.color = panelColors[(index + model.pageIndex) % panelColors.size]
                canvas.drawRoundRect(rect, 8f, 8f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = 0xFF171717.toInt()
                canvas.drawRoundRect(rect, 8f, 8f, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textSize = (height * 0.035f).coerceAtLeast(20f)
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText("${if (rightToLeft) "←" else "→"} ${index + 1}", rect.left + 18f, rect.top + paint.textSize + 12f, paint)
                paint.alpha = 155
                canvas.drawCircle(rect.centerX(), rect.centerY(), cellH * 0.18f, paint)
                paint.alpha = 255
            }
        }
        paint.color = 0xFF26201A.toInt()
        paint.textSize = (height * 0.032f).coerceAtLeast(18f)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(
            "${model.title}  ·  ${if (rightToLeft) "RTL" else "LTR"}  ·  ${model.pageIndex + 1}/${model.pageCount}",
            outer,
            height - footer * 0.25f,
            paint
        )
    }
}
