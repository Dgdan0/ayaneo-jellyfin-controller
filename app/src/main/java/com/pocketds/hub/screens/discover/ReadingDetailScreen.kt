package com.pocketds.hub.screens.discover

import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingRequestTarget
import com.pocketds.hub.model.ReadingType
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.Prefs
import com.pocketds.hub.ui.DetailHeaderView
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ReadingSeriesSelectionOverlay
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provider detail and the explicit BookKeeprr acquisition form.
 *
 * It is the prototype's book request page (`.pg-breq`): the cover at the
 * left (square for an audiobook), "EBOOK · NOT IN YOUR LIBRARY" over the
 * title, Find a download as the white pill, and the form as the glass side
 * sheet.
 */
class ReadingDetailScreen(
    private val api: HubApi,
    private val item: ReadingItem,
    private val ringVisible: () -> Boolean
) : Screen {
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    override val title: String = item.title
    override val focusOnShow: Boolean = false
    /** The title beside the cover heads the page. */
    override val showsOwnTitle = true
    override val pageArtwork: String? get() = resolvedScreen?.pageArtwork ?: item.cover.takeIf(String::isNotBlank)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var rootFrame:FrameLayout
    private var resolvedScreen:com.pocketds.hub.screens.library.ReadingWorkScreen?=null
    private var resolveGeneration=0
    private lateinit var colors: PocketColors
    private lateinit var header: DetailHeaderView
    private lateinit var form: FormOverlay
    private lateinit var seriesForm: ReadingSeriesSelectionOverlay
    private lateinit var status: TextView
    private lateinit var flow: ReadingRequestFlow
    private var requestButton: TextView? = null
    private var requested = false
    private var requestSeriesId = 0
    private var releaseTargets: List<ReadingRequestTarget> = emptyList()
    private var latestAcquisition: ReadingAcquisitionState? = null
    private var statusJob: Job? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        requestSeriesId = Prefs.of(context).getInt("reading_request_series:${item.key}", 0)
        releaseTargets = runCatching { Json.decodeFromString<List<ReadingRequestTarget>>(
            Prefs.of(context).getString("reading_request_targets:${item.key}", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        if (releaseTargets.isEmpty() && requestSeriesId > 0) releaseTargets = listOf(ReadingRequestTarget(requestSeriesId, item.title))
        requested = requestSeriesId > 0
        colors = Theme.colors(context)
        val loader = Artwork.loader(api, context)
        header = DetailHeaderView(context, colors, ringVisible).apply {
            book = true
            squareCover = com.pocketds.hub.screens.library.ReadingBookFacts.coverShape(item.contentType, listOf(item.contentType)) == com.pocketds.hub.screens.library.ReadingBookFacts.CoverShape.SQUARE
            eyebrowView.text = eyebrow()
            titleView.text=item.title
            metadataView.text=buildList {
                if(item.author.isNotBlank())add(item.author)
                if(item.year>0)add(item.year.toString())
                add(ReadingType.label(item.contentType))
            }.joinToString(" · ")
            overview.bind(item.description)
            overview.onChanged={host.refreshHints()}
            bindArtwork("book",null,item.cover.takeIf(String::isNotBlank)?.let(api::imageUrl),loader)
            if(item.contentType in setOf("ebook","audiobook")) formatStatus.bind(com.pocketds.hub.screens.library.ReadingFormatStatus.unknown())
            if(item.inLibrary) {stateView.text="Tracked in BookKeeprr";stateView.visibility=View.VISIBLE}
        }
        if(ReadingRequestActionPolicy.showAction(item.inLibrary, item.actions.contains("request"), releaseTargets.isNotEmpty())) {
            val label = if (requested && releaseTargets.isNotEmpty()) "Choose release" else if (requested) "Open Transfers" else "Find a download"
            // The Books side's main action, gold (PillButton.mainFace).
            requestButton=com.pocketds.hub.ui.PillButton.create(context, colors, label, com.pocketds.hub.ui.AppIcon.DOWNLOAD,
                primary = true, heightDp = 31f, side = com.pocketds.hub.state.ContentMode.BOOKS).apply {
                contentDescription=if (requested && releaseTargets.isNotEmpty()) "Choose release for ${item.title}" else if (requested) "Open Transfers for ${item.title}" else "Choose how to download ${item.title}"
                FocusDecorator.attach(this,ringVisible,scale=false)
                activateOnTap {
                    if (requested && latestAcquisition?.stage == ReadingAcquisitionState.Stage.IMPORTED) host.switchSection(1)
                    else if (requested && (latestAcquisition == null || latestAcquisition?.stage == ReadingAcquisitionState.Stage.AWAITING_CHOICE) && releaseTargets.isNotEmpty())
                        host.push(ReadingReleasePickerScreen(api, releaseTargets, ringVisible))
                    else if (requested) host.switchSection(3)
                    else if (!flow.busy) flow.start(item)
                }
            }
            header.actions.addView(requestButton, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                // The pill lines up with the words; its ring has the room left of it.
                marginStart = -dp(com.pocketds.hub.ui.PillButton.RING_DP.toInt())
            })
        }
        val quiet = com.pocketds.hub.ui.glass.GlassColors.QUIET
        status=TextView(context).apply{textSize=12f;setTextColor(quiet)}
        header.continuation.addView(status)
        val sourceText=listOfNotNull(item.source.takeIf(String::isNotBlank),item.isbn.takeIf(String::isNotBlank)?.let{"ISBN $it"}).joinToString(" · ")
        if(sourceText.isNotBlank())header.continuation.addView(TextView(context).apply{text=sourceText;textSize=12f;setTextColor(quiet);setPadding(0,dp(12),0,0)})

        val frame = FrameLayout(context)
        rootFrame=frame
        frame.addView(
            FocusScrollView(context).apply { addView(header) },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        // The form is the shared side sheet, as a film's request is.
        form = FormOverlay(context, colors, ringVisible, side = com.pocketds.hub.state.ContentMode.BOOKS)
        frame.addView(form, FrameLayout.LayoutParams(MATCH, MATCH))
        seriesForm = ReadingSeriesSelectionOverlay(
            context, colors, ringVisible, loader, api::imageUrl
        )
        frame.addView(seriesForm, FrameLayout.LayoutParams(MATCH, MATCH))
        flow = ReadingRequestFlow(
            api = api,
            scope = scope,
            overlay = { form },
            seriesOverlay = { seriesForm },
            onStatus = { message, failed ->
                status.setTextColor(if (failed) colors.dangerText else quiet)
                status.text = message
            },
            onNotify = host::notify,
            onHintsChanged = host::refreshHints,
            onRequested = { response ->
                requested = true
                requestSeriesId = response.seriesId.takeIf { it > 0 } ?: response.targets.firstOrNull()?.seriesId ?: 0
                releaseTargets = response.targets
                if (requestSeriesId > 0) Prefs.of(context).edit()
                    .putInt("reading_request_series:${item.key}", requestSeriesId).apply()
                Prefs.of(context).edit().putString("reading_request_targets:${item.key}", Json.encodeToString(releaseTargets)).apply()
                requestButton?.apply {
                    text = if (releaseTargets.isEmpty()) "Open Transfers" else "Choose release"
                    contentDescription = "$text for ${item.title}"
                }
                startStatusRefresh()
                if (releaseTargets.isNotEmpty()) host.push(ReadingReleasePickerScreen(api, releaseTargets, ringVisible))
            }
        )
        return frame
    }

    override fun hints(): List<ButtonHint> = resolvedScreen?.hints() ?: when {
        ::header.isInitialized && header.overview.hasFocus() -> listOf(ButtonHint.activate(header.overview.actionHint ?: "Read more"),ButtonHint.back())
        ::seriesForm.isInitialized && seriesForm.isOpen ->
            listOf(ButtonHint.activate("Select"), ButtonHint.secondary("All missing"), ButtonHint.back("Cancel"))
        ::form.isInitialized && form.isOpen ->
            listOf(ButtonHint.activate("Change"), ButtonHint.back("Cancel"))
        requestButton != null ->
            listOf(ButtonHint.activate(when {
                requested && latestAcquisition?.stage == ReadingAcquisitionState.Stage.IMPORTED -> "Open Library"
                requested && releaseTargets.isNotEmpty() && (latestAcquisition == null || latestAcquisition?.stage == ReadingAcquisitionState.Stage.AWAITING_CHOICE) -> "Choose release"
                requested -> "Open Transfers"
                ::flow.isInitialized && flow.busy -> "Working…"
                else -> "Download"
            }), ButtonHint.back())
        else -> listOf(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        resolvedScreen?.let { return it.onPad(action) }
        if (::seriesForm.isInitialized && seriesForm.onPad(action)) {
            host?.refreshHints()
            return true
        }
        if (::form.isInitialized && form.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return header.overview.onPad(action)
    }

    override fun onSystemBack(): Boolean {
        resolvedScreen?.let { return it.onSystemBack() }
        if (::seriesForm.isInitialized && seriesForm.isOpen) {
            seriesForm.onPad(PadAction.Back)
            host?.refreshHints()
            return true
        }
        if (::form.isInitialized && form.isOpen) {
            form.onPad(PadAction.Back)
            host?.refreshHints()
            return true
        }
        return false
    }

    override fun requestInitialFocus(): Boolean = resolvedScreen?.requestInitialFocus() ?: (requestButton?.requestFocus() == true)

    override fun onShow() {
        resolvedScreen?.let { it.onShow();return }
        resolveLibraryWork()
        if (::header.isInitialized) header.overview.collapse()
        if (requestButton != null && resolvedScreen==null) startStatusRefresh()
    }

    override fun onHide() {
        resolveGeneration++
        resolvedScreen?.onHide()
        if (::form.isInitialized && form.isOpen) form.dismiss()
        if (::seriesForm.isInitialized && seriesForm.isOpen) seriesForm.dismiss()
        scope.coroutineContext.cancelChildren()
        statusJob = null
    }

    private fun resolveLibraryWork() {
        if(item.contentType !in setOf("ebook","audiobook")) return
        val generation=++resolveGeneration
        scope.launch {
            val result=api.readingResolve(item.source,item.sourceId,item.isbn)
            if(generation!=resolveGeneration) return@launch
            if(result is HubResult.Ok && result.value.resolved && result.value.workId.isNotBlank()) {
                val h=host ?: return@launch
                val screen=com.pocketds.hub.screens.library.ReadingWorkScreen(api,result.value.workId,item.title,ringVisible)
                statusJob?.cancel();statusJob=null
                rootFrame.removeAllViews()
                rootFrame.addView(screen.onCreateView(h,rootFrame),FrameLayout.LayoutParams(MATCH,MATCH))
                resolvedScreen=screen;screen.onShow();h.refreshHints()
            }
        }
    }

    private fun startStatusRefresh() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (true) {
                when (val result = api.readingDownloads()) {
                    is HubResult.Ok -> {
                        if (requestSeriesId == 0) {
                            requestSeriesId = ReadingAcquisitionState.findSeriesId(
                                item.title, item.author, item.contentType, result.value.items
                            )
                            if (requestSeriesId > 0) {
                                requested = true
                                host?.viewContext?.let { context -> Prefs.of(context).edit()
                                    .putInt("reading_request_series:${item.key}", requestSeriesId).apply() }
                            }
                        }
                        if (requestSeriesId == 0) break
                        val state = ReadingAcquisitionState.from(requestSeriesId, result.value.items, manualSelection = releaseTargets.isNotEmpty())
                        showAcquisitionState(state)
                        if (state.terminal) break
                    }
                    is HubResult.Failed -> if (requested) {
                        status.text = "Download status unavailable · check Transfers"
                    }
                }
                delay(15_000)
            }
        }
    }

    private fun showAcquisitionState(state: ReadingAcquisitionState) {
        latestAcquisition = state
        status.text = state.message
        status.setTextColor(if (state.stage == ReadingAcquisitionState.Stage.FAILED) colors.dangerText
            else com.pocketds.hub.ui.glass.GlassColors.QUIET)
        requestButton?.apply {
            text = state.nextAction
            contentDescription = "${state.nextAction} for ${item.title}. ${state.message}"
        }
        host?.refreshHints()
    }

    override fun onDestroyView() {
        resolvedScreen?.onDestroyView()
        scope.cancel()
        host = null
    }

    /**
     * Glass: "EBOOK · NOT IN YOUR LIBRARY", the kind in the eyebrow's white and
     * where it stands in the accent, as a film you can request says it.
     */
    private fun eyebrow(): CharSequence {
        val kind = when (item.contentType) {
            ReadingType.EBOOK -> "Ebook"
            ReadingType.AUDIOBOOK -> "Audiobook"
            ReadingType.LIGHT_NOVEL -> "Light novel"
            else -> ReadingType.label(item.contentType)
        }
        val where = if (item.inLibrary) "In BookKeeprr" else "Not in your library"
        return android.text.SpannableStringBuilder("$kind · ").apply {
            val from = length
            append(where)
            setSpan(android.text.style.ForegroundColorSpan(colors.accent), from, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun dp(value: Int) = Styler.dpInt(host?.viewContext ?: error("screen detached"), value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
