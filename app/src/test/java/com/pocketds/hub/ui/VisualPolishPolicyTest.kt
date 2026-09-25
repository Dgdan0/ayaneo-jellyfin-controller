package com.pocketds.hub.ui

import com.pocketds.hub.model.PlaybackTrack
import com.pocketds.hub.state.LibraryGridSizing
import org.junit.Assert.*
import org.junit.Test

class VisualPolishPolicyTest {
    @Test fun `generated track labels do not repeat language codec or layout`() {
        val track=PlaybackTrack(language="eng",channels=6,codec="aac",default=true,label="Surround - English - AAC - 5.1 - Default")
        assertEquals("5.1 surround · AAC · Default",TrackPresentation.of(track).detail)
    }
    @Test fun `alternate audio descriptions remain distinguishable`() {
        val track=PlaybackTrack(language="eng",channels=2,codec="aac",label="English - Director commentary - AAC")
        assertEquals("Stereo · AAC · Director commentary",TrackPresentation.of(track).detail)
    }
    @Test fun `side panels fit short landscape and narrow windows`() {
        assertEquals(320, PanelGeometry.width(853))
        assertEquals(320, PanelGeometry.width(663))
        assertEquals(216, PanelGeometry.width(240))
        assertEquals(1, PanelGeometry.width(16))
    }
    @Test fun `every semantic badge picks a readable foreground`() {
        listOf(0xff5b1e96.toInt(), 0xff7b34c4.toInt(), 0xff1e9e5a.toInt(),
            0xff3ecb80.toInt(), 0xffc98a1b.toInt(), 0xffe0685c.toInt()).forEach {
            assertTrue(SemanticColor.contrast(it, SemanticColor.foreground(it)) >= 4.5)
        }
    }
    @Test fun `very narrow layouts use one column`() {
        assertEquals(1, LibraryGridSizing.columns(190, 24, 2f))
    }
    @Test fun `track language and accessibility come before codec diagnostics`() {
        val track = PlaybackTrack(language = "eng", label = "English - ASS - Full", codec = "ass", forced = true, hearingImpaired = true)
        val value = TrackPresentation.of(track)
        assertEquals("English", value.title)
        assertTrue(value.detail.contains("Forced"))
        assertTrue(value.detail.contains("SDH"))
        assertTrue(value.detail.contains("ASS"))
    }
    @Test fun `tracks without a language retain their useful label`() {
        assertEquals("Director commentary", TrackPresentation.of(PlaybackTrack(label = "Director commentary")).title)
        assertTrue(TrackPresentation.of(PlaybackTrack(language="heb", channels=2)).detail.contains("Stereo"))
    }
    @Test fun `adjusters clamp and step without accumulated floating point error`() {
        assertEquals(1.1f, ValueRange(.7f, 2f, .1f).move(1f, 1))
        assertEquals(.7f, ValueRange(.7f, 2f, .1f).move(.7f, -1))
        assertEquals(2f, ValueRange(.7f, 2f, .1f).move(2f, 1))
    }
}
