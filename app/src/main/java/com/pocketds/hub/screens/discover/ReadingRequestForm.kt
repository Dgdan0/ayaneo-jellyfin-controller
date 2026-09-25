package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingCreateRequestBody
import com.pocketds.hub.model.ReadingRequestOptions
import com.pocketds.hub.state.FormModel
import com.pocketds.hub.state.FormRow

/** Pure mapping between the Hub's acquisition choices and the controller form. */
object ReadingRequestForm {
    fun rows(
        options: ReadingRequestOptions,
        modeIndex: Int = 0,
        profileIndex: Int = -1
    ): List<FormRow> {
        val rows = mutableListOf<FormRow>()
        if (options.modes.isNotEmpty()) {
            rows += FormRow.Choice(
                id = "mode",
                label = "Download",
                options = options.modes.map { it.label },
                selected = modeIndex.coerceIn(0, options.modes.lastIndex)
            )
        }
        val selectedMode = options.modes.getOrNull(modeIndex)
        if (options.qualityProfiles.isNotEmpty()) {
            val selected = if (profileIndex >= 0) profileIndex else options.defaultProfileIndex
            rows += FormRow.Choice(
                id = "profile",
                label = "Quality",
                options = options.qualityProfiles.map { it.label },
                details = options.qualityProfiles.map {
                    if (it.preferCompleteBatches) "Prefers complete series" else ""
                },
                selected = selected.coerceIn(0, options.qualityProfiles.lastIndex)
            )
        }
        if (options.qualityProfiles.isNotEmpty() && options.modes.isNotEmpty()) {
            rows += FormRow.Action(
                "submit",
                if (selectedMode?.requiresSeriesPreview == true) "Review books" else "Choose release"
            )
        }
        return rows
    }

    fun body(options: ReadingRequestOptions, model: FormModel): ReadingCreateRequestBody {
        val mode = options.modes.getOrNull(model.selectedIndex("mode"))
            ?: error("request mode is unavailable")
        val profile = options.qualityProfiles.getOrNull(model.selectedIndex("profile"))
            ?: error("quality profile is unavailable")
        return ReadingCreateRequestBody(
            key = options.key,
            mode = mode.id,
            qualityProfileId = profile.id,
            monitoring = "none"
        )
    }
}
