package com.pocketds.hub.screens.discover

import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.model.RequestOptions
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow
import com.pocketds.hub.ui.FormOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asking for a title: fetch the choices, show the form, send the request.
 *
 * Shared by the Discover rows and the detail screen. Ⓧ on a poster opens this
 * directly rather than making you open the title first and press Request again
 * — but it opens the *form*, not a one-press request, because a handheld cursor
 * gets flung around by a thumbstick and the last thing this should be is an
 * irreversible action sitting under it.
 */
class RequestFlow(
    private val api: HubApi,
    private val scope: CoroutineScope,
    private val overlay: () -> FormOverlay,
    private val onStatus: (String, Boolean) -> Unit,
    private val onNotify: (String) -> Unit,
    private val onHintsChanged: () -> Unit,
    /** Called after a request lands, so a screen can reload its own view of it. */
    private val onRequested: () -> Unit = {}
) {

    var busy: Boolean = false
        private set

    /**
     * Fetch the options, then show the dialog.
     *
     * The options are asked for rather than assumed because profile ids are
     * per-install: "4" is HD-1080p here and something else on any other stack.
     * They come back cached for ten minutes, so this is instant after the first
     * time.
     */
    fun start(key: String, fallbackTitle: String) {
        if (busy) return
        onStatus("Loading request options…", false)
        scope.launch {
            when (val result = api.requestOptions(key)) {
                is HubResult.Ok -> {
                    onStatus("", false)
                    showForm(key, fallbackTitle, result.value)
                }
                is HubResult.Failed -> {
                    // Falling back to a plain request beats refusing to request
                    // at all: the defaults are what the old one-press flow used,
                    // and they are still what most people want.
                    DebugLog.log("net", "options failed: " + result.message)
                    onStatus(result.message + " — requesting with the defaults", true)
                    submit(key, fallbackTitle, null, null, null, null)
                }
            }
        }
    }

    private fun showForm(key: String, fallbackTitle: String, opts: RequestOptions) {
        val form = overlay()
        val model = FormModel(buildRows(opts, allSeasons = true))
        form.show(
            title = "Request " + opts.title.ifEmpty { fallbackTitle },
            subtitle = buildString {
                append(opts.serverName)
                if (opts.seasons.isNotEmpty()) {
                    append(" · ").append(plural(opts.seasons.size, "season"))
                    val episodes = opts.seasons.sumOf { it.episodeCount }
                    if (episodes > 0) append(" · ").append(plural(episodes, "episode"))
                }
            },
            model = model,
            onCancel = onHintsChanged,
            onChanged = { current ->
                // Ticking "all seasons" hides the per-season rows rather than
                // leaving a column of checkboxes that no longer mean anything.
                if (opts.seasons.isEmpty()) return@show
                val all = current.isChecked("allSeasons")
                val showingSeasons = current.rows().any { it.id.startsWith("season:") }
                if (all == showingSeasons) {
                    current.replace(
                        buildRows(
                            opts, all,
                            current.selectedIndex("profile"),
                            current.selectedIndex("folder"),
                            current
                        )
                    )
                    form.refresh()
                }
            }
        ) { actionId, current ->
            form.dismiss()
            onHintsChanged()
            if (actionId == "submit") submitFromForm(key, fallbackTitle, opts, current)
        }
        onHintsChanged()
    }

    private fun buildRows(
        opts: RequestOptions,
        allSeasons: Boolean,
        profileIndex: Int = -1,
        folderIndex: Int = -1,
        previous: FormModel? = null
    ): List<FormRow> {
        val rows = mutableListOf<FormRow>()
        if (opts.profiles.isNotEmpty()) {
            rows.add(
                FormRow.Choice(
                    id = "profile",
                    label = "Quality",
                    options = opts.profiles.map { it.label },
                    selected = if (profileIndex >= 0) profileIndex else opts.defaultProfileIndex
                )
            )
        }
        if (opts.rootFolders.isNotEmpty()) {
            rows.add(
                FormRow.Choice(
                    id = "folder",
                    label = "Folder",
                    options = opts.rootFolders.map { it.label },
                    // Free space belongs next to the choice, not on another
                    // screen: "which folder" and "is there room" are one
                    // question on a media box.
                    details = opts.rootFolders.map { Fmt.bytes(it.freeSpaceBytes) + " free" },
                    selected = if (folderIndex >= 0) folderIndex else opts.defaultFolderIndex
                )
            )
        }
        if (opts.seasons.isNotEmpty()) {
            rows.add(FormRow.Toggle("allSeasons", "All seasons", checked = allSeasons))
            if (!allSeasons) {
                opts.seasons.forEach { season ->
                    val id = "season:" + season.number
                    rows.add(
                        FormRow.Toggle(
                            id = id,
                            label = season.name.ifEmpty { "Season " + season.number },
                            checked = previous?.isChecked(id) ?: false,
                            detail = plural(season.episodeCount, "episode") +
                                if (season.year > 0) " · " + season.year else ""
                        )
                    )
                }
            }
        }
        rows.add(FormRow.Action("submit", "Request"))
        return rows
    }

    private fun submitFromForm(
        key: String,
        fallbackTitle: String,
        opts: RequestOptions,
        model: FormModel
    ) {
        val profile = opts.profiles.getOrNull(model.selectedIndex("profile"))?.id
        val folder = opts.rootFolders.getOrNull(model.selectedIndex("folder"))?.path
        val seasons: JsonElement? = when {
            opts.seasons.isEmpty() -> null
            model.isChecked("allSeasons") -> JsonPrimitive("all")
            else -> {
                val numbers = model.checkedIds("season:")
                    .mapNotNull { it.removePrefix("season:").toIntOrNull() }
                // Jellyseerr rejects a series request naming no seasons, so an
                // empty tick list means "all" rather than an error.
                if (numbers.isEmpty()) {
                    JsonPrimitive("all")
                } else {
                    JsonArray(numbers.map { JsonPrimitive(it) })
                }
            }
        }
        submit(key, fallbackTitle, profile, folder, opts.serverId, seasons)
    }

    private fun submit(
        key: String,
        fallbackTitle: String,
        profileId: Int?,
        rootFolder: String?,
        serverId: Int?,
        seasons: JsonElement?
    ) {
        busy = true
        onHintsChanged()
        onStatus("Requesting…", false)
        scope.launch {
            DebugLog.log("net", "requesting $key profile=$profileId")
            when (val result = api.requestMedia(key, profileId, rootFolder, serverId, seasons)) {
                is HubResult.Ok -> {
                    busy = false
                    onNotify(result.value.message.ifEmpty { "Requested $fallbackTitle" })
                    onStatus(result.value.message, false)
                    onRequested()
                }
                is HubResult.Failed -> {
                    busy = false
                    // The hub's own wording. "You have already requested this"
                    // beats a generic failure message.
                    onStatus(result.message, true)
                    onNotify(result.message)
                }
            }
            onHintsChanged()
        }
    }

    private fun plural(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"
}
