package com.pocketds.hub.screens.settings

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.playback.CastTransferPolicy
import com.pocketds.hub.playback.NextEpisodeTiming
import com.pocketds.hub.playback.PlaybackEnhancements
import com.pocketds.hub.playback.PlayerLabels
import com.pocketds.hub.playback.SubtitleLook
import com.pocketds.hub.playback.SubtitleLooks
import com.pocketds.hub.playback.SubtitleSize
import com.pocketds.hub.playback.SubtitleStyle
import com.pocketds.hub.screens.home.HomeRows
import com.pocketds.hub.screens.system.PadTestScreen
import com.pocketds.hub.settings.AccentPreset
import com.pocketds.hub.settings.DomainPreferences
import com.pocketds.hub.settings.HomeRowSettings
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Look
import com.pocketds.hub.settings.LookSettings
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.settings.SubtitleSettings
import com.pocketds.hub.settings.ThemeSettings
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SettingsCard
import com.pocketds.hub.ui.SideNavView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.SwatchRowView
import com.pocketds.hub.ui.SwitchRowView
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.UtilityRowView
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Settings as two panes: the sections down the left, with the sliding blob,
 * and the chosen section's cards on the right. Moving down the sections shows
 * each one as you pass; Right goes into it.
 *
 * Appearance picks the look (Glass, or Classic with its light and dark
 * themes) and a colour per media type, Home orders its
 * rows, Libraries orders the libraries on both sides for every device (#15),
 * Playback holds what used to be in the player (skip distance, the next
 * episode, intros), and Subtitles has a live preview drawn by the player's own
 * subtitle renderer, so what you choose is what you get.
 */
@androidx.media3.common.util.UnstableApi
class SettingsScreen(
    private val api: HubApi?,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Settings"
    override val showsOwnTitle = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Saves of a library order, which hiding Settings must not cancel half way. */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    /** Settings › Libraries; none without a hub to ask. */
    private var libraryOrder: LibraryOrderSection? = null
    private lateinit var colors: PocketColors
    private lateinit var overlay: ChoiceOverlay
    private lateinit var nav: SideNavView
    private lateinit var pane: LinearLayout
    private lateinit var scroll: FocusScrollView
    private var section = SECTION_APPEARANCE
    /** Library rows Settings › Home can offer, by view id and name. */
    private var libraries: List<Pair<String, String>> = emptyList()
    /** The Home row whose X/Y move it, when one is focused. */
    private var focusedHomeRow: String? = null
    private var subtitlePreviewControls = true

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        libraryOrder = api?.let { LibraryOrderSection(host, it, colors, ringVisible, scope, saveScope, ::render) }
        // Glass (#11): the prototype's Settings, a 150dp list of places with their icons beside glass cards.
        val glass = Theme.onGlass(colors)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val columns = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.HORIZONTAL
            if (glass) setPadding(dp(22), dp(8), dp(22), 0) else setPadding(dp(24), dp(10), dp(24), 0)
        }
        val left = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Settings"; setTextColor(colors.primaryText)
                if (glass) {
                    textSize = 18f; typeface = Type.display(context, 800); includeFontPadding = false
                    setPadding(dp(6), dp(4), 0, dp(10))
                } else {
                    typeRole(Type.Role.SCREEN)
                    setPadding(dp(4), dp(2), 0, dp(12))
                }
            })
            nav = SideNavView(context, colors, ringVisible).apply {
                setItems(SECTIONS.map { SideNavView.Item(it.first, it.second, if (glass) SECTION_ICONS[it.first] else null) }, section)
                onPick = { id ->
                    section = id
                    if (id == SECTION_LIBRARIES) libraryOrder?.load()
                    render()
                }
            }
            addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        columns.addView(left, LinearLayout.LayoutParams(dp(if (glass) 150 else 178), MATCH).apply { marginEnd = dp(if (glass) 12 else 18) })
        pane = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(3), dp(4), dp(3), dp(20))
        }
        scroll = FocusScrollView(host.viewContext).apply { clipToPadding = false; clipChildren = false; addView(pane) }
        columns.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        root.addView(columns, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        render()
        return root
    }

    override fun onShow() {
        if (section == SECTION_LIBRARIES) libraryOrder?.load()
        render()
        if (libraries.isEmpty() && api != null) scope.launch {
            (api.library() as? HubResult.Ok)?.value?.views?.let { views ->
                libraries = views.map { it.id to it.name }
                if (section == SECTION_HOME) render()
            }
        }
    }

    override fun onHide() {
        if (::overlay.isInitialized) overlay.dismiss()
        // A library row still lifted is put down where it is, and saves.
        libraryOrder?.finish()
        scope.coroutineContext.cancelChildren()
    }

    /** The system's Back puts a lifted library row back where it was, before it leaves Settings. */
    override fun onSystemBack(): Boolean = libraryOrder?.putBackIfLifted() == true

    override fun onDestroyView() {
        scope.cancel()
        saveScope.cancel()
    }

    override fun requestInitialFocus(): Boolean = nav.focus()

    override fun hints(): List<ButtonHint> = when {
        ::overlay.isInitialized && overlay.isOpen -> listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        focusedHomeRow != null && pane.hasFocus() -> listOf(ButtonHint.activate("Show or hide"),
            ButtonHint.primary("Move up"), ButtonHint.secondary("Move down"))
        section == SECTION_LIBRARIES && pane.hasFocus() && libraryOrder?.hints() != null -> libraryOrder?.hints().orEmpty()
        ::nav.isInitialized && nav.hasFocus() -> listOf(ButtonHint("→", "Open", PadAction.Step(Direction.RIGHT)))
        else -> listOf(ButtonHint.activate("Choose"))
    }

    override fun onPad(action: PadAction): Boolean {
        if (overlay.onPad(action)) return true
        if (section == SECTION_LIBRARIES && pane.hasFocus() && libraryOrder?.onPad(action) == true) return true
        val row = focusedHomeRow?.takeIf { pane.hasFocus() }
        return when {
            action == PadAction.Primary && row != null -> { moveHomeRow(row, -1); true }
            action == PadAction.Secondary && row != null -> { moveHomeRow(row, 1); true }
            action is PadAction.Step && action.direction == Direction.RIGHT && nav.hasFocus() -> focusPane()
            action == PadAction.Back && pane.hasFocus() -> nav.focus()
            else -> false
        }
    }

    private fun focusPane(): Boolean {
        val first = (0 until pane.childCount).asSequence().map { pane.getChildAt(it) }
            .mapNotNull { firstFocusable(it) }.firstOrNull() ?: return false
        return first.requestFocus()
    }

    private fun firstFocusable(view: View): View? {
        if (view.visibility != View.VISIBLE) return null
        if (view.isFocusable && view !is ViewGroup) return view
        if (view.isFocusable && view is ViewGroup && view.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) firstFocusable(view.getChildAt(i))?.let { return it }
        return if (view.isFocusable) view else null
    }

    /** Rebuilds the chosen section's cards. Focus inside the pane comes back to the control with the same tag. */
    private fun render() {
        if (!::pane.isInitialized) return
        // The tags round the focused control, nearest first: the control itself
        // when it comes back, else the card it was in (Back to A-Z goes once
        // the order is A to Z again, and its card's first row takes focus).
        val focusedTags = pane.findFocus()?.let { focused ->
            generateSequence(focused) { it.parent as? View }.takeWhile { it !== pane }.mapNotNull { it.tag as? String }.toList()
        }.orEmpty()
        focusedHomeRow = null
        // Removing the focused card hands focus to the first focusable in the
        // window, the first section in the list, which then opened: moving a
        // Home row or a library jumped to Appearance. The chosen section holds
        // focus meanwhile, and the control with the same tag takes it back.
        if (focusedTags.isNotEmpty() && ::nav.isInitialized) nav.focus()
        pane.removeAllViews()
        when (section) {
            SECTION_APPEARANCE -> appearance()
            SECTION_HOME -> homeRows()
            SECTION_LIBRARIES -> libraries()
            SECTION_PLAYBACK -> playback()
            SECTION_SUBTITLES -> subtitles()
            SECTION_DOWNLOADS -> downloads()
            else -> more()
        }
        focusedTags.firstNotNullOfOrNull { tag -> pane.findViewWithTag<View>(tag) }?.let { target ->
            pane.post { firstFocusable(target)?.requestFocus() }
        }
        host.refreshHints()
    }

    private fun card(tag: String? = null) = SettingsCard(host.viewContext, colors).also { card ->
        tag?.let { card.tag = it }
        pane.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(if (Theme.onGlass(colors)) 10 else 12) })
    }

    private fun blob(options: List<Pair<String, String>>, selected: String, tag: String, accent: Boolean = false,
                     onPick: (String) -> Unit) = BlobSegmentedView(host.viewContext, colors, ringVisible,
        if (accent) BlobSegmentedView.Style.ACCENT else BlobSegmentedView.Style.PILL).apply {
        this.tag = tag
        if (Theme.onGlass(colors)) useGlassTrack() else trackColor = colors.background
        setOptions(options.map { BlobSegmentedView.Option(it.first, it.second) }, selected)
        this.onPick = { id -> onPick(id); host.refreshHints() }
        onOptionFocused = { host.refreshHints() }
    }

    // ---------------------------------------------------------- Appearance

    private fun appearance() {
        val context = host.viewContext
        val look = LookSettings.get(context)
        // A new look rebuilds the app, as dark and light do; Settings reopens here.
        card().title("Look").hint(if (look == Look.GLASS)
                "The page takes the colour of the artwork in focus, under panels of tinted glass. Always dark."
            else "The look before Glass, with its own light and dark themes. It goes once Glass is finished.")
            .body(blob(Look.entries.map { it.name to it.label }, look.name, "look") { id ->
                LookSettings.set(context, Look.valueOf(id))
                host.refreshAppearance()
                render()
            })
        // Glass is always dark, so the theme is Classic's alone.
        if (look == Look.CLASSIC) card().title("Theme").body(blob(listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "Match the system"),
            ThemeSettings.getMode(context).name, "theme") { id ->
            ThemeSettings.setMode(context, ThemeSettings.Mode.valueOf(id))
            host.refreshAppearance()
            render()
        })
        palette(ContentMode.MEDIA, "Movies and TV", "Play buttons, progress and the chosen tab while you watch")
        palette(ContentMode.BOOKS, "Books", "The same places while you read, so books feel like their own space")
    }

    private fun palette(mode: ContentMode, name: String, hint: String) {
        val context = host.viewContext
        val dark = Theme.isDark(context)
        val chosen = DomainPreferences.accent(context, mode)
        val sample = TextView(context).apply {
            text = if (mode == ContentMode.BOOKS) "Continue reading" else "Play"
            textSize = 12f; textWeight(700); gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(30)
            setTextColor(chosen.ink(dark))
            background = ThemeGradientDrawable().apply { cornerRadius = dp(15).toFloat(); setColor(chosen.color(dark)) }
        }
        val bar = View(context).apply {
            background = ThemeGradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(chosen.color(dark)) }
        }
        val track = FrameLayout(context).apply {
            background = ThemeGradientDrawable.rounded(dp(3).toFloat(), ColorUtils.setAlphaComponent(colors.primaryText, 0x1F))
            addView(bar, FrameLayout.LayoutParams(dp(66), MATCH))
        }
        val swatches = SwatchRowView(context, colors, ringVisible).apply {
            tag = "palette:${mode.stored}"
            bind(AccentPreset.entries.map { SwatchRowView.Swatch(it.id, it.label, it.color(dark)) }, chosen.id)
            onPick = { swatch ->
                val preset = AccentPreset.fromStored(swatch.id)
                DomainPreferences.setAccent(context, mode, preset)
                host.refreshAppearance()
                render()
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sample)
            addView(track, LinearLayout.LayoutParams(dp(120), dp(5)).apply { marginStart = dp(10) })
        }
        card().title(name, chosen.label).hint(hint).body(swatches).body(row)
    }

    // ---------------------------------------------------------------- Home

    private fun homeRows() {
        val context = host.viewContext
        val order = HomeRowSettings.order(context).toMutableList()
        libraries.forEach { (id, _) -> HomeRows.libraryRowId(id).let { if (it !in order) order += it } }
        val hidden = effectiveHidden()
        val card = card().title("Home rows")
            .hint("A shows or hides a row; X and Y move it up and down. Home changes straight away.")
        order.forEach { id ->
            val name = HomeRows.libraryViewId(id)?.let { viewId -> libraries.firstOrNull { it.first == viewId }?.second?.let(HomeRows::libraryRowTitle) }
                ?: if (HomeRows.libraryViewId(id) != null) null else HomeRows.builtInTitle(id)
            if (name == null) return@forEach
            val row = SwitchRowView(context, colors, ringVisible, name, if (HomeRows.libraryViewId(id) != null) "The newest titles in that library" else "").apply {
                tag = "home:$id"
                set(id !in hidden)
                onChange = { shown ->
                    val now = effectiveHidden().toMutableSet()
                    if (shown) now -= id else now += id
                    HomeRowSettings.save(context, currentOrder(), now)
                }
                FocusDecorator.listen(this, ringVisible) { _, focused ->
                    focusedHomeRow = if (focused) id else focusedHomeRow.takeUnless { it == id }
                    host.refreshHints()
                }
            }
            card.body(row, topDp = 6f)
            (row.layoutParams as LinearLayout.LayoutParams).width = MATCH
        }
    }

    /** The order as Settings shows it, library rows included, so a move keeps them. */
    private fun currentOrder(): List<String> {
        val context = host.viewContext
        val order = HomeRowSettings.order(context).toMutableList()
        libraries.forEach { (id, _) -> HomeRows.libraryRowId(id).let { if (it !in order) order += it } }
        return order
    }

    /** Hidden rows, counting a library row Home has never been told about: those start hidden. */
    private fun effectiveHidden(): Set<String> {
        val context = host.viewContext
        val stored = HomeRowSettings.order(context)
        return HomeRowSettings.hidden(context) + libraries.map { HomeRows.libraryRowId(it.first) }.filter { it !in stored }
    }

    private fun moveHomeRow(id: String, delta: Int) {
        val context = host.viewContext
        val order = currentOrder().toMutableList()
        val hidden = effectiveHidden()
        val from = order.indexOf(id).takeIf { it >= 0 } ?: return
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return
        order.add(to, order.removeAt(from))
        HomeRowSettings.save(context, order, hidden)
        render()
    }

    // ----------------------------------------------------------- Libraries

    private fun libraries() {
        val section = libraryOrder ?: return run {
            card().title("Libraries").hint("Connect to the hub to arrange your libraries.")
        }
        section.build { tag -> card(tag) }
    }

    // ------------------------------------------------------------ Playback

    private fun playback() {
        val context = host.viewContext
        card().title("Skip back and forward").hint("◀ ▶ on the timeline, the skip buttons, and double-tapping either side of the video")
            .body(blob(listOf(5, 10, 15, 30).map { it.toString() to "$it s" }, PlaybackSettings.seekSeconds(context).toString(), "seek", accent = true) {
                PlaybackSettings.setSeekSeconds(context, it.toInt())
            })
        card().title("Show the next episode").hint("A card with a bar that fills, then the next episode starts")
            .body(blob(NextEpisodeTiming.entries.map { it.name to it.label }, PlaybackSettings.nextTiming(context).name, "next") {
                PlaybackSettings.setNextTiming(context, NextEpisodeTiming.valueOf(it))
            })
        pane.addView(SwitchRowView(context, colors, ringVisible, "Skip intros automatically",
            "Off: a Skip intro button appears instead. Intros come from Jellyfin, or from chapters named Opening or OP").apply {
            tag = "autoskip"
            set(PlaybackSettings.autoSkipIntro(context))
            onChange = { PlaybackSettings.setAutoSkipIntro(context, it) }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        card().title("HDR video").hint("This screen shows HDR dim. Applies from the next video.")
            .body(blob(listOf("true" to "Convert to SDR", "false" to "Show as HDR"), PlaybackSettings.convertHdr(context).toString(), "hdr") {
                PlaybackSettings.setConvertHdr(context, it.toBoolean())
            })
    }

    // ----------------------------------------------------------- Subtitles

    private fun subtitles() {
        val context = host.viewContext
        val look = SubtitleSettings.look(context)
        val preview = FrameLayout(context).apply {
            background = ThemeGradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(64, 26, 28), Color.rgb(24, 34, 52))).apply { cornerRadius = dp(16).toFloat() }
            clipToOutline = true
            tag = "preview"
        }
        preview.addView(TextView(context).apply {
            text = "BLEACH"; typeRole(Type.Role.HERO, 64f); setTextColor(Color.WHITE); alpha = 0.08f; gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        val controls = FrameLayout(context).apply {
            visibility = if (subtitlePreviewControls) View.VISIBLE else View.GONE
            background = ThemeGradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(220, 0, 0, 0), Color.TRANSPARENT))
            addView(View(context).apply {
                background = ThemeGradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.argb(70, 255, 255, 255)) }
            }, FrameLayout.LayoutParams(MATCH, dp(4), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(16)) })
            addView(View(context).apply {
                background = ThemeGradientDrawable.rounded(dp(2).toFloat(), colors.accent)
            }, FrameLayout.LayoutParams(dp(150), dp(4), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(14), 0, 0, dp(16)) })
        }
        preview.addView(controls, FrameLayout.LayoutParams(MATCH, dp(64), Gravity.BOTTOM))
        val subtitle = SubtitleView(context).apply {
            SubtitleLooks.apply(this, look)
            // The player sizes text against the whole screen; against this small
            // box the same fraction is unreadably small. Show the real size.
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                look.size.textFraction * resources.displayMetrics.heightPixels)
            setCues(listOf(Cue.Builder().setText("I've always been able to see ghosts.").build()))
            val covered = if (subtitlePreviewControls) dp(64) else 0
            setBottomPaddingFraction(PlaybackEnhancements.subtitlePlacement(look, covered, dp(PREVIEW_DP)))
        }
        preview.addView(subtitle, FrameLayout.LayoutParams(MATCH, MATCH))
        preview.addView(TextView(context).apply {
            text = if (subtitlePreviewControls) "Hide controls" else "Show controls"
            textSize = 11f; textWeight(600); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0); minimumHeight = dp(28)
            background = Styler.chipBackground(context, colors)
            contentDescription = text
            tag = "preview-controls"
            Styler.makeFocusable(this)
            activateOnTap { subtitlePreviewControls = !subtitlePreviewControls; render() }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        pane.addView(preview, LinearLayout.LayoutParams(MATCH, dp(PREVIEW_DP)).apply { bottomMargin = dp(12) })

        fun save(next: SubtitleLook) { SubtitleSettings.save(context, next); render() }
        card().title("Look")
            .body(blob(listOf(SubtitleStyle.OUTLINE.name to "Outline (like Findroid)", SubtitleStyle.BOX.name to "Box"), look.style.name, "style") {
                save(look.copy(style = SubtitleStyle.valueOf(it)))
            })
            .hint(if (look.style == SubtitleStyle.OUTLINE)
                "White text with a black edge and nothing behind it, the way your old player drew subtitles. Clean over most scenes."
                else "White text on a dark box, Android's caption style. Easiest to read over snow and bright skies.")
        card().title("Size").body(blob(SubtitleSize.entries.map { it.name to PlayerLabels.subtitleSize(it) }, look.size.name, "size") {
            save(look.copy(size = SubtitleSize.valueOf(it)))
        })
        pane.addView(SwitchRowView(context, colors, ringVisible, "Move up when the controls show",
            "On: lines jump above the timeline so it never covers them. Off: they stay put").apply {
            tag = "lift"
            set(look.liftWithControls)
            onChange = { save(look.copy(liftWithControls = it)) }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
    }

    // ----------------------------------------------------- Downloads, More

    private fun downloads() {
        val context = host.viewContext
        utility("offline", "Offline downloads", offlineDetail(), AppIcon.SETTINGS, com.pocketds.hub.R.drawable.ic_nav_offline) {
            host.push(OfflineSettingsScreen(ringVisible))
        }
        utility("cast", "Public Hub address", HubSettings.publicBaseUrl(context).ifEmpty { "Uses private Hub address" }, AppIcon.TV) { editCastAddress() }
    }

    private fun more() {
        NotificationSettings.limits(host.viewContext).let {
            utility("notifications", "Notifications", "Sonarr ${it.sonarr} · Radarr ${it.radarr} · Bazarr ${it.bazarr}", AppIcon.SETTINGS,
                com.pocketds.hub.R.drawable.ic_nav_notifications) { host.push(NotificationSettingsScreen(ringVisible)) }
        }
        utility("controller", "Controller test", "Inspect buttons, sticks and triggers", AppIcon.SETTINGS, com.pocketds.hub.R.drawable.ic_nav_pad) {
            host.push(PadTestScreen())
        }
        utility("dictionary", "Offline dictionary", "Open English WordNet 2025 · CC BY 4.0", AppIcon.BOOK) {
            overlay.show("Offline dictionary", "Available without an internet connection", listOf(
                ChoiceOverlay.Choice("source", "Open English WordNet 2025", "https://en-word.net/downloads/"),
                ChoiceOverlay.Choice("license", "Creative Commons Attribution 4.0", "https://creativecommons.org/licenses/by/4.0/"),
                ChoiceOverlay.Choice("changes", "App index", "Converted to a local headword and definition database")
            ), onCancel = host::refreshHints) { }
            host.refreshHints()
        }
        utility("licences", "Fonts and licences", "Figtree and Bricolage Grotesque · SIL Open Font License", AppIcon.APPEARANCE) {
            overlay.show("Fonts and licences", "Their full texts are in the app, under assets/licenses", listOf(
                ChoiceOverlay.Choice("figtree", "Figtree", "Body text · © The Figtree Project Authors · SIL OFL 1.1"),
                ChoiceOverlay.Choice("bricolage", "Bricolage Grotesque", "Titles · © The Bricolage Grotesque Project Authors · SIL OFL 1.1"),
                ChoiceOverlay.Choice("ffmpeg", "FFmpeg audio decoder", "AC-3 and E-AC-3 · LGPL 2.1"),
                ChoiceOverlay.Choice("wordnet", "Open English WordNet", "Dictionary · CC BY 4.0")
            ), onCancel = host::refreshHints) { }
            host.refreshHints()
        }
    }

    private fun utility(id: String, label: String, detail: String, icon: AppIcon, iconResource: Int? = null, action: () -> Unit) {
        val row = UtilityRowView(host.viewContext, colors, label, detail, icon).apply {
            tag = id
            iconResource?.let(::setIconResource)
            background = Styler.cardBackground(context, colors, cornerDp = 16f, focusStrokeDp = 2f)
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { _, _ -> host.refreshHints() }
            activateOnTap(action)
        }
        pane.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun offlineDetail(): String = buildList {
        val context = host.viewContext
        add(if (com.pocketds.hub.settings.OfflineSettings.wifiOnly(context)) "Wi-Fi only" else "Any network")
        if (com.pocketds.hub.settings.OfflineSettings.chargingOnly(context)) add("while charging")
        add("keep ${com.pocketds.hub.settings.OfflineSettings.minimumFreeMb(context)} MB free")
    }.joinToString(" · ")

    private fun editCastAddress() {
        val field = EditText(host.viewContext).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(HubSettings.publicBaseUrl(host.viewContext))
            selectAll()
            contentDescription = "Public HTTPS Hub address for TV playback and offline downloads"
        }
        AlertDialog.Builder(host.viewContext)
            .setTitle("Public Hub address")
            .setMessage("Chromecast and offline downloads use this HTTPS address. Downloads fall back to the private Hub address if it is unavailable.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val entered = field.text.toString().trim()
                if (CastTransferPolicy.receiverUrl(entered, "/v1/cast/check/stream") == null) {
                    host.notify("Enter a public HTTPS Hub address")
                } else {
                    HubSettings.setCastBaseUrl(host.viewContext, entered)
                    render()
                    host.notify("Public Hub address saved")
                }
            }
            .show()
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val PREVIEW_DP = 176
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_HOME = "home"
        const val SECTION_LIBRARIES = "libraries"
        const val SECTION_PLAYBACK = "playback"
        const val SECTION_SUBTITLES = "subtitles"
        const val SECTION_DOWNLOADS = "downloads"
        val SECTIONS = listOf(
            SECTION_APPEARANCE to "Appearance", SECTION_HOME to "Home", SECTION_LIBRARIES to "Libraries", SECTION_PLAYBACK to "Playback",
            SECTION_SUBTITLES to "Subtitles", SECTION_DOWNLOADS to "Downloads", "more" to "More"
        )
        /** Glass: each place's icon, as the prototype's list has them. */
        val SECTION_ICONS = mapOf(
            SECTION_APPEARANCE to com.pocketds.hub.ui.AppIcon.APPEARANCE, SECTION_HOME to com.pocketds.hub.ui.AppIcon.HOME,
            SECTION_LIBRARIES to com.pocketds.hub.ui.AppIcon.ARRANGE,
            SECTION_PLAYBACK to com.pocketds.hub.ui.AppIcon.PLAY, SECTION_SUBTITLES to com.pocketds.hub.ui.AppIcon.SUBTITLES,
            SECTION_DOWNLOADS to com.pocketds.hub.ui.AppIcon.DOWNLOAD, "more" to com.pocketds.hub.ui.AppIcon.MORE
        )
    }
}
