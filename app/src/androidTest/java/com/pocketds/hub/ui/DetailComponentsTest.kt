package com.pocketds.hub.ui

import android.content.res.Configuration
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ScrollView
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.HintBarView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native measurements on the Pocket DS; no network, real account or media data involved. */
@RunWith(AndroidJUnit4::class)
class DetailComponentsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun layout(view: View, widthDp: Int) {
        val width = Styler.dpInt(view.context, widthDp.toFloat())
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun longTitleNeverOverlapsActionsInEitherRailWidth() = onUi {
        val context = instrumentation.targetContext
        for (width in listOf(663, 785)) {
            val header = DetailHeaderView(context, Theme.colors(context), { true })
            header.titleView.text = "A very long title with multiple lines — וגם כותרת ארוכה בעברית"
            header.metadataView.text = "2026 · 2h 12m · Drama"
            header.setPresentation(true)
            val action = TextView(context).apply { text = "Resume"; minimumHeight = Styler.dpInt(context, 48f) }
            header.actions.addView(action)
            layout(header, width)
            val titleBounds = Rect().also { header.titleView.getDrawingRect(it); header.offsetDescendantRectToMyCoords(header.titleView, it) }
            val actionBounds = Rect().also { action.getDrawingRect(it); header.offsetDescendantRectToMyCoords(action, it) }
            assertTrue(titleBounds.bottom <= actionBounds.top)
            assertTrue(actionBounds.right <= header.width)
            assertTrue(actionBounds.bottom <= header.height)
        }
    }

