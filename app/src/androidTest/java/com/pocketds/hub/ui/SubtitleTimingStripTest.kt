package com.pocketds.hub.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.playback.SubtitleOffsetOverlay
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTimingStripTest {
    @Test fun timingIsCompactAndHasNoPauseButton() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val root = FrameLayout(activity)
            val strip = SubtitleOffsetOverlay(activity, Theme.colors(activity)) { true }
            root.addView(strip, FrameLayout.LayoutParams(320, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            activity.setContentView(root)
            strip.show(800, {}, {})
            fun labels(view: View): List<String> = listOfNotNull((view as? TextView)?.text?.toString()) +
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { labels(group.getChildAt(it)) } }.orEmpty()
            assertTrue(labels(strip).any { it.contains("Later") })
            assertFalse(labels(strip).any { it.equals("Pause", ignoreCase = true) })
            assertEquals(320, strip.layoutParams.width)
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
