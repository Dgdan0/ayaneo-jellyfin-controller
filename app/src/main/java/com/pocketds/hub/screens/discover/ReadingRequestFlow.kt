package com.pocketds.hub.screens.discover

import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingRequestOptions
import com.pocketds.hub.model.ReadingSeriesPreview
import com.pocketds.hub.model.ReadingSeriesPreviewResponse
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.ReadingSeriesSelectionOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Controller-first BookKeeprr request dialog shared by reading discovery details. */
class ReadingRequestFlow(
    private val api: HubApi,
    private val scope: CoroutineScope,
    private val overlay: () -> FormOverlay,
    private val seriesOverlay: () -> ReadingSeriesSelectionOverlay,
    private val onStatus: (String, Boolean) -> Unit,
    private val onNotify: (String) -> Unit,
    private val onHintsChanged: () -> Unit,
    private val onRequested: () -> Unit = {}
) {
    var busy = false
        private set

    fun start(item: ReadingItem) {
        if (busy || !item.actions.contains("request")) return
        busy = true
        onStatus("Loading download choices…", false)
        onHintsChanged()
        scope.launch {
            when (val result = api.readingRequestOptions(item.key)) {
                is HubResult.Ok -> {
                    busy = false
                    showForm(item, result.value)
                }
                is HubResult.Failed -> {
                    busy = false
                    onStatus(result.message, true)
                    onNotify(result.message)
                }
            }
            onHintsChanged()
        }
    }

    private fun showForm(item: ReadingItem, options: ReadingRequestOptions) {
        if (options.qualityProfiles.isEmpty() || options.modes.isEmpty()) {
            onStatus("BookKeeprr has no usable quality profile", true)
            return
        }
        val form = overlay()
        val model = FormModel(ReadingRequestForm.rows(options))
        form.show(
            title = "Download ${options.title.ifEmpty { item.title }}",
            subtitle = options.author,
            model = model,
            onCancel = onHintsChanged,
            onChanged = { current ->
                val modeIndex = current.selectedIndex("mode").coerceAtLeast(0)
                current.replace(
                    ReadingRequestForm.rows(
                        options = options,
                        modeIndex = modeIndex,
                        profileIndex = current.selectedIndex("profile"),
                        monitoringIndex = current.selectedIndex("monitoring")
                    )
                )
                form.refresh()
            }
        ) { action, current ->
            if (action != "submit") return@show
            val body = try {
                ReadingRequestForm.body(options, current)
            } catch (error: IllegalStateException) {
                onStatus(error.message ?: "Invalid download choices", true)
                return@show
            }
            form.dismiss()
            onHintsChanged()
            if (options.modes.firstOrNull { it.id == body.mode }?.requiresSeriesPreview == true) {
                loadSeriesPreview(item, body)
            } else {
                submit(item, body)
            }
        }
        onStatus("", false)
        onHintsChanged()
    }

    private fun loadSeriesPreview(item: ReadingItem, body: com.pocketds.hub.model.ReadingCreateRequestBody) {
        busy = true
        onStatus("Verifying the series books…", false)
        onHintsChanged()
        scope.launch {
            when (val result = api.readingSeriesPreview(item.key)) {
                is HubResult.Ok -> {
                    busy = false
                    if (result.value.scopes.isEmpty()) {
                        onStatus("No verified books were found for this series", true)
                    } else if (result.value.scopes.size == 1) {
                        showSeriesSelection(item, body, result.value.scopes.single())
                    } else {
                        showScopeChoice(item, body, result.value)
                    }
                }
                is HubResult.Failed -> {
                    busy = false
                    onStatus(result.message, true)
                    onNotify(result.message)
                }
            }
            onHintsChanged()
        }
    }

    private fun showScopeChoice(
        item: ReadingItem,
        body: com.pocketds.hub.model.ReadingCreateRequestBody,
        response: ReadingSeriesPreviewResponse
    ) {
        val form = overlay()
        val model = FormModel(ReadingSeriesScopeForm.rows(response))
        form.show(
            title = "Choose the collection",
            subtitle = "This book belongs to more than one verified series list.",
            model = model,
            onCancel = onHintsChanged,
            onChanged = { current ->
                val selected = current.selectedIndex("series_scope").coerceAtLeast(0)
                current.replace(ReadingSeriesScopeForm.rows(response, selected))
                form.refresh()
            }
        ) { action, current ->
            if (action != "review") return@show
            val selected = ReadingSeriesScopeForm.selected(response, current) ?: return@show
            form.dismiss()
            showSeriesSelection(item, body, selected) {
                showScopeChoice(item, body, response)
            }
        }
        onStatus("", false)
        onHintsChanged()
    }

    private fun showSeriesSelection(
        item: ReadingItem,
        body: com.pocketds.hub.model.ReadingCreateRequestBody,
        preview: ReadingSeriesPreview,
        onCancel: () -> Unit = onHintsChanged
    ) {
        val chooser = seriesOverlay()
        chooser.show(preview = preview, onCancel = onCancel) { bookIds ->
            chooser.dismiss()
            onHintsChanged()
            submit(item, body.copy(seriesId = preview.seriesId, bookIds = bookIds))
        }
        onStatus("", false)
        onHintsChanged()
    }

    private fun submit(item: ReadingItem, body: com.pocketds.hub.model.ReadingCreateRequestBody) {
        busy = true
        onStatus(if (body.bookIds.isEmpty()) "Starting BookKeeprr search…" else "Starting ${body.bookIds.size} book searches…", false)
        onHintsChanged()
        scope.launch {
            DebugLog.log("net", "reading request ${item.key} mode=${body.mode} books=${body.bookIds.size}")
            when (val result = api.requestReading(body)) {
                is HubResult.Ok -> {
                    onStatus(result.value.message, false)
                    onNotify(result.value.message.ifEmpty { "BookKeeprr is searching for ${item.title}" })
                    onRequested()
                }
                is HubResult.Failed -> {
                    onStatus(result.message, true)
                    onNotify(result.message)
                }
            }
            busy = false
            onHintsChanged()
        }
    }
}
