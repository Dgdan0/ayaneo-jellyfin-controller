package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class ReadingModesTest {
    private val all = listOf(ReadingMode.EBOOK, ReadingMode.AUDIO, ReadingMode.ALONG)

    @Test fun onlyTheFormatsTheBookHasAreOffered() {
        assertEquals(all, ReadingMode.available(ebook = true, audio = true, aligned = true))
        assertEquals(listOf(ReadingMode.EBOOK, ReadingMode.AUDIO), ReadingMode.available(true, true, false))
        assertEquals(listOf(ReadingMode.AUDIO, ReadingMode.ALONG), ReadingMode.available(false, true, true))
        assertEquals(listOf(ReadingMode.EBOOK), ReadingMode.available(true, false, false))
    }

    @Test fun aBookWithOneFormatHasNoButtonToChooseWith() {
        assertFalse(ModePicker(listOf(ReadingMode.EBOOK), ReadingMode.EBOOK).worthShowing)
        assertTrue(ModePicker(all, ReadingMode.EBOOK).worthShowing)
        val one = ModePicker(listOf(ReadingMode.EBOOK), ReadingMode.EBOOK)
        one.open(0)
        assertFalse(one.isOpen)
    }

    @Test fun theButtonShowsTheModeYouAreInAndOpensOnItWithItsNameReady() {
        val picker = ModePicker(all, ReadingMode.AUDIO)
        assertFalse(picker.isOpen)
        picker.open(1_000)
        assertTrue(picker.isOpen)
        assertEquals(ReadingMode.AUDIO, picker.focused)
    }

    @Test fun leftAndRightChooseAndStopAtTheEnds() {
        val picker = ModePicker(all, ReadingMode.AUDIO)
        picker.open(0)
        picker.move(1, 100)
        assertEquals(ReadingMode.ALONG, picker.focused)
        picker.move(1, 200)
        assertEquals(ReadingMode.ALONG, picker.focused)
        picker.move(-1, 300); picker.move(-1, 400); picker.move(-1, 500)
        assertEquals(ReadingMode.EBOOK, picker.focused)
    }

    @Test fun aSwitchIsTheFocusedModeAndNothingForTheOneYouAreIn() {
        val picker = ModePicker(all, ReadingMode.AUDIO)
        picker.open(0)
        assertNull("Ⓐ on the mode you are in just closes it", picker.pick())
        assertFalse(picker.isOpen)
        picker.open(10)
        picker.move(1, 20)
        assertEquals(ReadingMode.ALONG, picker.pick())
        assertFalse(picker.isOpen)
        assertNull(picker.pick())
    }

    @Test fun closingPutsTheFocusBackOnTheCurrentMode() {
        val picker = ModePicker(all, ReadingMode.EBOOK)
        picker.open(0); picker.move(1, 1)
        picker.close()
        assertFalse(picker.isOpen)
        assertEquals(ReadingMode.EBOOK, picker.focused)
    }

    @Test fun itClosesByItselfAfterFiveSecondsAndEveryMoveStartsThemAgain() {
        val picker = ModePicker(all, ReadingMode.EBOOK)
        picker.open(1_000)
        assertFalse(picker.expired(5_999))
        assertTrue(picker.expired(6_000))
        picker.move(1, 5_500)
        assertFalse(picker.expired(10_000))
        assertTrue(picker.expired(10_500))
        picker.point(ReadingMode.ALONG, 20_000)
        assertFalse(picker.expired(24_999))
        picker.close()
        assertFalse(picker.expired(99_999))
    }

    @Test fun aWordSelectedOnThePageSetsWhereTheVoiceStartsThenWhereItStoppedThenTheTop() {
        assertEquals(ModePlace.Start.SELECTED_SENTENCE, ModePlace.startingFromPage(selected = true, heardHere = true))
        assertEquals(ModePlace.Start.SELECTED_SENTENCE, ModePlace.startingFromPage(selected = true, heardHere = false))
        assertEquals(ModePlace.Start.WHERE_VOICE_STOPPED, ModePlace.startingFromPage(selected = false, heardHere = true))
        assertEquals(ModePlace.Start.TOP_OF_PAGE, ModePlace.startingFromPage(selected = false, heardHere = false))
    }

    @Test fun theScreenSaysWhereItStartedInTheWordsOfTheDesign() {
        assertEquals("Listening from the start of the sentence you selected", ModePlace.note(ModePlace.Start.SELECTED_SENTENCE, ReadingMode.AUDIO))
        assertEquals("Reading along from the start of the sentence you selected", ModePlace.note(ModePlace.Start.SELECTED_SENTENCE, ReadingMode.ALONG))
        assertEquals("Going on from where the voice stopped", ModePlace.note(ModePlace.Start.WHERE_VOICE_STOPPED, ReadingMode.ALONG))
        assertEquals("Listening from the top of your page", ModePlace.note(ModePlace.Start.TOP_OF_PAGE, ReadingMode.AUDIO))
        assertEquals("Reading along from the top of your page", ModePlace.note(ModePlace.Start.TOP_OF_PAGE, ReadingMode.ALONG))
        assertEquals("The page opens where the voice was", ModePlace.noteToEbook(ReadingMode.AUDIO))
        assertEquals("Paused. Your place is kept", ModePlace.noteToEbook(ReadingMode.ALONG))
    }

    @Test fun theVoiceKeepsGoingBetweenAudioAndReadAlongAndNowhereElse() {
        assertTrue(ModePlace.voiceKeepsGoing(ReadingMode.AUDIO, ReadingMode.ALONG))
        assertTrue(ModePlace.voiceKeepsGoing(ReadingMode.ALONG, ReadingMode.AUDIO))
        assertFalse(ModePlace.voiceKeepsGoing(ReadingMode.AUDIO, ReadingMode.EBOOK))
        assertFalse(ModePlace.voiceKeepsGoing(ReadingMode.EBOOK, ReadingMode.ALONG))
        assertFalse(ModePlace.voiceKeepsGoing(ReadingMode.ALONG, ReadingMode.ALONG))
    }

    @Test fun onlyAPlaceFurtherOnAndWrittenElsewhereIsAskedAbout() {
        val ask = ListenedFurther.decide(ours = 0.30, theirs = 0.35, inMs = false, byThisDevice = false, device = "iPad", where = "Chapter 7", ageMs = 12 * 60_000L)
        assertEquals(ListenedFurther.Away("iPad", "Chapter 7", 12 * 60_000L), ask)
        assertNull("behind", ListenedFurther.decide(0.35, 0.30, false, false, "iPad", "", 0))
        assertNull("a hair ahead", ListenedFurther.decide(0.300, 0.305, false, false, "iPad", "", 0))
        assertNull("this device's own", ListenedFurther.decide(0.30, 0.60, false, true, "pocket", "", 0))
        assertNull("nothing here yet", ListenedFurther.decide(null, 0.60, false, false, "iPad", "", 0))
        assertNull("nothing there", ListenedFurther.decide(0.30, null, false, false, "iPad", "", 0))
    }

    @Test fun inMillisecondsAMinuteAheadIsTheLeastWorthAsking() {
        assertNotNull(ListenedFurther.decide(100_000.0, 160_000.0, inMs = true, byThisDevice = false, device = "iPhone", where = "", ageMs = 0))
        assertNull(ListenedFurther.decide(100_000.0, 159_999.0, inMs = true, byThisDevice = false, device = "iPhone", where = "", ageMs = 0))
    }

    @Test fun anUnnamedDeviceIsAnotherDevice() {
        val ask = ListenedFurther.decide(0.1, 0.5, false, false, "  ", "", 0)!!
        assertEquals("You listened further on another device", ListenedFurther.title(ask))
    }

    @Test fun whenAndWhereAreOneLine() {
        assertEquals("Chapter 7 · 12 minutes ago", ListenedFurther.detail(ListenedFurther.Away("iPad", "Chapter 7", 12 * 60_000L)))
        assertEquals("just now", ListenedFurther.detail(ListenedFurther.Away("iPad", "", 5_000)))
        assertEquals("a minute ago", ListenedFurther.ago(60_000))
        assertEquals("an hour ago", ListenedFurther.ago(75 * 60_000L))
        assertEquals("3 hours ago", ListenedFurther.ago(3 * 3_600_000L))
        assertEquals("yesterday", ListenedFurther.ago(30 * 3_600_000L))
        assertEquals("4 days ago", ListenedFurther.ago(4 * 24 * 3_600_000L))
    }
}