    @Test fun largeFontSeasonCaptionsStayInsideTheCard() = onUi {
        val context = instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale = 1.6f })
        val card = DetailArtworkCardView(context, Theme.colors(context)) { true }
        card.titleView.text = "Season 12"
        card.subtitleView.text = "16 downloaded episodes"
        layout(card, 112)
        val caption = Rect().also { card.subtitleView.getDrawingRect(it); card.offsetDescendantRectToMyCoords(card.subtitleView, it) }
        assertTrue(caption.bottom <= card.height)
        assertTrue(card.height >= card.image.height + card.titleView.height + card.subtitleView.height)
    }

    @Test fun oneTapActivatesContinuationOnceAndDraggingDoesNot() = onUi {
        val context = instrumentation.targetContext
        val card = ContinuationCardView(context, Theme.colors(context), { true })
        card.bind("Continue watching", "S2 E12 · The Gary Grill", .5, false)
        var activations = 0
        card.activateOnTap { activations++ }
        layout(card, 540)
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, time: Long) {
            MotionEvent.obtain(now, time, action, x, 25f, 0).also { card.dispatchTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN, 30f, now)
        event(MotionEvent.ACTION_UP, 30f, now + 20)
        assertEquals(1, activations)
        event(MotionEvent.ACTION_DOWN, 30f, now + 40)
        event(MotionEvent.ACTION_MOVE, 300f, now + 60)
        event(MotionEvent.ACTION_UP, 300f, now + 80)
        assertEquals(1, activations)
        assertEquals(1f, card.scaleX)
        assertEquals(1f, card.scaleY)
    }

    @Test fun stationaryControlsDoNotCastClippedFocusShadows() = onUi {
        val context = instrumentation.targetContext
        val button = TextView(context)
        DetailStyler.action(button, Theme.colors(context), primary = true)
        FocusDecorator.attach(button, { true }, scale = false)
        layout(button, 96)
        assertTrue(button.requestFocus())
        FocusDecorator.refresh(button, true)
        assertEquals(0f, button.translationZ)
        assertEquals(1f, button.scaleX)
        assertEquals(1f, button.scaleY)
    }

    @Test fun watchedContinuationDoesNotShowAnInProgressLine() = onUi {
        val context = instrumentation.targetContext
        val card = ContinuationCardView(context, Theme.colors(context), { true })
        card.bind("Episode", "Watched", 1.0, true)
        assertEquals(View.GONE, card.progressView.visibility)
        card.bind("Episode", "50% watched", .5, false)
        assertEquals(View.VISIBLE, card.progressView.visibility)
    }

    @Test fun summaryResetsWhenDetailPageIsReentered() = onUi {
        val context = instrumentation.targetContext
        val summary = DetailOverviewView(context, Theme.colors(context)) { true }
        summary.bind("A long synopsis with a lot to read. ".repeat(70))
        summary.expand()
        assertTrue(summary.expanded)
        summary.collapse()
        assertFalse(summary.expanded)
        layout(summary, 520)
        assertTrue(summary.height <= Styler.dpInt(context, 72f))
    }

    @Test fun controllerActivationOpensTheFocusedSummary() = onUi {
        val context = instrumentation.targetContext
        val summary = DetailOverviewView(context, Theme.colors(context)) { true }
        summary.bind("A long synopsis. ".repeat(50))
        layout(summary, 520)
        assertTrue(summary.getChildAt(0).requestFocus())
        assertTrue(summary.onPad(PadAction.Activate))
        assertTrue(summary.expanded)
        assertTrue(summary.onPad(PadAction.Back))
        assertFalse(summary.expanded)
    }

    @Test fun longHintsCannotConsumeTheDetailViewport() = onUi {
        val context = instrumentation.targetContext
        val bar = HintBarView(context, Theme.colors(context))
        bar.setHints(listOf(ButtonHint.activate("Resume · 1:52:59"), ButtonHint.primary("Playback options"),
            ButtonHint.secondary("Start over"), ButtonHint.back(), ButtonHint("Refresh", "Refresh (Select)", PadAction.Refresh),
            ButtonHint("Start", "Collapse navigation menu", PadAction.Menu)))
        layout(bar, 663)
        // One slim line however many hints there are: the redesign's 36dp bar.
        assertEquals(Styler.dpInt(context, HintBarView.HEIGHT_DP), bar.height)
        assertTrue("Overflow hints remain reachable by touch", bar.canScrollHorizontally(1))
    }

    /** The backdrop runs on under the page below by design; only the words decide the header's height. */
    @Test fun heroArtworkDoesNotDecideTheHeaderHeight() = onUi {
        val context = instrumentation.targetContext
        val root = FrameLayout(context).apply { setBackgroundColor(Color.MAGENTA); clipChildren = false }
        val header = DetailHeaderView(context, Theme.colors(context), { true })
        header.titleView.text = "A movie title"
        header.setPresentation(true)
        val art = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        header.landscape.setImageBitmap(art)
        root.addView(header, FrameLayout.LayoutParams(-1, -2))
        root.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(context, 663f), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Styler.dpInt(context, 500f), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue("Decorative artwork must not determine the header height", header.height < root.height)
        art.recycle()
    }

    @Test fun focusingASeasonScrollsItsWholeFocusRingIntoView() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var scroller: ScrollView
        lateinit var card: DetailArtworkCardView
        try {
            onUi {
                val root = FrameLayout(activity)
                scroller = ScrollView(activity).apply { isFocusable = false }
                val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                content.addView(View(activity), LinearLayout.LayoutParams(1, Styler.dpInt(activity, 140f)))
                card = DetailArtworkCardView(activity, Theme.colors(activity)) { true }
                card.titleView.text = "Season 3"; card.subtitleView.text = "17 unwatched"
                content.addView(card, LinearLayout.LayoutParams(Styler.dpInt(activity, 112f), -2).apply {
                    leftMargin = Styler.dpInt(activity, 24f)
                })
                content.addView(View(activity), LinearLayout.LayoutParams(1, Styler.dpInt(activity, 40f)))
                scroller.addView(content)
                root.addView(scroller, FrameLayout.LayoutParams(-1, Styler.dpInt(activity, 300f)))
                activity.setContentView(root)
            }
            instrumentation.waitForIdleSync()
            onUi { scroller.scrollTo(0, 0); card.clearFocus(); card.requestFocus() }
            instrumentation.waitForIdleSync()
            onUi {
                val bottom = card.bottom - scroller.scrollY
                val lift = ((card.height * (DetailLayout.POSTER_FOCUS_SCALE - 1)) / 2).toInt()
                assertTrue("Focused season is clipped by the bottom bar", bottom + lift < scroller.height)
            }
        } finally { onUi { activity.finish() } }
    }
}
