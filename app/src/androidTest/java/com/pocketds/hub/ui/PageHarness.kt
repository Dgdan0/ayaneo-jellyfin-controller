package com.pocketds.hub.ui

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.nav.ScreenStack

/**
 * The host's way of changing pages, for tests (#23): a back stack, a frame of
 * pages and the same [FocusPlace] calls in the same order as HubActivity's
 * push, back and showCurrent, so Back is tested as it happens on the device.
 * Anything else a page asks of its host is noted and goes no further: nothing
 * plays, downloads or leaves the test.
 */
class PageHarness(private val activity: Activity) : ScreenHost {
    val stage = FrameLayout(activity)
    /**
     * The tabs, added after the pages as HubActivity adds its bar: where a
     * cleared focus goes when no page can take it.
     */
    val tabs = TextView(activity).apply { text = "Tabs"; Styler.makeFocusable(this) }
    private val stack = ScreenStack()
    private val views = HashMap<Screen, View>()
    /** What a page asked the host to play, open or download; a Back test asks for none of it. */
    val asked = mutableListOf<String>()
    /** What a page told the person, in order. */
    val notices = mutableListOf<String>()

    override val viewContext: Context get() = activity

    init {
        activity.setContentView(FrameLayout(activity).apply {
            addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(tabs, FrameLayout.LayoutParams(WRAP, WRAP))
        })
    }

    /** HubActivity.push: the page's view made and hidden, the stack moved on, then shown. */
    override fun push(screen: Screen) {
        val view = screen.onCreateView(this, stage)
        view.visibility = View.GONE
        stage.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        views[screen] = view
        stack.push(screen)
        show()
    }

    /** HubActivity.back: the stack moved back, the page let go of, then the one under it shown. */
    override fun back(): Boolean {
        val leaving = stack.peek() as? Screen
        if (!stack.pop()) return false
        leaving?.let { views.remove(it)?.let(stage::removeView) }
        show()
        return true
    }

    /** HubActivity.showCurrent's part in focus. */
    private fun show() {
        val top = stack.peek() as? Screen ?: return
        val view = views[top]
        FocusPlace.show(stage, view)
        view?.post { FocusPlace.settle(view, top.focusOnShow, top::requestInitialFocus) { it.requestFocus() } }
    }

    fun viewOf(screen: Screen): View = checkNotNull(views[screen])

    /** The page in front. */
    fun top(): Screen = stack.peek() as Screen

    /** Every page off the stack, as the activity finishing would. */
    fun close() {
        while (stack.depth > 0) {
            val top = stack.peek() as? Screen ?: break
            if (!stack.pop()) {
                top.onHide(); top.onDestroyView(); break
            }
        }
    }

    override fun switchSection(delta: Int) = Unit
    override fun selectJellyfinUser(id: String, name: String) = Unit
    override fun notify(message: String) { notices += message }
    override fun refreshHints() = Unit
    override fun openTrailer(key: String, watchUrl: String, title: String) { asked += "trailer:$key" }
    override fun playItem(itemId: String, startMode: String) { asked += "play:$itemId" }
    override fun openPlaybackOptions(itemId: String, startMode: String) { asked += "options:$itemId" }
    override fun playPrepared(plan: PlaybackPrepareResponse) { asked += "prepared" }
    override fun downloadItem(item: LibraryItem, seasonId: String) { asked += "download:${item.id}" }
    override fun enterPictureInPicture(source: View) = false

    /** A second page to go to and come back from: one focusable line. */
    class Elsewhere : Screen {
        override val title = "Elsewhere"
        override fun onCreateView(host: ScreenHost, container: ViewGroup): View = FrameLayout(host.viewContext).apply {
            addView(TextView(host.viewContext).apply {
                text = "Elsewhere"
                Styler.makeFocusable(this)
            }, FrameLayout.LayoutParams(WRAP, WRAP))
        }
        override fun onShow() = Unit
        override fun onHide() = Unit
        override fun onDestroyView() = Unit
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
