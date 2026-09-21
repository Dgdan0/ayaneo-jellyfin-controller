package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ReadingDownloadItem
import com.pocketds.hub.model.ReadingTransferAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingTransferActionsTest {
    @Test
    fun `failed transfer offers retry before cancel`() {
        val item = ReadingDownloadItem(status = "failed", failed = true, actions = listOf("retry", "cancel"))
        assertEquals(listOf(ReadingTransferAction.RETRY, ReadingTransferAction.CANCEL), item.availableActions)
    }

    @Test
    fun `imported transfer has no destructive action`() {
        val item = ReadingDownloadItem(status = "imported", actions = emptyList())
        assertEquals(emptyList<ReadingTransferAction>(), item.availableActions)
    }

    @Test
    fun `unknown actions from a newer hub are ignored`() {
        val item = ReadingDownloadItem(actions = listOf("pause", "cancel"))
        assertEquals(listOf(ReadingTransferAction.CANCEL), item.availableActions)
    }
}
