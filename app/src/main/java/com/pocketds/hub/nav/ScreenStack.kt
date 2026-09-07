package com.pocketds.hub.nav

/**
 * The part of a screen's lifecycle that involves no Android types, so the
 * navigation machinery around it can be unit-tested with fakes.
 *
 * The Android-facing `Screen` interface extends this and adds view creation.
 */
interface StackScreen {
    /** Became visible: start loads, restore focus. */
    fun onShow()

    /** No longer visible: cancel in-flight work. */
    fun onHide()

    /** Gone for good. */
    fun onDestroyView()
}

/**
 * One section's back stack.
 *
 * Hand-rolled rather than `FragmentManager`, because what we actually need is
 * push, pop and a guaranteed callback order -- about forty lines -- and the
 * alternative brings a transaction and saved-state machine we would then have to
 * work around to keep a single focus owner.
 *
 * The stack is never empty once seeded: [pop] refuses to remove the root and
 * says so, which is what makes "B at the top level does nothing" fall out
 * without a special case at the call site.
 */
class ScreenStack {

    private val entries = ArrayList<StackScreen>()

    val depth: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun peek(): StackScreen? = entries.lastOrNull()

    /** Hides the current top, then shows [screen]. */
    fun push(screen: StackScreen) {
        entries.lastOrNull()?.onHide()
        entries.add(screen)
        screen.onShow()
    }

    /**
     * @return false if only the root remains, so the caller can let the gesture
     *   fall through to the system rather than swallowing it.
     */
    fun pop(): Boolean {
        if (entries.size <= 1) return false
        val removed = entries.removeAt(entries.lastIndex)
        removed.onHide()
        removed.onDestroyView()
        entries.last().onShow()
        return true
    }

    /** Hide the top without tearing anything down. For switching away. */
    fun hideTop() {
        entries.lastOrNull()?.onHide()
    }

    /** Show the top again. For switching back. */
    fun showTop() {
        entries.lastOrNull()?.onShow()
    }

    /** Tear everything down, deepest last. */
    fun clear() {
        for (i in entries.indices.reversed()) {
            entries[i].onHide()
            entries[i].onDestroyView()
        }
        entries.clear()
    }
}
