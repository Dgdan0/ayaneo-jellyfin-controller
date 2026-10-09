package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-pass reading of an overlay's `<par>`s (#66): what the DOM gave, and what it refused. */
class ReadAlongSmilTest {
    private fun pars(xml: String) = ReadAlongSmil.pars(xml).map { listOf(it.textSrc, it.audioSrc, it.clipBegin, it.clipEnd, it.sentenceRef) }

    @Test fun `every par in document order, its own first text and audio, and the place its seq names`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!-- a comment with <par> in it -->
            <smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0"><body>
              <seq id="ch" epub:textref="../t.xhtml" epub:type="chapter">
                <par id="a"><text src="../t.xhtml#s0"/><audio src="../a.mp4" clipBegin="1s" clipEnd="2s"/></par>
                <seq id="s1-seq" epub:textref="../t.xhtml#s1">
                  <par><text src="../t.xhtml#s1-w0"/><audio src="../a.mp4" clipBegin="2s" clipEnd="2.5s"/><text src="../t.xhtml#ignored"/></par>
                  <par><audio src="../a.mp4" clipBegin="2.5s" clipEnd="3s"></audio><text src='../t.xhtml#s1-w1'></text></par>
                  <seq><par><text src="../t.xhtml#s1-w2"/><audio src="../a.mp4" clipBegin="3s" clipEnd="3.5s"/></par></seq>
                </seq>
                <par><wrap><text src="../t.xhtml#nested"/></wrap><audio src="../a.mp4" clipEnd="9s"/></par>
                <par/>
                <![CDATA[ <par><text src="x#y"/><audio src="z" clipEnd="1s"/></par> ]]>
              </seq>
            </body></smil>"""
        assertEquals(listOf(
            listOf("../t.xhtml#s0", "../a.mp4", "1s", "2s", null),
            listOf("../t.xhtml#s1-w0", "../a.mp4", "2s", "2.5s", "../t.xhtml#s1"),
            listOf("../t.xhtml#s1-w1", "../a.mp4", "2.5s", "3s", "../t.xhtml#s1"),
            // A <seq> with no place of its own inside one with a place: the place carries on.
            listOf("../t.xhtml#s1-w2", "../a.mp4", "3s", "3.5s", "../t.xhtml#s1")
        ), pars(xml))
    }

    @Test fun `prefixed names, entities and character references are what they say`() {
        val xml = """<s:smil xmlns:s="http://www.w3.org/ns/SMIL" xmlns:e="http://www.idpf.org/2007/ops"><s:body>
            <s:seq e:textref="Text/Tom &amp; Jerry.xhtml#s&#49;"><s:par><s:text src="Text/Tom &amp; Jerry.xhtml#s1-w&#x30;"/><s:audio src="A&apos;1.mp4" clipBegin="0s" clipEnd="1s"/></s:par></s:seq>
            </s:body></s:smil>"""
        assertEquals(listOf(listOf("Text/Tom & Jerry.xhtml#s1-w0", "A'1.mp4", "0s", "1s", "Text/Tom & Jerry.xhtml#s1")), pars(xml))
    }

    @Test fun `a document that is not well formed is refused, as the DOM refused it`() {
        for (bad in listOf(
            "<smil><body><par><text src='a#b'/><audio src='x' clipEnd='1s'/></body></smil>",
            "<smil><body><par><text src='a#b'/>",
            "<smil><body><par><text src='a#b/></par></body></smil>",
            "<smil><body><par><text src=a#b /></par></body></smil>",
            "<smil><body><par><text src='&nbsp;'/></par></body></smil>",
            "<smil><!-- never closed </smil>",
            "</smil>"
        )) assertTrue(bad, runCatching { ReadAlongSmil.pars(bad) }.isFailure)
    }

    @Test fun `a par without its text or its audio is no sentence`() {
        assertEquals(emptyList<List<String?>>(), pars("<smil><par><text src='a#b'/></par><par><audio src='x' clipEnd='1s'/></par></smil>"))
        assertNull(ReadAlongSmil.pars("<smil><seq epub:textref='t.xhtml'><par><text src='t.xhtml#a'/><audio src='x' clipEnd='1s'/></par></seq></smil>").single().sentenceRef)
    }
}
