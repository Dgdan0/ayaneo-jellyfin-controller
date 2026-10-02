package com.pocketds.hub.screens.discover

import com.pocketds.hub.net.HubEndpoints

import com.pocketds.hub.nav.TopBarView

import com.pocketds.hub.ui.typeRole

import com.pocketds.hub.ui.Type

import com.pocketds.hub.ui.AppIcon

import com.pocketds.hub.ui.PillButton

import com.pocketds.hub.ui.CastRowView

import com.pocketds.hub.ui.DetailHeaderView

import com.pocketds.hub.ui.FocusHorizontalScrollView
import com.pocketds.hub.ui.FocusScrollView
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.Availability
import com.pocketds.hub.model.CastMember
import com.pocketds.hub.model.MediaDetail
import com.pocketds.hub.model.Stage
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.state.PollCadence
import com.pocketds.hub.state.PollOutcome
import com.pocketds.hub.state.Poller
import com.pocketds.hub.ui.showStatus
import com.pocketds.hub.ui.activateOnTap

/**
 * One title, and where it actually is.
 *
 * The pipeline is the point of this screen and of the whole project: "requested
 * three days ago, grabbed, 63% downloaded, not in the library yet" is a question
 * that currently takes four browser tabs to answer.
 *
 * It runs **across** rather than down. Five stacked rows ate the space the
 * overview and cast need, and on a 7-inch landscape screen the horizontal axis
 * is the one there is plenty of. Detail moves to the summary line beneath, so
 * the strip stays a glance rather than a wall of text.
 */
