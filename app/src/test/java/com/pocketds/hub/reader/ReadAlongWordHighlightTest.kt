package com.pocketds.hub.reader

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read along by the word, style A (#66): the colours and their maths, the setting as kept, and what is lit when. */
class ReadAlongWordHighlightTest {
    private val themes = EpubPagePalette.CHOICES

    // ------------------------------------------------------------------ the colours: the owner's demo, number for number

    @Test fun `the eight colours are the demo's`() {
        assertEquals(listOf("Gold", "Ember", "Rose", "Lavender", "Sky", "Teal", "Mint", "Moon"), ReadAlongColor.entries.map { it.label })
        assertEquals(listOf(
            Triple(240, 201, 106), Triple(222, 140, 76), Triple(233, 143, 168), Triple(179, 157, 235),
            Triple(125, 183, 232), Triple(92, 194, 181), Triple(154, 212, 126), Triple(190, 196, 214)
        ), ReadAlongColor.entries.map { Triple((it.argb shr 16) and 0xFF, (it.argb shr 8) and 0xFF, it.argb and 0xFF) })
        assertEquals(ReadAlongColor.EMBER, ReadAlongColor.of("ember"))
        assertNull(ReadAlongColor.of("teal-ish"))
    }

    @Test fun `paper and sepia default to gold, dim, dark and blue to ember, each with a 40 percent trail`() {
        assertEquals(HighlightLook(ReadAlongColor.GOLD, 40), ReadAlongWordHighlight.defaultLook(EpubTheme.LIGHT))
        assertEquals(HighlightLook(ReadAlongColor.GOLD, 40), ReadAlongWordHighlight.defaultLook(EpubTheme.SEPIA))
        assertEquals(HighlightLook(ReadAlongColor.EMBER, 40), ReadAlongWordHighlight.defaultLook(EpubTheme.DARK))
        assertEquals(HighlightLook(ReadAlongColor.EMBER, 40), ReadAlongWordHighlight.defaultLook(EpubTheme.BLACK))
        assertEquals(HighlightLook(ReadAlongColor.EMBER, 40), ReadAlongWordHighlight.defaultLook(EpubTheme.BLUE))
    }

    @Test fun `light pages ask 0_62 for the word, dark pages 0_42, and the trail is the word's times its percent`() {
        assertEquals(0.62, ReadAlongWordHighlight.wordStrength(dark = false), 0.0)
        assertEquals(0.42, ReadAlongWordHighlight.wordStrength(dark = true), 0.0)
        // 40% reproduces the demo's first numbers, 0.24 and 0.17, as near as its own maths does.
        assertEquals(0.62 * 40 / 100.0, ReadAlongWordHighlight.trailStrength(false, 40), 0.0)
        assertEquals(0.248, ReadAlongWordHighlight.trailStrength(false, 40), 1e-12)
        assertEquals(0.168, ReadAlongWordHighlight.trailStrength(true, 40), 1e-12)
        assertEquals(0.0, ReadAlongWordHighlight.trailStrength(true, 0), 0.0)
        assertEquals(0.42, ReadAlongWordHighlight.trailStrength(true, 100), 0.0)
    }

    /** Colours worked out by the demo's own JavaScript loop (its `wash`) on the app's page colours: what Apple must draw too. */
    @Test fun `the washes are the demo's to the level`() {
        fun hex(color: Int) = "%06X".format(color and 0xFFFFFF)
        fun tints(theme: EpubTheme, color: ReadAlongColor, trail: Int) =
            ReadAlongWordHighlight.tints(ReadAlongHighlights().with(theme, HighlightLook(color, trail)), theme)
        tints(EpubTheme.LIGHT, ReadAlongColor.GOLD, 40).let { assertEquals("F4DC9F", hex(it.word)); assertEquals("F8EED3", hex(it.trail!!)); assertEquals("F6E4B7", hex(it.sentence)) }
        tints(EpubTheme.SEPIA, ReadAlongColor.EMBER, 40).let { assertEquals("E9B282", hex(it.word)); assertEquals("F5D7B6", hex(it.trail!!)) }
        tints(EpubTheme.DARK, ReadAlongColor.EMBER, 40).let { assertEquals("6C4B32", hex(it.word)); assertEquals("403227", hex(it.trail!!)); assertEquals("6E4C32", hex(it.sentence)) }
        tints(EpubTheme.BLACK, ReadAlongColor.EMBER, 40).let { assertEquals("5D3B20", hex(it.word)); assertEquals("25180D", hex(it.trail!!)) }
        tints(EpubTheme.BLACK, ReadAlongColor.MOON, 100).let { assertEquals("414349", hex(it.word)); assertEquals("414349", hex(it.trail!!)) }
        tints(EpubTheme.BLUE, ReadAlongColor.GOLD, 40).let { assertEquals("69674D", hex(it.word)); assertEquals("404A45", hex(it.trail!!)) }
    }

    /** All eight colours on every page, at a trail of 0, 40 and 100: the page's ink always reads at 4.5:1 or better. */
    @Test fun `every colour on every page at every trail keeps the ink at 4_5 to 1`() {
        var lowest = Double.MAX_VALUE
        for (theme in themes) for (color in ReadAlongColor.entries) for (trail in listOf(0, 40, 100)) {
            val (page, ink) = EpubPagePalette.of(theme)
            val tints = ReadAlongWordHighlight.tints(HighlightLook(color, trail), page, ink, ReadAlongWordHighlight.isDark(theme))
            val label = "$theme $color $trail%"
            for (tint in listOfNotNull(tints.word, tints.trail, tints.sentence)) {
                assertEquals(label, 0xFF, tint ushr 24)
                val contrast = ReadAlongGlow.contrast(ink, tint)
                lowest = minOf(lowest, contrast)
                assertTrue("$label: ${ReadAlongGlow.rgb(tint)} reads at $contrast", contrast >= ReadAlongGlow.MIN_CONTRAST)
            }
            // The word is the stronger of the two, the trail none at 0%, and the word's own at 100%.
            when (trail) {
                0 -> assertNull(label, tints.trail)
                100 -> assertEquals(label, tints.word, tints.trail)
                else -> assertTrue(label, distance(tints.trail!!, page) < distance(tints.word, page))
            }
            assertTrue(label, tints.word != page)
        }
        assertTrue("the least is still readable: $lowest", lowest >= 4.5)
    }

    private fun distance(a: Int, b: Int) = listOf(16, 8, 0).sumOf { Math.abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) }

    @Test fun `the slider steps by five from none to as strong as the word`() {
        assertEquals(0, ReadAlongWordHighlight.stepped(-3))
        assertEquals(40, ReadAlongWordHighlight.stepped(41))
        assertEquals(45, ReadAlongWordHighlight.stepped(43))
        assertEquals(100, ReadAlongWordHighlight.stepped(140))
        assertEquals("None", ReadAlongWordHighlight.trailLabel(0))
        assertEquals("40%", ReadAlongWordHighlight.trailLabel(40))
        assertEquals("Gold · trail 40%", ReadAlongWordHighlight.summary(HighlightLook(ReadAlongColor.GOLD, 40)))
        assertEquals("Ember · no trail", ReadAlongWordHighlight.summary(HighlightLook(ReadAlongColor.EMBER, 0)))
    }

    // ------------------------------------------------------------------ the setting, kept per theme

    private class Store : SharedPreferences {
        val values = mutableMapOf<String, Any>()
        override fun getAll() = values
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { self, method, args ->
                when (method.name) {
                    "putString", "putInt" -> { pending[args[0] as String] = args[1]; self }
                    "remove" -> { pending[args[0] as String] = null; self }
                    "apply", "commit" -> { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; if (method.name == "commit") true else null }
                    else -> error("not used: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun `each theme keeps its own colour and trail, and the default is nothing kept`() {
        val store = Store()
        assertEquals(ReadAlongHighlights(), ReadAlongHighlightStore.decode(store))
        val set = ReadAlongHighlights().with(EpubTheme.SEPIA, HighlightLook(ReadAlongColor.ROSE, 65)).with(EpubTheme.BLACK, HighlightLook(ReadAlongColor.SKY, 0))
        ReadAlongHighlightStore.encode(store, set)
        assertEquals("rose", store.values["readalong.color.SEPIA"])
        assertEquals(65, store.values["readalong.trail.SEPIA"])
        val read = ReadAlongHighlightStore.decode(store)
        assertEquals(set, read)
        assertEquals(HighlightLook(ReadAlongColor.ROSE, 65), read.of(EpubTheme.SEPIA))
        assertEquals(HighlightLook(ReadAlongColor.GOLD, 40), read.of(EpubTheme.LIGHT))
        // "Use the default" is both, for that theme only, and nothing of it is left kept.
        ReadAlongHighlightStore.encode(store, read.reset(EpubTheme.SEPIA))
        assertFalse(store.values.keys.any { it.endsWith(".SEPIA") })
        assertEquals(HighlightLook(ReadAlongColor.GOLD, 40), ReadAlongHighlightStore.decode(store).of(EpubTheme.SEPIA))
        assertEquals(HighlightLook(ReadAlongColor.SKY, 0), ReadAlongHighlightStore.decode(store).of(EpubTheme.BLACK))
        // A colour this build does not know is the theme's default colour; a trail off its steps is stepped.
        store.values["readalong.color.DARK"] = "ultraviolet"; store.values["readalong.trail.DARK"] = 37
        assertEquals(HighlightLook(ReadAlongColor.EMBER, 35), ReadAlongHighlightStore.decode(store).of(EpubTheme.DARK))
    }

    // ------------------------------------------------------------------ what is lit: style A's bookkeeping

    /** Two sentences of words in one file, a third with a word the dramatization cut (w2 has no clip), and a sentence edition's sentence. */
    private val timeline = ReadAlongTimeline(ReadAlongPackage.stretches(listOf(
        word("s0", 0, 0, 400), word("s0", 1, 500, 900), word("s0", 2, 1_000, 1_400),
        word("s1", 0, 2_000, 2_300), word("s1", 1, 2_400, 2_800),
        word("s2", 0, 3_000, 3_200), word("s2", 1, 3_300, 3_500), word("s2", 3, 3_600, 3_900),
        ReadAlongSegment("one.xhtml", "s3", "one.mp4", 4_000, 5_000)
    )))

    private fun word(sentence: String, n: Int, begin: Long, end: Long) =
        ReadAlongSegment("one.xhtml", "$sentence-w$n", "one.mp4", begin, end, sentence)

    private fun at(ms: Long) = timeline.active(0, ms)

    @Test fun `the trail grows word by word`() {
        assertEquals(emptyList<String>(), ReadAlongWordHighlight.trail(timeline, at(100)!!))
        assertEquals(listOf("s0-w0"), ReadAlongWordHighlight.trail(timeline, at(600)!!))
        assertEquals(listOf("s0-w0", "s0-w1"), ReadAlongWordHighlight.trail(timeline, at(1_100)!!))
        // In the pause between two words of a sentence the word before is still being said.
        assertEquals("s0-w1", at(950)?.fragment)
        assertEquals(ReadAlongWordHighlight.Change.Word(ReadAlongWordHighlight.Mark("one.xhtml", "s0", "s0-w1")),
            ReadAlongWordHighlight.change(ReadAlongWordHighlight.mark(at(100)), ReadAlongWordHighlight.mark(at(600))))
    }

    @Test fun `a new sentence clears the trail`() {
        val last = ReadAlongWordHighlight.mark(at(1_300))
        val next = ReadAlongWordHighlight.mark(at(2_100))
        assertEquals(ReadAlongWordHighlight.Change.Sentence(next!!), ReadAlongWordHighlight.change(last, next))
        assertEquals(emptyList<String>(), ReadAlongWordHighlight.trail(timeline, at(2_100)!!))
        assertEquals(ReadAlongWordHighlight.Change.Same, ReadAlongWordHighlight.change(next, ReadAlongWordHighlight.mark(at(2_200))))
    }

    @Test fun `between two sentences nothing is lit, and the space between them is never in a trail`() {
        // After a sentence's last word and before the next one's first: nothing is said, and the highlight goes.
        assertNull(at(1_700))
        assertEquals(ReadAlongWordHighlight.Change.Clear, ReadAlongWordHighlight.change(ReadAlongWordHighlight.mark(at(1_300)), ReadAlongWordHighlight.mark(at(1_700))))
        // A trail is only ever its own sentence's words, which the page draws from its first letter: never another's.
        for (ms in 0L..3_900L step 50) {
            val segment = at(ms) ?: continue
            assertTrue("$ms", ReadAlongWordHighlight.trail(timeline, segment).all { it.startsWith(segment.sentenceFragment + "-w") })
        }
    }

    @Test fun `a jump or a seek lands the trail on the words before the word it lands on`() {
        // A seek into the middle of the third sentence: the trail is that sentence's words before it, as if read.
        assertEquals(listOf("s2-w0", "s2-w1"), ReadAlongWordHighlight.trail(timeline, at(3_700)!!))
        // Back to the first sentence's second word.
        assertEquals(listOf("s0-w0"), ReadAlongWordHighlight.trail(timeline, at(timeline.find("one.xhtml", "s0-w1")!!.offsetMs)!!))
        // A saved place, a sentence: the voice resumes at its first word with no trail.
        val resume = timeline.find("one.xhtml", "s1")!!
        assertEquals("s1-w0", at(resume.offsetMs)?.fragment)
        assertEquals(emptyList<String>(), ReadAlongWordHighlight.trail(timeline, at(resume.offsetMs)!!))
    }

    @Test fun `a trail at 0 percent draws nothing for the trail, and a sentence edition washes its sentence whole`() {
        val tints = ReadAlongWordHighlight.tints(HighlightLook(ReadAlongColor.GOLD, 0), 0xFFFBFAF6.toInt(), 0xFF282B29.toInt(), dark = false)
        assertNull(tints.trail)
        val word = ReadAlongWordHighlight.paint(ReadAlongWordHighlight.mark(at(600))!!, tints)
        assertFalse(word.trail)
        assertEquals("s0-w1", word.word)
        assertEquals(tints.word, word.wordTint)
        val withTrail = ReadAlongWordHighlight.tints(HighlightLook(ReadAlongColor.GOLD, 40), 0xFFFBFAF6.toInt(), 0xFF282B29.toInt(), dark = false)
        val trailed = ReadAlongWordHighlight.paint(ReadAlongWordHighlight.mark(at(600))!!, withTrail)
        assertTrue(trailed.trail)
        assertEquals(withTrail.trail, trailed.decorationTint)
        // No pack: the sentence's own segment, washed whole in the sentence wash of the chosen colour.
        val sentence = ReadAlongWordHighlight.paint(ReadAlongWordHighlight.mark(at(4_500))!!, withTrail)
        assertNull(sentence.word)
        assertEquals(withTrail.sentence, sentence.decorationTint)
        assertTrue(sentence.trail)
    }
}
