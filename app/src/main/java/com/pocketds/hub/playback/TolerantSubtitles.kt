package com.pocketds.hub.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.SimpleSubtitleDecoder
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.ssa.SsaParser

/**
 * Subtitle decoders that skip a line they cannot parse instead of failing.
 *
 * Measured on the Bleach BD release (DBD-Raws, ParanDark subs): 186 of ~5000
 * ASS packets per track carry no Matroska block duration, so the line ends the
 * moment it starts and Media3 1.4.1's SsaParser throws IllegalStateException.
 * Parsed during extraction that failed the whole video; in the stock text
 * renderer every such line logged a stack trace and rebuilt the decoder,
 * dropping lines queued behind it. Here the broken line is simply empty.
 */
@OptIn(UnstableApi::class)
internal object TolerantSubtitleDecoderFactory : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean = SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        if (format.sampleMimeType == MimeTypes.TEXT_SSA) SkippingSsaDecoder(format.initializationData)
        else SubtitleDecoderFactory.DEFAULT.createDecoder(format)
}

@OptIn(UnstableApi::class)
private class SkippingSsaDecoder(initializationData: List<ByteArray>) : SimpleSubtitleDecoder("SkippingSsaDecoder") {
    private val parser = SsaParser(initializationData)

    override fun decode(data: ByteArray, length: Int, reset: Boolean): Subtitle {
        if (reset) parser.reset()
        return try {
            parser.parseToLegacySubtitle(data, 0, length)
        } catch (_: IllegalStateException) {
            NoCues
        }
    }
}

@OptIn(UnstableApi::class)
private object NoCues : Subtitle {
    override fun getNextEventTimeIndex(timeUs: Long): Int = C.INDEX_UNSET
    override fun getEventTimeCount(): Int = 0
    override fun getEventTime(index: Int): Long = throw IndexOutOfBoundsException(index.toString())
    override fun getCues(timeUs: Long): List<Cue> = emptyList()
}
