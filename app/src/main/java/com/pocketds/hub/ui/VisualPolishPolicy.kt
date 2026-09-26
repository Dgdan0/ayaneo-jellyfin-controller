package com.pocketds.hub.ui

import com.pocketds.hub.model.PlaybackTrack
import java.util.Locale
import kotlin.math.pow
import kotlin.math.round

object PanelGeometry { fun width(availableDp: Int) = (availableDp - 24).coerceIn(1, 320) }

/** Choose the foreground per surface, never reuse the primary-action foreground for status badges. */
object SemanticColor {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v=((color ushr shift) and 255)/255.0
            return if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)
        }
        return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
    }
    fun contrast(a: Int,b: Int): Double {
        val x=luminance(a); val y=luminance(b)
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
    fun foreground(surface: Int): Int = if (contrast(surface, -1)>=contrast(surface, 0xff111116.toInt())) -1 else 0xff111116.toInt()
}

data class ValueRange(val min: Float, val max: Float, val step: Float) {
    val steps: Int get() = round((max-min)/step).toInt()
    fun at(index: Int): Float = round((min + index.coerceIn(0,steps)*step)*1000)/1000
    fun index(value: Float): Int = round((value-min)/step).toInt().coerceIn(0,steps)
    fun move(value: Float, direction: Int): Float = at(index(value)+direction)
}

data class TrackPresentation(val title: String, val detail: String) {
    companion object {
        fun of(track: PlaybackTrack): TrackPresentation {
            val code=track.language.trim().lowercase(Locale.ROOT)
            val language=when(code) {
                "eng" -> "en"; "heb" -> "he"; "jpn" -> "ja"; "spa" -> "es"; "fre", "fra" -> "fr"
                "ger", "deu" -> "de"; "ara" -> "ar"; "rus" -> "ru"; "chi", "zho" -> "zh"
                "und", "" -> ""; else -> code
            }
            val name=language.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }.orEmpty()
            return TrackPresentation(name.ifBlank { track.label.ifBlank { "Track ${track.index+1}" } }, buildList {
                if (track.forced) add("Forced")
                if (track.hearingImpaired) add("SDH")
                if (track.channels>0) add(when(track.channels) { 1->"Mono";2->"Stereo";6->"5.1 surround";8->"7.1 surround";else->"${track.channels} channels" })
                if(track.codec.isNotBlank()) add(track.codec.uppercase(Locale.ROOT))
                if(track.default) add("Default")
                if(track.external) add("External")
                // A commentary or alternate mix must remain distinguishable from the main track.
                if (name.isNotBlank() && track.label.isNotBlank()) {
                    val generated = setOf(name, code, language, track.codec, "Default", "External",
                        "Forced", "SDH", "Hearing impaired", "Mono", "Stereo", "Surround", "pocketds",
                        "1.0", "2.0", "5.1", "7.1", "${track.channels} channels")
                        .map { it.lowercase(Locale.ROOT) }.toSet()
                    track.label.split(Regex("\\s+[-·|]\\s+"))
                        .map(String::trim)
                        .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in generated }
                        .forEach(::add)
                }
            }.distinct().joinToString(" · "))
        }
    }
}
