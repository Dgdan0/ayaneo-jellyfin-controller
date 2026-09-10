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
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Pre-play source, language, subtitle and quality selection. */
class PlaybackOptionsScreen(
    private val api: HubApi,
    private val itemId: String,
    private val startMode: String,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title = "Playback options"
    override val focusOnShow = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var overlay: ChoiceOverlay
    private var plan: PlaybackPrepareResponse? = null
    private var job: Job? = null
    private var handedOff = false

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
        val abandoned = plan?.takeUnless { handedOff }
        scope.cancel()
        if (abandoned != null) CoroutineScope(Dispatchers.IO).launch {
            api.deletePlayback(abandoned.sessionId)
        }
    }

    override fun hints() = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else listOf(ButtonHint.back())

    override fun onPad(action: PadAction): Boolean =
        if (::overlay.isInitialized && overlay.onPad(action)) true else false

    private fun prepare() {
        status.text = "Negotiating with Jellyfin…"
        job = scope.launch {
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
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message + " · Select retries"
                }
            }
            job = null
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
        if (desiredAudio == initial.selectedAudioIndex && desiredSubtitle == initial.selectedSubtitleIndex) {
            return initial
        }
        return when (val result = api.selectPlayback(
            initial.sessionId,
            PlaybackSelectBody(
                positionMillis = initial.positionMillis,
                audioStreamIndex = desiredAudio,
                subtitleStreamIndex = desiredSubtitle
            )
        )) {
            is HubResult.Ok -> result.value
            is HubResult.Failed -> initial
        }
    }

    private fun showMain(value: PlaybackPrepareResponse) {
        val audio = value.audioTracks.firstOrNull { it.index == value.selectedAudioIndex }?.label
            ?: "Jellyfin default"
        val subtitle = value.subtitleTracks.firstOrNull { it.index == value.selectedSubtitleIndex }?.label
            ?: "Off"
        val source = value.sources.firstOrNull { it.id == value.selectedMediaSourceId }
        overlay.show(
            title = value.item.displayTitle(),
            subtitle = "Choose the streams before playback starts.",
            choices = listOf(
                ChoiceOverlay.Choice("play", if (value.positionMillis > 0) "Resume" else "Play", time(value.positionMillis)),
                ChoiceOverlay.Choice("source", "Media version", source?.let { sourceLabel(it.name, it.container, it.bitrate) }.orEmpty()),
                ChoiceOverlay.Choice("audio", "Audio", audio),
                ChoiceOverlay.Choice("subtitle", "Subtitles", subtitle),
                ChoiceOverlay.Choice("quality", "Quality", qualityLabel(value))
            ),
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
        value.sources.map { ChoiceOverlay.Choice(it.id, it.name.ifEmpty { it.container.uppercase() }, sourceLabel("", it.container, it.bitrate)) },
        value.sources.indexOfFirst { it.id == value.selectedMediaSourceId }
    ) { source -> select(PlaybackSelectBody(value.positionMillis, mediaSourceId = source)) }

    private fun showAudio(value: PlaybackPrepareResponse) = submenu(
        "Audio",
        value.audioTracks.map { ChoiceOverlay.Choice(it.index.toString(), it.label, trackDetail(it.codec, it.channels)) },
        value.audioTracks.indexOfFirst { it.index == value.selectedAudioIndex }
    ) { index -> select(PlaybackSelectBody(value.positionMillis, audioStreamIndex = index.toInt())) }

    private fun showSubtitles(value: PlaybackPrepareResponse) {
        val choices = listOf(ChoiceOverlay.Choice("-1", "Off")) + value.subtitleTracks.map {
            ChoiceOverlay.Choice(it.index.toString(), it.label, buildList {
                if (it.forced) add("Forced")
                if (it.hearingImpaired) add("Hearing impaired")
                if (it.external) add("External")
                if (it.codec.isNotEmpty()) add(it.codec.uppercase())
            }.joinToString(" · "))
        }
        submenu("Subtitles", choices, choices.indexOfFirst { it.id.toInt() == value.selectedSubtitleIndex }) {
            select(PlaybackSelectBody(value.positionMillis, subtitleStreamIndex = it.toInt()))
        }
    }

    private fun showQuality(value: PlaybackPrepareResponse) = submenu(
        "Quality",
        PlaybackRules.qualities.map { ChoiceOverlay.Choice(it.bitrate.toString(), it.label) },
        0
    ) { bitrate -> select(PlaybackSelectBody(value.positionMillis, maxBitrate = bitrate.toInt())) }

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
        overlay.dismiss()
        status.visibility = View.VISIBLE
        status.text = "Applying playback option…"
        job = scope.launch {
            when (val result = api.selectPlayback(current.sessionId, body)) {
                is HubResult.Ok -> {
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

    private fun qualityLabel(value: PlaybackPrepareResponse): String = buildString {
        append(if (value.playMethod.isEmpty()) "Original" else value.playMethod)
        if (value.width > 0 && value.height > 0) append(" · ${value.width}×${value.height}")
        if (value.bitrate > 0) append(" · %.1f Mbps".format(value.bitrate / 1_000_000.0))
    }

    private fun sourceLabel(name: String, container: String, bitrate: Int) = buildList {
        if (name.isNotEmpty()) add(name)
        if (container.isNotEmpty()) add(container.uppercase())
        if (bitrate > 0) add("%.1f Mbps".format(bitrate / 1_000_000.0))
    }.joinToString(" · ")

    private fun trackDetail(codec: String, channels: Int) = buildList {
        if (codec.isNotEmpty()) add(codec.uppercase())
        if (channels > 0) add("$channels channels")
    }.joinToString(" · ")

    private fun time(milliseconds: Long): String {
        if (milliseconds <= 0) return ""
        val total = milliseconds / 1_000
        return if (total >= 3_600) "%d:%02d:%02d".format(total / 3_600, total % 3_600 / 60, total % 60)
            else "%d:%02d".format(total / 60, total % 60)
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
