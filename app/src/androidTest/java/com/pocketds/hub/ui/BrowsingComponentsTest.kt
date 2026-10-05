package com.pocketds.hub.ui

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowsingComponentsTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private fun layout(v: View, width: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(v.context,width.toFloat()), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0,0,v.measuredWidth,v.measuredHeight)
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    @Test fun posterArtKeepsRatioAtDifferentGridWidthsAndHasNoMat() = i.runOnMainSync {
        val c=i.targetContext
        for (width in listOf(96,112,146)) {
            val card=PosterCardView(c,Theme.colors(c))
            layout(card,width)
            val image=descendants(card).filterIsInstance<ImageView>().first()
            assertEquals(0,card.paddingLeft)
            assertEquals(image.width*1.5f,image.height.toFloat(),1.1f)
            assertEquals(1f,image.alpha)
            val old=card.measuredHeight
            image.setImageDrawable(android.graphics.drawable.ColorDrawable(Color.RED))
            layout(card,width)
            assertEquals(old,card.measuredHeight)
        }
    }
}
