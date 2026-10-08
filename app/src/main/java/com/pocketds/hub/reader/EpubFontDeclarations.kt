package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Typeface
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The bundled faces ([EpubFonts]) declared to Readium Kotlin 3.0.0 (#47): the app's `fonts/` assets are served
 * to the book (`servedAssets`), and each face is a font family with its files as `@font-face` rules, a
 * variable file's whole weight range at once. A book asks for one by the name in its preferences
 * ([EpubFonts.Face.css]).
 */
@OptIn(ExperimentalReadiumApi::class)
object EpubFontDeclarations {
    fun declare(configuration: EpubNavigatorFragment.Configuration) {
        configuration.servedAssets = configuration.servedAssets + EpubFonts.SERVED_ASSETS
        EpubFonts.CHOICES.forEach { face ->
            val name = face.css ?: return@forEach
            configuration.addFontFamilyDeclaration(FontFamily(name)) {
                face.sources.forEach { source ->
                    addFontFace {
                        addSource(source.asset)
                        setFontStyle(if (source.italic) FontStyle.ITALIC else FontStyle.NORMAL)
                        if (source.variable) setFontWeight(source.minWeight..source.maxWeight)
                        else setFontWeight(FontWeight.entries.first { it.value == source.minWeight })
                    }
                }
            }
        }
    }

    /** The family Readium is asked for: null for the book's own font. */
    fun family(id: String?): FontFamily? = EpubFonts.face(id).css?.let(::FontFamily)
}

/** The faces drawn for the menu's "Aa", from the same files the book is given. Loaded once each. */
object EpubTypefaces {
    private val cache = mutableMapOf<String, Typeface>()

    /** The typeface of [face]'s "Aa": the bundled roman, or the platform's serif for the book's own. */
    fun of(context: Context, face: EpubFonts.Face): Typeface {
        val source = face.preview ?: return Typeface.SERIF
        return synchronized(cache) {
            cache.getOrPut(source.asset) {
                runCatching { Typeface.createFromAsset(context.applicationContext.assets, source.asset) }.getOrDefault(Typeface.SERIF)
            }
        }
    }
}
