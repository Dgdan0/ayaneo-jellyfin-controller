package com.pocketds.hub.screens.discover

/** BookKeeprr's inLib flag means tracked, which does not imply an imported file. */
internal object ReadingRequestActionPolicy {
    fun showAction(tracked: Boolean, canRequest: Boolean, hasReleaseTargets: Boolean): Boolean =
        hasReleaseTargets || (!tracked && canRequest)
}
