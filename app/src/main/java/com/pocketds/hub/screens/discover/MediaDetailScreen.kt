package com.pocketds.hub.screens.discover

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.request.ImageRequest
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.Availability
import com.pocketds.hub.model.CastMember
import com.pocketds.hub.model.MediaDetail
import com.pocketds.hub.model.Stage
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
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
import kotlinx.coroutines.launch

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

    override val title: String = fallbackTitle

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var heading: TextView
    private lateinit var meta: TextView
    private lateinit var stageStrip: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var summary: TextView
    private lateinit var overview: TextView
    private lateinit var castLabel: TextView
    private lateinit var castScroller: HorizontalScrollView
    private lateinit var castRow: LinearLayout
    private lateinit var status: TextView

    private var host: ScreenHost? = null
    private var detail: MediaDetail? = null
    private lateinit var form: FormOverlay
    private lateinit var picker: ChoiceOverlay
    private lateinit var flow: RequestFlow

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)

        val scroller = ScrollView(context).apply {
            // ScrollView and HorizontalScrollView are focusable by default, which
            // makes them invisible focus stops: a directional press lands on the
            // scroller, draws no ring, and reads as the pad being broken. They
            // should pass focus straight through to their contents.
            isFocusable = false
            isFillViewport = true
            setBackgroundColor(colors.background)
            // Or a focused cast card has its ring clipped by the scroll bounds.
            clipChildren = false
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            val pad = Styler.dpInt(context, 16f)
            setPadding(pad, pad, pad, Styler.dpInt(context, 90f))
        }

        backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Dimmed, because text sits under it. A full-brightness backdrop
            // makes the overview unreadable and looks like a rendering bug.
            alpha = 0.35f
            setBackgroundColor(colors.posterPlaceholder)
            layoutParams = LinearLayout.LayoutParams(MATCH, Styler.dpInt(context, 118f))
        }
        root.addView(backdrop)

        // Poster beside the title rather than above everything. The backdrop is
        // atmosphere; the poster is what the eye actually uses to recognise a
        // title, so it belongs next to the name, and putting them side by side
        // buys back a good deal of vertical space on a 456dp-tall screen.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Styler.dpInt(context, 12f), 0, 0)
        }

        poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
            // 2:3, the aspect every poster is.
            layoutParams = LinearLayout.LayoutParams(
                Styler.dpInt(context, 104f), Styler.dpInt(context, 156f)
            ).apply { rightMargin = Styler.dpInt(context, 14f) }
        }
        header.addView(poster)

        val headerText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        heading = TextView(context).apply {
            textSize = 23f
            maxLines = 2
            setTextColor(colors.primaryText)
            text = fallbackTitle
        }
        headerText.addView(heading)

        meta = TextView(context).apply {
            textSize = 13f
            setTextColor(colors.mutedText)
        }
        headerText.addView(meta)

        stageStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Styler.dpInt(context, 12f), 0, Styler.dpInt(context, 2f))
        }
        headerText.addView(
            HorizontalScrollView(context).apply {
                isFocusable = false
                isHorizontalScrollBarEnabled = false
                addView(stageStrip)
            }
        )

        summary = TextView(context).apply {
            textSize = 15f
            setTextColor(colors.accent)
            setPadding(0, Styler.dpInt(context, 2f), 0, 0)
        }
        headerText.addView(summary)

        // Real, focusable buttons -- not only hint-bar chips.
        //
        // The hint bar tells a pad user what A/B/X/Y do, which is necessary but
        // invisible to anyone driving the trackpad, and it leaves the screen's
        // main actions with nothing on it you can point at. These are the same
        // actions, on screen, reachable both ways.
        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(0, Styler.dpInt(context, 10f), 0, 0)
        }
        headerText.addView(actionRow)

        header.addView(headerText)
        root.addView(header)

        overview = TextView(context).apply {
            textSize = 14f
            maxLines = 4
            setPadding(0, Styler.dpInt(context, 12f), 0, 0)
            setTextColor(colors.primaryText)
            setLineSpacing(0f, 1.15f)
        }
        root.addView(overview)

        castLabel = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 16f), 0, Styler.dpInt(context, 6f))
            text = "CAST"
            visibility = View.GONE
        }
        root.addView(castLabel)

        castRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        castScroller = HorizontalScrollView(context).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            addView(castRow)
            visibility = View.GONE
        }
        root.addView(castScroller)

        status = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(0, Styler.dpInt(context, 14f), 0, 0)
            text = "Loading…"
        }
        root.addView(status)

        scroller.addView(root)

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

    override fun onShow() = load()

    override fun onHide() {
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
        if (!::castRow.isInitialized || castRow.childCount == 0) return false
        return castRow.getChildAt(0).requestFocus()
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
        return false
    }

    private fun load() {
        scope.launch {
            when (val result = api.mediaDetail(mediaKey)) {
                is HubResult.Ok -> render(result.value)
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                }
            }
        }
    }

    private fun render(d: MediaDetail) {
        detail = d
        heading.text = d.media.title
        meta.text = describe(d)

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
        overview.text = d.overview

        castRow.removeAllViews()
        if (d.cast.isEmpty()) {
            castLabel.visibility = View.GONE
            castScroller.visibility = View.GONE
        } else {
            castLabel.visibility = View.VISIBLE
            castScroller.visibility = View.VISIBLE
            d.cast.forEach { castRow.addView(castCard(it)) }
        }

        status.setTextColor(colors.mutedText)
        status.text = buildString {
            append(d.availability)
            if (d.cache.hit) append(" · cached ").append(d.cache.ageSeconds).append("s ago")
            if (d.partial.isNotEmpty()) {
                append(" · degraded: ").append(d.partial.joinToString(", ") { it.service })
            }
        }

        loadImage(d.media.backdrop, backdrop)
        loadImage(d.media.poster, poster)

        host?.refreshHints()
    }

    private fun loadImage(hubPath: String, into: ImageView) {
        val url = api.imageUrl(hubPath)
        if (url.isEmpty()) return
        (api as? HubClient)?.imageLoader?.enqueue(
            ImageRequest.Builder(into.context)
                .data(url)
                .target(into)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .build()
        )
    }

    private fun describe(d: MediaDetail): String = buildString {
        if (d.media.year > 0) append(d.media.year)
        if (d.runtimeMinutes > 0) {
            if (isNotEmpty()) append(" · ")
            append(d.runtimeMinutes).append(" min")
        }
        if (d.seasons > 0) {
            if (isNotEmpty()) append(" · ")
            append(d.seasons).append(" seasons")
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

    /** One performer. Focusable, and opens their filmography. */
    private fun castCard(member: CastMember): View {
        val context = castRow.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors)
            Styler.makeFocusable(this)
            isClickable = true
            // One focus target per person, not three.
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val pad = Styler.dpInt(context, 6f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(Styler.dpInt(context, 100f), WRAP).apply {
                rightMargin = Styler.dpInt(context, 8f)
            }
        }

        val photo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
            layoutParams = LinearLayout.LayoutParams(MATCH, Styler.dpInt(context, 112f))
        }
        card.addView(photo)
        card.addView(
            TextView(context).apply {
                textSize = 12f
                maxLines = 2
                setTextColor(colors.primaryText)
                text = member.name
                setPadding(0, Styler.dpInt(context, 4f), 0, 0)
            }
        )
        card.addView(
            TextView(context).apply {
                textSize = 10f
                maxLines = 1
                setTextColor(colors.mutedText)
                text = member.character
            }
        )

        FocusDecorator.attach(card, ringVisible)
        card.setOnClickListener {
            host?.push(PersonScreen(api, member.id, member.name, ringVisible))
        }

        val url = api.imageUrl(member.profile)
        if (url.isNotEmpty()) {
            (api as? HubClient)?.imageLoader?.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .target(photo)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()
            )
        }
        return card
    }

    /**
     * Buttons for what the hub says is possible, plus the one thing it does not
     * model: finding a release by hand.
     */
    private fun buildActions(d: MediaDetail) {
        actionRow.removeAllViews()
        if (d.canRequest) {
            actionRow.addView(
                actionButton(if (flow.busy) "Requesting…" else "Request") {
                    if (!flow.busy) flow.start(mediaKey, d.media.title)
                }
            )
        }
        // Offered on everything, because "why has this not downloaded?" is asked
        // about titles that are already requested at least as often as about new
        // ones. The hub answers with a plain sentence when the title is not in
        // Radarr or Sonarr yet.
        actionRow.addView(actionButton("Find release") { findRelease() })
        // Only when there is one. TMDB has no trailer for plenty of titles --
        // The Mentalist carries nothing but behind-the-scenes clips -- and a
        // button that goes nowhere is worse than no button.
        if (d.trailerUrl.isNotEmpty()) {
            actionRow.addView(actionButton("Trailer") {
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
            if (rootFrame.findFocus() == null) {
                actionRow.getChildAt(0)?.requestFocus()
            }
            host?.refreshHints()
        }
    }


    private fun actionButton(label: String, onClick: () -> Unit): View =
        TextView(actionRow.context).apply {
            text = label
            textSize = 14f
            setTextColor(colors.primaryText)
            background = Styler.chipBackground(context, colors)
            val h = Styler.dpInt(context, 16f)
            val v = Styler.dpInt(context, 8f)
            setPadding(h, v, h, v)
            Styler.makeFocusable(this)
            isClickable = true
            setOnClickListener { onClick() }
            // No scale: these sit in a row of text and growing one shoves the
            // next along.
            FocusDecorator.attach(this, ringVisible, scale = false)
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                rightMargin = Styler.dpInt(context, 8f)
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
        // Sonarr has no "search the whole series" call -- a season number is
        // required -- so the choice has to be made here rather than guessed.
        picker.show(
            title = "Which season?",
            subtitle = d.media.title,
            choices = seasons.map { season ->
                ChoiceOverlay.Choice(
                    id = season.number.toString(),
                    label = season.name.ifEmpty { "Season " + season.number },
                    detail = season.episodeCount.toString() + " episodes" +
                        if (season.year > 0) " · " + season.year else ""
                )
            },
            // Never Specials, which is season 0 and sorts first.
            startIndex = seasons.indexOfFirst { it.number > 0 }.coerceAtLeast(0),
            onCancel = { host?.refreshHints() }
        ) { picked ->
            host?.refreshHints()
            pushReleases(picked.toIntOrNull() ?: 0)
        }
        host?.refreshHints()
    }

    private fun pushReleases(season: Int) {
        host?.push(
            ReleasesScreen(
                api, mediaKey, detail?.media?.title ?: fallbackTitle, season, ringVisible
            )
        )
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
