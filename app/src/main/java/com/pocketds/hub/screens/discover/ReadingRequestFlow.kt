package com.pocketds.hub.screens.discover

import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingRequestOptions
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.ui.FormOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Controller-first BookKeeprr request dialog shared by reading discovery details. */
class ReadingRequestFlow(
    private val api: HubApi,
    private val scope: CoroutineScope,
    private val overlay: () -> FormOverlay,
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
                val needsCount = options.modes.getOrNull(modeIndex)?.requiresTotalBooks == true
                val hasCount = current.rows().any { it.id == "totalBooks" }
                if (needsCount != hasCount) {
                    val totalBooks = current.selectedIndex("totalBooks").takeIf { it >= 0 }?.plus(1) ?: 1
                    current.replace(
                        ReadingRequestForm.rows(
                            options = options,
                            modeIndex = modeIndex,
                            profileIndex = current.selectedIndex("profile"),
                            totalBooks = totalBooks,
                            monitoringIndex = current.selectedIndex("monitoring")
                        )
                    )
                    form.refresh()
                }
            }
        ) { action, current ->
            if (action != "submit") return@show
            form.dismiss()
            onHintsChanged()
            submit(item, options, current)
        }
        onStatus("", false)
        onHintsChanged()
    }

    private fun submit(item: ReadingItem, options: ReadingRequestOptions, model: FormModel) {
        val body = try {
            ReadingRequestForm.body(options, model)
        } catch (error: IllegalStateException) {
            onStatus(error.message ?: "Invalid download choices", true)
            return
        }
        busy = true
        onStatus("Starting BookKeeprr search…", false)
        onHintsChanged()
        scope.launch {
            DebugLog.log("net", "reading request ${item.key} mode=${body.mode} count=${body.totalBooks}")
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
