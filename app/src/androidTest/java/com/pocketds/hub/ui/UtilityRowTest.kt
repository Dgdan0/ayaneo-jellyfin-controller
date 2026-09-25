package com.pocketds.hub.ui

import android.content.res.Configuration
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UtilityRowTest {
    @Test fun rowGrowsForLargeTextWithoutTruncatingItsValue() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val ctx=i.targetContext.createConfigurationContext(Configuration(i.targetContext.resources.configuration).apply{fontScale=1.5f})
            val row=UtilityRowView(ctx,Theme.colors(ctx),"Offline downloads","Wi-Fi only · while charging · keep 10240 MB free",AppIcon.BOOK)
            row.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(ctx,300f),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            assertTrue(row.measuredHeight>=Styler.dpInt(ctx,56f))
            row.detail="Choose a different storage location"
            assertTrue(row.contentDescription.contains("different storage"))
            assertEquals(1f,row.scaleX)
        }
    }
}
