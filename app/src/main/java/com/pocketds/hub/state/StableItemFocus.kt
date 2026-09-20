package com.pocketds.hub.state

/**
 * Keeps RecyclerView focus attached to an item ID across navigation and reloads.
 *
 * Opening a child screen pins the selected item. RecyclerView can briefly focus
 * another recycled child while the parent screen is hidden; those events must
 * not replace the item that should receive focus when the user returns.
 */
class StableItemFocus {
    private var position = 0
    private var itemId = ""
    private var pinnedItemId: String? = null

    fun remember(position: Int, itemId: String) {
        if (pinnedItemId != null || position < 0 || itemId.isBlank()) return
        this.position = position
        this.itemId = itemId
    }

    fun pin(position: Int, itemId: String) {
        if (itemId.isBlank()) return
        this.position = position.coerceAtLeast(0)
        this.itemId = itemId
        pinnedItemId = itemId
    }

    fun resolve(itemIds: List<String>): Int {
        if (itemIds.isEmpty()) return -1
        pinnedItemId?.let { pinned ->
            val pinnedIndex = itemIds.indexOf(pinned)
            if (pinnedIndex >= 0) return pinnedIndex

            // The opened item disappeared while Details was visible. Return to
            // the grid's first valid item and allow normal focus updates again.
            pinnedItemId = null
            position = 0
            itemId = itemIds.first()
            return 0
        }

        val stableIndex = itemIds.indexOf(itemId)
        return if (stableIndex >= 0) stableIndex else position.coerceIn(itemIds.indices)
    }

    fun confirmRestored(position: Int, itemId: String) {
        val pinned = pinnedItemId
        if (pinned != null && pinned != itemId) return
        pinnedItemId = null
        remember(position, itemId)
    }

    fun reset() {
        position = 0
        itemId = ""
        pinnedItemId = null
    }
}
