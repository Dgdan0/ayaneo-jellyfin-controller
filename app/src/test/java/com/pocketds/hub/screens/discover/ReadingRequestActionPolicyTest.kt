package com.pocketds.hub.screens.discover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingRequestActionPolicyTest {
    @Test fun trackedBookWithPendingChoiceCanReopenReleasePicker() {
        assertTrue(ReadingRequestActionPolicy.showAction(
            tracked = true, canRequest = false, hasReleaseTargets = true
        ))
    }

    @Test fun ordinaryUntrackedBookCanStartRequest() {
        assertTrue(ReadingRequestActionPolicy.showAction(
            tracked = false, canRequest = true, hasReleaseTargets = false
        ))
    }

    @Test fun trackedBookWithoutKnownTargetsDoesNotOfferDuplicateRequest() {
        assertFalse(ReadingRequestActionPolicy.showAction(
            tracked = true, canRequest = false, hasReleaseTargets = false
        ))
    }
}
