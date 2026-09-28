package com.pocketds.hub.ui

import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterGridResizeTest {
    @Test fun populatedGridReflowsWhenTheAvailableWidthShrinksAndGrows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
            val grid = PosterGridLayout(context).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            repeat(18) { index ->
                grid.addView(TextView(context).apply { text = "Downloaded title $index" },
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = dp(120)
                        setMargins(dp(8), dp(8), dp(8), dp(8))
                    })
            }
            // The rail and different device screens can change the content width
            // after GridLayout has already cached its children's column indices.
            for (widthDp in listOf(800, 500, 800, 320, 500, 800)) {
                val width = dp(widthDp)
                grid.forceLayout()
                grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                grid.layout(0, 0, width, grid.measuredHeight)
                assertEquals(18, grid.childCount)
                val first = grid.getChildAt(0)
                for (index in 0 until grid.childCount) {
                    val child = grid.getChildAt(index)
                    assertTrue("Card $index extends beyond the viewport at $widthDp dp", child.right <= width - grid.paddingRight)
                    assertTrue(child.left >= grid.paddingLeft)
                    assertEquals(first.width, child.width)
                    assertTrue(child.width > 0)
                    assertEquals(grid.getChildAt(index % grid.columnCount).left, child.left)
                    if (index >= grid.columnCount) {
                        assertTrue(child.top >= grid.getChildAt(index - grid.columnCount).bottom)
                    }
                }
            }
        }
    }
}
