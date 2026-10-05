package com.pocketds.hub.playback

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.PlaybackSelectBody
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.TrackPresentation
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/** Pre-play source, language, subtitle and quality selection. */
class PlaybackOptionsScreen(
    private val api: HubApi,
    private val itemId: String,
    private val startMode: String,
    private val ringVisible: () -> Boolean,
    private val localPlan: PlaybackPrepareResponse? = null
) : Screen {
    override val title = "Audio & subtitles"
    override val focusOnShow = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var overlay: ChoiceOverlay
    private var plan: PlaybackPrepareResponse? = null
    private var job: Job? = null
    private var handedOff = false
    private var qualityCap = 0

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            status = TextView(context).apply {
                text = "Preparing playback options…"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
            }
            addView(status, FrameLayout.LayoutParams(MATCH, MATCH))
            this@PlaybackOptionsScreen.overlay = ChoiceOverlay(context, colors, ringVisible)
            addView(this@PlaybackOptionsScreen.overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onShow() {
        if (plan == null && job?.isActive != true) prepare()
        else plan?.let(::showMain)
    }

    override fun onHide() {
        if (::overlay.isInitialized) overlay.dismiss()
        job?.cancel()
        job = null
    }

    override fun onDestroyView() {
        val abandoned = plan?.takeUnless { handedOff || localPlan != null }
        scope.cancel()
        if (abandoned != null) CoroutineScope(Dispatchers.IO).launch {
            api.deletePlayback(abandoned.sessionId)
        }
    }

    override fun hints() = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else if (plan == null && job?.isActive != true) {
        listOf(ButtonHint.refresh("Retry (Select)"), ButtonHint.back())
    } else listOf(ButtonHint.back())

    override fun onPad(action: PadAction): Boolean = when {
        ::overlay.isInitialized && overlay.onPad(action) -> true
        // The failure line has always said "Select retries"; this makes it true.
        action == PadAction.Refresh && plan == null && job?.isActive != true -> { prepare(); true }
        else -> false
    }

    private fun prepare() {
        status.showStatus(StatusMessage("Negotiating with Jellyfin…"), colors)
        host.refreshHints()
        job = scope.launch {
            if (localPlan != null) {
                plan = applyRememberedSelection(localPlan)
                plan?.let(::showMain)
                return@launch
            }
            val userId = HubSettings.userId(host.viewContext)
            val localPosition = PlaybackProgressStore.resumePosition(
                host.viewContext,
                userId,
                itemId,
                startMode
            )
            when (val result = api.preparePlayback(
                itemId,
                PlaybackCapabilitiesProbe.prepare(host.viewContext, startMode, localPosition)
            )) {
                is HubResult.Ok -> {
                    plan = applyRememberedSelection(result.value)
                    plan?.let(::showMain)
                }
                is HubResult.Failed ->
                    status.showStatus(StatusText.failed(result.message, result.kind, hasData = false), colors)
            }
            job = null
            host.refreshHints()
        }
    }

    private suspend fun applyRememberedSelection(initial: PlaybackPrepareResponse): PlaybackPrepareResponse {
        val user = HubSettings.userId(host.viewContext)
        val scopeId = initial.item.seriesId.ifEmpty { initial.item.id }
        val remembered = PlaybackPreferences.get(host.viewContext, user, scopeId)
        val audio = PlaybackPreferences.preferredAudio(initial, remembered)
        val subtitle = PlaybackPreferences.preferredSubtitle(initial, remembered)
        val desiredSubtitle = when (remembered.subtitlesEnabled) {
            false -> -1
            true -> subtitle?.index
            null -> initial.selectedSubtitleIndex
        }
        val desiredAudio = audio?.index ?: initial.selectedAudioIndex
        if (localPlan != null) return initial.copy(selectedAudioIndex = desiredAudio, selectedSubtitleIndex = desiredSubtitle)
        if (desiredAudio == initial.selectedAudioIndex && desiredSubtitle == initial.selectedSubtitleIndex) {
            return initial
        }
        return when (val result = api.selectPlayback(
            initial.sessionId,
            PlaybackRules.selection(initial, audioStreamIndex = desiredAudio, subtitleStreamIndex = desiredSubtitle)
        )) {
            is HubResult.Ok -> result.value
            is HubResult.Failed -> initial
        }
    }

    private fun showMain(value: PlaybackPrepareResponse) {
        val audio = value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex }?.let { TrackPresentation.of(it).title }
            ?: "Jellyfin default"
        val subtitle = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }?.let { TrackPresentation.of(it).title }
            ?: "Off"
        val source = value.sources.firstOrNull { it.id == value.selectedMediaSourceId }
        overlay.show(
            title = value.item.displayTitle(),
            subtitle = "Choose the streams before playback starts.",
            choices = listOf(
                ChoiceOverlay.Choice("play", if (value.positionMillis > 0) "Resume" else "Play", if (value.positionMillis > 0) Fmt.clock(value.positionMillis) else ""),
                ChoiceOverlay.Choice("source", "Media version", source?.let { PlayerLabels.source(it.name, it.container, it.bitrate) }.orEmpty()),
                ChoiceOverlay.Choice("audio", "Audio", audio),
                ChoiceOverlay.Choice("subtitle", "Subtitles", subtitle),
                ChoiceOverlay.Choice("quality", "Quality", PlayerLabels.quality(value))
            ).filter { localPlan == null || it.id !in setOf("source", "quality") },
            onCancel = { host.back() }
        ) { choice ->
            when (choice) {
                "play" -> {
                    handedOff = true
                    host.back()
                    host.playPrepared(value)
                }
                "source" -> showSources(value)
                "audio" -> showAudio(value)
                "subtitle" -> showSubtitles(value)
                "quality" -> showQuality(value)
            }
        }
        host.refreshHints()
    }

    private fun showSources(value: PlaybackPrepareResponse) = submenu(
        "Media version",
        value.sources.map { ChoiceOverlay.Choice(it.id, it.name.ifEmpty { it.container.uppercase() }, PlayerLabels.source("", it.container, it.bitrate), selected = it.id == value.selectedMediaSourceId) },
        value.sources.indexOfFirst { it.id == value.selectedMediaSourceId }
    ) { source -> select(PlaybackRules.selection(value, mediaSourceId = source)) }

    private fun showAudio(value: PlaybackPrepareResponse) = submenu(
        "Audio",
        value.audioTracks.map { val copy=TrackPresentation.of(it);ChoiceOverlay.Choice(it.index.toString(), copy.title, copy.detail, selected=it.index==value.selectedAudioIndex) },
        value.audioTracks.indexOfFirst { it.index == value.selectedAudioIndex }
    ) { index -> select(PlaybackRules.selection(value, audioStreamIndex = index.toInt())) }

    private fun showSubtitles(value: PlaybackPrepareResponse) {
        val choices = listOf(ChoiceOverlay.Choice("-1", "Off", selected = value.selectedSubtitleIndex == null || value.selectedSubtitleIndex == -1)) + value.subtitleTracks.map {
            val copy=TrackPresentation.of(it)
            ChoiceOverlay.Choice(it.index.toString(),copy.title,copy.detail,selected=it.index==value.selectedSubtitleIndex)
        }
        submenu("Subtitles", choices, choices.indexOfFirst { it.id.toInt() == value.selectedSubtitleIndex }) {
            select(PlaybackRules.selection(value, subtitleStreamIndex = it.toInt()))
        }
    }

    private fun showQuality(value: PlaybackPrepareResponse) = submenu(
        "Quality",
        PlaybackRules.qualities.map { ChoiceOverlay.Choice(it.bitrate.toString(), it.label, selected=it.bitrate==qualityCap) },
        PlaybackRules.qualities.indexOfFirst { it.bitrate==qualityCap }
    ) { bitrate -> select(PlaybackRules.selection(value, maxBitrate = bitrate.toInt())) }

    private fun submenu(
        title: String,
        choices: List<ChoiceOverlay.Choice>,
        selected: Int,
        pick: (String) -> Unit
    ) {
        overlay.show(title, "", choices, selected.coerceAtLeast(0), { plan?.let(::showMain) }, pick)
        host.refreshHints()
    }

    private fun select(body: PlaybackSelectBody) {
        val current = plan ?: return
        if (localPlan != null) {
            val updated = current.copy(
                selectedAudioIndex = body.audioStreamIndex ?: current.selectedAudioIndex,
                selectedSubtitleIndex = body.subtitleStreamIndex ?: current.selectedSubtitleIndex
            )
            plan = updated
            remember(updated)
            showMain(updated)
            return
        }
        overlay.dismiss()
        status.visibility = View.VISIBLE
        status.text = "Applying playback option…"
        job = scope.launch {
            when (val result = api.selectPlayback(current.sessionId, body)) {
                is HubResult.Ok -> {
                    body.maxBitrate?.let { qualityCap=it }
                    plan = result.value
                    remember(result.value)
                    showMain(result.value)
                }
                is HubResult.Failed -> {
                    host.notify(result.message)
                    showMain(current)
                }
            }
            job = null
        }
    }

    private fun remember(value: PlaybackPrepareResponse) {
        PlaybackPreferences.remember(
            host.viewContext,
            HubSettings.userId(host.viewContext),
            value,
            value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex },
            value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }
        )
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
