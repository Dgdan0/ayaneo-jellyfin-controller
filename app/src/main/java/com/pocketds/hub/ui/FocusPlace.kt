package com.pocketds.hub.ui

import android.view.View
import android.view.ViewGroup
import com.pocketds.hub.R

/**
 * The place on a page that Back returns to (#23), and the one way focus moves
 * between pages: the host shows a page through [show] and settles focus on it
 * with [settle]. No page hand-rolls either.
 *
 * Switching pages, Android moves focus through them twice. Leaving, the host
 * clears focus while the page is still on screen, and the view nearest the
 * scroll position takes it; coming back, the page's first layout restores the
 * window's default focus before the page asks for its own. A page that
 * remembered "where you were" from its focus listeners took both moves for the
 * person's, and Back landed on a continue card or the tabs (#22).
 *
 * So the place is kept here, as Android's own default focus. Leaving a page,
 * what has focus in it is marked as its default, and the page is closed to
 * focus while the focus is cleared, so it cannot hear a move that was not the
 * person's. Coming back, the mark is the page's default again: Android's
 * restore lands on it, and [settle] puts focus there when Android does not. The
 * first move the person makes spends it ([spend]). A page whose views are
 * rebuilt while it is away marks the new view of its place with [mark], or
 * lets [across] find it by its tag.
 *
 * A place that leaves the window is let go: a list that recycles its cards
 * can bind the same view to another title, and Back must not land on that.
 */
object FocusPlace {

    /**
     * Shows [page], hiding the others in [stage]. The page that has focus is
     * left first: what has focus in it becomes its place, and it is closed to
     * focus while the focus is cleared, so the clear cannot hand focus to it.
     */
    fun show(stage: ViewGroup, page: View?) {
        val focused = stage.findFocus()
        val leaving = focused?.let { pageOf(stage, it) }?.takeIf { it !== page }
        if (focused != null && leaving != null) {
            mark(leaving, focused)
            val group = leaving as? ViewGroup
            val before = group?.descendantFocusability
            group?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            focused.clearFocus()
            leaving.visibility = View.GONE
            if (group != null && before != null) group.descendantFocusability = before
        }
        for (index in 0 until stage.childCount) {
            val child = stage.getChildAt(index)
            if (child !== page) child.visibility = View.GONE
        }
        if (page == null) return
        page.visibility = View.VISIBLE
        // The window's default focus leads to the page in front, not to the
        // last page that was marked: Android follows it from the top.
        placeIn(page)?.let { place -> place.isFocusedByDefault = false; place.isFocusedByDefault = true }
    }

    /**
     * Once [page] is shown: focus goes back to its place if it has one, or, on
     * a first visit with nothing in it focused yet, where the page says
     * ([initial], else [fallback]). A page that has already put focus where it
     * wants on a first visit (a menu it opened) keeps it.
     */
    fun settle(page: View, wantsFocus: Boolean, initial: () -> Boolean, fallback: (View) -> Unit) {
        val place = placeIn(page)?.takeIf(::canTake)
        when {
            place != null -> if (!place.isFocused) place.requestFocus()
            wantsFocus && page.findFocus() == null -> if (!initial()) fallback(page)
        }
    }

    /**
     * [view] is the place on [page]: where Back returns. The page's earlier
     * place is let go. For a page that rebuilds its views while it is away; the
     * host marks the focused view itself when it leaves a page.
     */
    fun mark(page: View, view: View?) {
        val before = page.getTag(R.id.focus_place) as? View
        if (before === view) {
            if (view != null && !view.isFocusedByDefault) view.isFocusedByDefault = true
            return
        }
        before?.let(::letGo)
        page.setTag(R.id.focus_place, view)
        if (view != null) hold(page, view)
    }

    /**
     * Focus on [page]'s place while it is still there to come back to, for a
     * page's own [com.pocketds.hub.nav.Screen.requestInitialFocus]: whatever
     * asks for the page's start while the person is coming back gets the place
     * they left, and their own memory only once they have moved.
     */
    fun focus(page: View): Boolean = placeIn(page)?.takeIf(::canTake)?.let { it.isFocused || it.requestFocus() } == true

    /**
     * Focus back on [page]'s place, which is then spent: for focus coming back
     * from outside the page without the page being hidden (the tabs).
     */
    fun restore(page: View): Boolean {
        val place = placeIn(page)?.takeIf(::canTake) ?: return false
        val taken = place.requestFocus()
        spend(page)
        return taken
    }

    /**
     * Runs [rebuild], which replaces views on [page] (a settings section drawn
     * again, a dashboard's rows after a poll): the place moves to the new view
     * standing where it stood, found by the tag it, or the nearest tagged view
     * round it, carries. Without this a page rebuilt while away lost its place.
     */
    fun across(page: View, rebuild: () -> Unit) {
        val old = placeIn(page)
        val tags = old?.let { view ->
            generateSequence(view) { it.parent as? View }.takeWhile { it !== page }.mapNotNull { it.tag }.toList()
        }.orEmpty()
        rebuild()
        if (old == null) return
        // Moved rather than rebuilt (taken out and put back): still the place.
        if (old.isAttachedToWindow) { mark(page, old); return }
        val stand = tags.firstNotNullOfOrNull { tag -> find(page) { it.tag == tag } } ?: return
        mark(page, find(stand) { it.isFocusable && it.visibility == View.VISIBLE })
    }

    /** The person moved: [page]'s place has served, and a later restore must not find it. */
    fun spend(page: View) = mark(page, null)

    /** [page]'s place, while it is still on the page. */
    fun placeIn(page: View): View? = (page.getTag(R.id.focus_place) as? View)?.takeIf { it.isAttachedToWindow && it.isFocusedByDefault }

    private fun canTake(view: View) = view.isShown && view.isFocusable && view.isEnabled

    /** [view] is [page]'s place, Android's default focus, until it leaves the window. */
    private fun hold(page: View, view: View) {
        view.isFocusedByDefault = true
        val leave = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (page.getTag(R.id.focus_place) === v) page.setTag(R.id.focus_place, null)
                letGo(v)
            }
        }
        view.setTag(R.id.focus_place_hold, leave)
        view.addOnAttachStateChangeListener(leave)
    }

    /** [view] is no longer a place: not the default focus, and nothing waits for it to leave. */
    private fun letGo(view: View) {
        view.isFocusedByDefault = false
        (view.getTag(R.id.focus_place_hold) as? View.OnAttachStateChangeListener)?.let(view::removeOnAttachStateChangeListener)
        view.setTag(R.id.focus_place_hold, null)
    }

    /** The first view, depth first from [root] itself, that [matches]. */
    private fun find(root: View, matches: (View) -> Boolean): View? {
        if (matches(root)) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) find(group.getChildAt(index), matches)?.let { return it }
        return null
    }

    /** The page in [stage] holding [view]: the stage's child it sits in. */
    private fun pageOf(stage: ViewGroup, view: View): View? {
        var child = view
        while (true) {
            val parent = child.parent as? View ?: return null
            if (parent === stage) return child
            child = parent
        }
    }
}
