package com.pocketds.hub.reader

import com.pocketds.hub.ui.ChoiceOverlay
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Null means cancelled; a ReadingResume with a null location means explicitly start at the beginning. */
suspend fun chooseReadingResume(
    overlay:ChoiceOverlay,progress:ReadingProgress,key:ReadingCheckpointKey,resume:ReadingResume
):ReadingResume? {
    if (!resume.conflict && !resume.unavailable) return resume
    val checkpoint=progress.store.read(key)
    return suspendCancellableCoroutine { continuation ->
        val choices=if(resume.conflict) listOf(
            ChoiceOverlay.Choice("local","Continue on this device",checkpoint?.local?.label().orEmpty()),
            ChoiceOverlay.Choice("server","Use server position",checkpoint?.remote?.label() ?: "Beginning")
        ) else listOf(ChoiceOverlay.Choice("start","Start from the beginning","Your server position could not be checked. This choice is saved locally."))
        overlay.show(if(resume.conflict) "Choose reading position" else "Reading position unavailable",
            if(resume.conflict) "Another reader moved your position. Both positions will be kept on this device." else "Go back to retry, or explicitly start here.",
            choices,onCancel={if(continuation.isActive)continuation.resume(null)}) { selected ->
            val location=when(selected) {
                "local" -> progress.store.chooseLocal(key).local.also { progress.requestSync() }
                "server" -> progress.store.chooseRemote(key).local
                else -> null
            }
            if(continuation.isActive)continuation.resume(ReadingResume(location))
        }
        continuation.invokeOnCancellation { overlay.post { overlay.dismiss() } }
    }
}
