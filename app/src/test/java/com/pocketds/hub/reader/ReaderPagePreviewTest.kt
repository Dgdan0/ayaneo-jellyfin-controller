package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderPagePreviewTest {
    @Test fun `reading without menus uses the complete viewport`() {
        assertEquals(ReaderPageTransform(1f, 0f, 0f), ReaderPagePreview.fit(1920, 1080))
    }

    @Test fun `menu preview fits between toolbars without changing aspect ratio`() {
        val result = ReaderPagePreview.fit(1920, 1080, top = 130, bottom = 130, margin = 24)
        assertEquals(772f / 1080f, result.scale, .0001f)
        assertEquals(154f, result.y, .001f)
        assertTrue(result.x >= 24)
        assertTrue(result.y + 1080 * result.scale <= 926.001f)
    }

    @Test fun `appearance and contents panels leave the entire page visible on the left`() {
        for ((width, height, right) in listOf(Triple(1920,1080,750), Triple(1080,1920,650), Triple(640,360,320))) {
            val result = ReaderPagePreview.fit(width, height, right = right, margin = 12)
            assertTrue(result.scale > 0f && result.scale < 1f)
            assertTrue(result.x >= 12f)
            assertTrue(result.y >= 12f)
            assertTrue(result.x + width * result.scale <= width - right - 12f + .01f)
            assertTrue(result.y + height * result.scale <= height - 12f + .01f)
        }
    }

    @Test fun `closing repeatedly restores the same full size page`() {
        repeat(10) {
            ReaderPagePreview.fit(1920,1080,right=750,margin=24)
            assertEquals(ReaderPageTransform(1f,0f,0f),ReaderPagePreview.fit(1920,1080))
        }
    }

    @Test fun `unmeasured views and very small windows are safe`() {
        assertEquals(ReaderPageTransform(1f,0f,0f), ReaderPagePreview.fit(0,0,top=60))
        val result = ReaderPagePreview.fit(80,40,top=60,bottom=60,right=320,margin=12)
        assertTrue(result.scale.isFinite() && result.scale > 0f)
        assertTrue(result.x >= 0 && result.y >= 0)
        assertTrue(result.x + 80 * result.scale <= 80)
        assertTrue(result.y + 40 * result.scale <= 40)
    }
}
