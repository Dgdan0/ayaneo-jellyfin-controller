package com.pocketds.hub.screens.library

import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One side's library order being changed (#15), on a Glass Library root or in
 * Settings › Libraries: the [LibraryArrangeSession], its saves, and A to Z.
 *
 * A move shows at once ([onOrder]) and saves behind it with
 * `PUT /v1/library/order`, one save at a time with only the newest order
 * waiting ([LibraryOrderQueue]). A save that fails puts back the order the hub
 * has and says why ([onFailed]). A to Z sends an empty order and then asks the
 * screen to read the hub's again ([onReset]): the app never sorts libraries
 * itself.
 *
 * [scope] must outlive the screen's onHide, so a save already sent is never
 * cancelled half way and its answer still lands.
 */
class LibraryOrderEditor(
    val side: ContentMode,
    private val api: HubApi,
    private val scope: CoroutineScope,
    /** The order to show now: after a move, a failed save, or a save landing (hints may change). */
    private val onOrder: (List<String>) -> Unit,
    /** A save failed and the order went back; the screen says so. */
    private val onFailed: (String) -> Unit,
    /** The hub went back to A to Z; the screen reads its order again. */
    private val onReset: () -> Unit
) {
    val session = LibraryArrangeSession(emptyList())
    private val queue = LibraryOrderQueue()
    private var seen = LibraryOrderChanges.revision(side)

    /** "name" or "custom", as the hub last said. */
    var order: String = LibraryOrder.NAME
        private set

    val ids: List<String> get() = session.ids
    val isCustom: Boolean get() = LibraryOrder.isCustom(order)

    /** Another screen saved this side's order since this one read it. */
    val stale: Boolean get() = seen != LibraryOrderChanges.revision(side)

    /** The hub's order, from a load. */
    fun show(ids: List<String>, order: String) {
        session.reset(ids)
        this.order = order
        seen = LibraryOrderChanges.revision(side)
    }

    /** Settings: the library at [from] goes to [to], and the order saves. */
    fun move(from: Int, to: Int) {
        if (!session.pickUp(from)) return
        session.moveLiftedTo(to)
        drop()
    }

    /** The lifted library is put down; a changed order shows and saves. */
    fun drop() {
        val next = session.drop() ?: return
        onOrder(next)
        save(next)
    }

    /** Back to A to Z: a lifted library goes back first, then the hub forgets the order. */
    fun aToZ() {
        if (session.isLifted) onOrder(session.rollback())
        save(emptyList())
    }

    private fun save(ids: List<String>) {
        queue.submit(ids)?.let(::send)
    }

    private fun send(ids: List<String>) {
        scope.launch {
            when (val result = api.saveLibraryOrder(LibraryOrder.side(side), ids)) {
                is HubResult.Ok -> {
                    order = result.value.order
                    seen = LibraryOrderChanges.changed(side)
                    val next = queue.answered(ok = true)
                    if (ids.isEmpty()) onReset() else {
                        session.saved(ids)
                        if (next == null) onOrder(session.ids)
                    }
                    next?.let(::send)
                }
                is HubResult.Failed -> {
                    queue.answered(ok = false)
                    onOrder(session.rollback())
                    onFailed(result.message)
                }
            }
        }
    }
}
