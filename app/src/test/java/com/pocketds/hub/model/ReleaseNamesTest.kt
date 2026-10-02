package com.pocketds.hub.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNamesTest {
    @Test
    fun `an episode release reads as title, episode and quality`() {
        assertEquals("Dark Matter (2024) · S2E6 · 1080p",
            ReleaseNames.readable("dark.matter.2024.s02e06.1080p.web.h264-cakes[EZTVx.to].mkv"))
        assertEquals("Lanterns · S1E7 · 1080p", ReleaseNames.readable("Lanterns S01E07 1080p AMZN WEB-DL DD 5 1 H 264-playWEB"))
        assertEquals("Slow Horses · S6E3 · 2160p",
            ReleaseNames.readable("Slow.Horses.S06E03.Resurrection.2160p.ATVP.WEB-DL.DDP5.1.DV.HDR.H.265-NTb"))
    }

    @Test
    fun `a season pack and a film anchor on the season and the year`() {
        assertEquals("The Mentalist · Season 1 · 1080p", ReleaseNames.readable("The.Mentalist.S01.1080p.BluRay.x264-SHORTBREHD"))
        assertEquals("UNABOMBER (2026) · 1080p", ReleaseNames.readable("UNABOMBER 2026 1080p WEB H264-CUPCAKES"))
        assertEquals("2001 A Space Odyssey (1968) · 2160p", ReleaseNames.readable("2001.A.Space.Odyssey.1968.2160p.UHD.BluRay"))
    }

    @Test
    fun `a name with nothing to anchor on is left alone`() {
        assertEquals("Some Concert Recording", ReleaseNames.readable("Some Concert Recording"))
        assertEquals("", ReleaseNames.readable(""))
    }

    @Test
    fun `a transfer with an arr title keeps it and one without reads its release name`() {
        assertEquals("The Mentalist", ActivityItem(title = "The.Mentalist.S01.1080p", mediaTitle = "The Mentalist").headline)
        assertEquals("Ted Lasso · S4E8 · 1080p", ActivityItem(title = "Ted.Lasso.S04E08.PROPER.1080p.WEB.h264-TRB").headline)
    }
}