class MediaDetailScreen(
    private val api: HubApi,
    private val mediaKey: String,
    private val fallbackTitle: String,
    private val ringVisible: () -> Boolean
) : Screen {

    override val contentDomain = com.pocketds.hub.state.ContentMode.MEDIA
    override val title: String = fallbackTitle

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var header: DetailHeaderView
    private lateinit var scroll: FocusScrollView
    private lateinit var stageStrip: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var summary: TextView
    private lateinit var castLabel: TextView
    private lateinit var cast: CastRowView
    private lateinit var status: TextView

    private var host: ScreenHost? = null
    private var detail: MediaDetail? = null
    private var visible = false
    /** Refreshes the pipeline while a stage is active, and retries a failed load. */
    private val poller = Poller(PollCadence.PIPELINE)
    private var actionsKey: List<Any> = emptyList()
    private lateinit var form: FormOverlay
    private lateinit var picker: ChoiceOverlay
    private lateinit var flow: RequestFlow

    override val drawsUnderTopBar = true
    override val showsOwnTitle = true

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)

        // The same header as a Library title: artwork to the edges behind the
        // tabs, the title over it, the overview and the actions under it.
        scroll = FocusScrollView(context, revealAbove = Styler.dpInt(context, 56f)).apply {
            isFillViewport = true
            setBackgroundColor(colors.background)
            // Or a focused cast card has its ring clipped by the scroll bounds.
            clipChildren = false
        }
        val scroller = scroll
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(0, 0, 0, Styler.dpInt(context, 40f))
        }
        header = DetailHeaderView(context, colors, ringVisible).apply {
            topInsetDp = TopBarView.HEIGHT_DP.toInt()
            titleView.text = fallbackTitle
            overview.onChanged = { host.refreshHints() }
        }
        root.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        // Real, focusable buttons -- not only hint-bar chips: the hint bar is
        // invisible to anyone driving the trackpad.
        actionRow = header.actions

        // Where a request has got to, under the actions: the stages as chips,
        // and a sentence only while something is moving or wrong.
        stageStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Styler.dpInt(context, 2f), 0, Styler.dpInt(context, 2f))
        }
        header.continuation.addView(FocusHorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(stageStrip)
        })
        summary = TextView(context).apply {
            textSize = 13f
            setTextColor(colors.accent)
            setPadding(0, Styler.dpInt(context, 2f), 0, 0)
        }
        header.continuation.addView(summary)

        castLabel = TextView(context).apply {
            text = "Cast"
            typeRole(Type.Role.HEADING, 16f)
            setTextColor(colors.primaryText)
            setPadding(Styler.dpInt(context, 24f), Styler.dpInt(context, 14f), 0, 0)
            visibility = View.GONE
        }
        root.addView(castLabel)
        cast = CastRowView(context, colors, ringVisible).apply {
            visibility = View.GONE
            onOpen = { person -> person.key.toIntOrNull()?.let { host.push(PersonScreen(api, it, person.name, ringVisible)) } }
            onFocused = { host.refreshHints() }
        }
        root.addView(cast, LinearLayout.LayoutParams(MATCH, WRAP))

        status = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(Styler.dpInt(context, 24f), Styler.dpInt(context, 14f), Styler.dpInt(context, 24f), 0)
            text = "Loading…"
        }
        root.addView(status)

        scroller.addView(root)
        // Once the backdrop has scrolled away, the tabs above need solid ground.
        scroller.setOnScrollChangeListener { _, _, y, _, _ -> host.setTopBarOverArtwork(y < Styler.dpInt(context, 24f)) }

        // The scroller goes inside a frame so the request dialog can sit over
        // it. An AlertDialog would be a second window with its own focus rules
        // and would leave the hint bar behind it describing this screen.
        val frame = FrameLayout(context)
        rootFrame = frame
        frame.addView(scroller, FrameLayout.LayoutParams(MATCH, MATCH))
        form = FormOverlay(context, colors, ringVisible)
        frame.addView(form, FrameLayout.LayoutParams(MATCH, MATCH))
        picker = ChoiceOverlay(context, colors, ringVisible)
        frame.addView(picker, FrameLayout.LayoutParams(MATCH, MATCH))

        flow = RequestFlow(
            api = api,
            scope = scope,
            overlay = { form },
            onStatus = { text, isError ->
                status.setTextColor(if (isError) colors.dangerText else colors.mutedText)
                status.text = text
            },
            onNotify = { host?.notify(it) },
            onHintsChanged = { host?.refreshHints() },
            // Reload rather than patch locally: the hub has just dropped its
            // cache for this title, so a fresh fetch is both cheap and
            // authoritative.
            onRequested = { load() }
        )
        return frame
    }

    override fun onShow() {
        visible = true
        scroll.post { host?.setTopBarOverArtwork(scroll.scrollY < Styler.dpInt(scroll.context, 24f)) }
        load()
    }

    override fun onHide() {
        visible = false
        poller.stop()
        if (form.isOpen) form.dismiss()
        if (picker.isOpen) picker.dismiss()
        host?.refreshHints()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun hints(): List<ButtonHint> {
        if (form.isOpen) {
            return listOf(ButtonHint.activate("Change"), ButtonHint.back("Cancel"))
        }
        if (picker.isOpen) {
            return listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        }
        val hints = mutableListOf(ButtonHint.back())
        if (detail?.canRequest == true) {
            hints.add(0, ButtonHint.primary(if (flow.busy) "Requesting…" else "Request"))
        }
        // Offered on everything, because "why has this not downloaded?" is a
        // question about titles that are already requested at least as often as
        // about new ones. The hub answers with 409 and a plain sentence when the
        // title is not in Radarr or Sonarr yet.
        hints.add(ButtonHint("Ⓨ", "Find release", PadAction.Secondary))
        return hints
    }

    /**
     * Takes focus on arrival, now that there is something at the top worth
     * focusing.
     *
     * This was false because the only focusable thing used to be the cast row,
     * which is below the fold -- focusing it scrolled the title out of view
     * before you had read it. The action buttons sit beside the poster, so
     * focusing them moves nothing.
     */
    override val focusOnShow: Boolean get() = true

    /**
     * The first action button, falling back to the first cast card.
     *
     * The buttons are near the top and are what someone opening this screen
     * wants; the cast row is below the fold and scrolling the title out of view
     * to reach it was the original reason focusOnShow is false here.
     */
    override fun requestInitialFocus(): Boolean {
        if (::actionRow.isInitialized && actionRow.childCount > 0) {
            return actionRow.getChildAt(0).requestFocus()
        }
        if (!::cast.isInitialized || cast.visibility != View.VISIBLE) return false
        return cast.requestFocus()
    }

    override fun onPad(action: PadAction): Boolean {
        if (form.onPad(action)) {
            host?.refreshHints()
            return true
        }
        if (picker.onPad(action)) {
            host?.refreshHints()
            return true
        }
        if (action == PadAction.Primary && detail?.canRequest == true && !flow.busy) {
            flow.start(mediaKey, detail?.media?.title ?: fallbackTitle)
            return true
        }
        if (action == PadAction.Secondary) {
            findRelease()
            return true
        }
        if (action == PadAction.Refresh) {
            load()
            return true
        }
        return false
    }

    private fun load() {
        poller.start(scope, { visible }) {
            when (val result = api.mediaDetail(mediaKey)) {
                is HubResult.Ok -> {
                    render(result.value)
                    PollOutcome(ok = true, active = result.value.pipeline.stages.any { it.state == "active" })
                }
                is HubResult.Failed -> {
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = detail != null), colors)
                    PollOutcome(ok = false)
                }
            }
        }
    }

    private fun render(d: MediaDetail) {
        detail = d
        header.titleView.text = d.media.title
        header.metadataView.text = describe(d)

        stageStrip.removeAllViews()
        d.pipeline.stages.forEach { stageStrip.addView(stageChip(it)) }

        // Hidden unless it adds something. With nothing in motion it read
        // "Not requested" directly under a strip of five pending chips that
        // already said exactly that.
        val informative = d.pipeline.stages.any {
            it.state == "active" || it.state == "failed" || it.state == "stuck"
        }
        summary.visibility = if (informative && d.pipeline.summary.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        summary.text = d.pipeline.summary
        summary.setTextColor(
            when (Availability.fromWire(d.availability)) {
                Availability.AVAILABLE -> colors.badgeAvailable
                Availability.BLOCKED, Availability.DELETED -> colors.badgeFailed
                else -> colors.accent
            }
        )

        buildActions(d)
        header.overview.bind(d.overview)

        castLabel.visibility = if (d.cast.isEmpty()) View.GONE else View.VISIBLE
        cast.visibility = castLabel.visibility
        cast.bind(d.cast.map { CastRowView.Person(it.id.toString(), it.name, it.character,
            it.profile.takeIf(String::isNotBlank)?.let(api::imageUrl)) }, Artwork.loader(api, cast.context))

        val availability = Availability.fromWire(d.availability)
        status.showStatus(
            StatusText.loaded(
                availability.label.ifEmpty {
                    if (availability == Availability.NOT_IN_LIBRARY) "Not in library" else "Status unavailable"
                },
                d.cache,
                d.partial.map { it.service }
            ),
            colors
        )

        header.bindArtwork(d.media.type,
            d.media.backdrop.takeIf(String::isNotBlank)?.let { api.imageUrl(HubEndpoints.sized(it, ART_WIDTH_PX)) },
            d.media.poster.takeIf(String::isNotBlank)?.let(api::imageUrl), Artwork.loader(api, header.context))

        host?.refreshHints()
    }

    private fun describe(d: MediaDetail): String = buildString {
        if (d.media.year > 0) append(d.media.year)
        if (d.runtimeMinutes > 0) {
            if (isNotEmpty()) append(" · ")
            append(d.runtimeMinutes).append(" min")
        }
        if (d.seasons > 0) {
            if (isNotEmpty()) append(" · ")
            append(d.seasons).append(if (d.seasons == 1) " season" else " seasons")
        }
        if (d.genres.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(d.genres.joinToString(", "))
        }
        if (d.rating > 0) {
            if (isNotEmpty()) append(" · ")
            append("★ ").append(String.format("%.1f", d.rating))
        }
    }

    /**
     * One stage as a compact chip.
     *
     * A percentage is appended only to the stage that is actually active. Five
     * chips each carrying their own detail would be a wall of text; the detail
     * lives in the summary line beneath instead.
     */
    private fun stageChip(stage: Stage): View {
        val context = stageStrip.context
        val tint = when (stage.state) {
            "done" -> colors.badgeAvailable
            "active" -> colors.accent
            "failed", "stuck" -> colors.badgeFailed
            // A step we could not ask about is greyed, not shown as
            // not-yet-happened. Those are different facts.
            else -> colors.mutedText
        }
        return TextView(context).apply {
            textSize = 13f
            setTextColor(tint)
            val label = stage.compactLabel
            text = if (stage.state == "active" && stage.progress > 0) {
                stage.glyph + " " + label + " " + String.format("%.0f%%", stage.progress * 100)
            } else {
                stage.glyph + " " + label
            }
            val h = Styler.dpInt(context, 9f)
            val v = Styler.dpInt(context, 5f)
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                rightMargin = Styler.dpInt(context, 6f)
            }
        }
    }

    /**
     * Buttons for what the hub says is possible, plus the one thing it does not
     * model: finding a release by hand.
     */
    private fun buildActions(d: MediaDetail) {
        val attention = d.pipeline.stages.any { it.id in listOf("download", "import") && it.state in listOf("failed", "stuck") }
        val key = listOf(attention, d.canRequest, flow.busy, d.trailerKey, d.trailerUrl)
        // The pipeline refresh re-renders every four seconds while a download
        // runs. Rebuilding identical buttons threw focus out of the row each
        // time, so the selection jumped under a pad user's thumb.
        if (key == actionsKey && actionRow.childCount > 0) return
        actionsKey = key
        val focusedLabel = (actionRow.findFocus() as? TextView)?.text?.toString()
        actionRow.removeAllViews()
        if (attention) {
            actionRow.addView(actionButton("Transfers needing attention", AppIcon.INFO) {
                host?.push(com.pocketds.hub.screens.downloads.DownloadsScreen(api, ringVisible, startWithAttention = true, targetMediaKey = mediaKey))
            })
        }
        if (d.canRequest) {
            actionRow.addView(
                actionButton(if (flow.busy) "Requesting…" else "Request", null, primary = true) {
                    if (!flow.busy) flow.start(mediaKey, d.media.title)
                }
            )
        }
        // Offered on everything, because "why has this not downloaded?" is asked
        // about titles that are already requested at least as often as about new
        // ones. The hub answers with a plain sentence when the title is not in
        // Radarr or Sonarr yet.
        actionRow.addView(actionButton("Find release", AppIcon.SEARCH) { findRelease() })
        // Only when there is one. TMDB has no trailer for plenty of titles --
        // The Mentalist carries nothing but behind-the-scenes clips -- and a
        // button that goes nowhere is worse than no button.
        if (d.trailerUrl.isNotEmpty()) {
            actionRow.addView(actionButton("Trailer", AppIcon.PLAY) {
                // Handed to the Activity, which owns the floating window, so the
                // trailer keeps playing when you back out of this screen.
                host?.openTrailer(d.trailerKey, d.trailerUrl, d.media.title)
            })
        }
        // Claim focus now that there is something to focus.
        //
        // showCurrent asks for initial focus the frame after this screen is
        // pushed, which is well before the detail has loaded -- so there were
        // no buttons yet, focusOnShow found nothing, and the screen opened with
        // no selection at all.
        actionRow.post {
            val same = (0 until actionRow.childCount).map(actionRow::getChildAt)
                .firstOrNull { (it as? TextView)?.text?.toString() == focusedLabel }
            when {
                // The row changed under the selection: keep it on the same action.
                focusedLabel != null -> (same ?: actionRow.getChildAt(0))?.requestFocus()
                rootFrame.findFocus() == null -> actionRow.getChildAt(0)?.requestFocus()
            }
            host?.refreshHints()
        }
    }


    /** A pill like every detail page's; the first lines up with the title, its ring gap pulled back. */
    private fun actionButton(label: String, icon: AppIcon?, primary: Boolean = false, onClick: () -> Unit): View =
        PillButton.create(actionRow.context, colors, label, icon, primary = primary, heightDp = 40f).apply {
            activateOnTap { onClick() }
            // No scale: these sit in a row and growing one shoves the next along.
            FocusDecorator.attach(this, ringVisible, scale = false)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                if (actionRow.childCount == 0) marginStart = -Styler.dpInt(context, PillButton.RING_DP)
                marginEnd = Styler.dpInt(context, 6f)
            }
        }

    /** Ⓨ: pick a release by hand. */
    private fun findRelease() {
        val d = detail
        val seasons = d?.seasonList.orEmpty()
        if (d == null || d.media.type != "series" || seasons.isEmpty()) {
            pushReleases(0)
            return
        }
        host?.push(
            SeasonReleasePickerScreen(
                api, mediaKey, d.media.title, seasons, d.media.poster, ringVisible
            )
        )
    }

    private fun pushReleases(season: Int) {
        host?.push(
            ReleasesScreen(
                api, mediaKey, detail?.media?.title ?: fallbackTitle, season, 0, ringVisible
            )
        )
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val ART_WIDTH_PX = 1920
    }
}
