package com.pocketds.hub.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CenteredIconTextViewTest {
    @Test fun standalone_action_glyph_is_centred_despite_button_padding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = CenteredIconTextView(context)
        view.setPadding(12, 8, 4, 8)
        view.layout(0, 0, 48, 48)
        view.setCenteredIcon(MediaActionIconDrawable(context, MediaActionIcon.PLAY, 0xffeeeeee.toInt()), 20)
        val bounds = view.centeredIconBounds()
        assertEquals(24, bounds.centerX())
        assertEquals(24, bounds.centerY())
    }

    @Test fun icon_and_label_share_one_centred_group() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = CenteredIconTextView(context)
        view.text = "Read along"
        view.setCenteredIcon(MediaActionIconDrawable(context, MediaActionIcon.PLAY, 0xffeeeeee.toInt()), 20, 8)
        view.layout(0, 0, 200, 48)
        val icon = view.centeredIconBounds()
        val total = 20 + 8 + view.paint.measureText(view.text.toString())
        assertEquals(100f, icon.left + total / 2f, 1f)
    }
}
