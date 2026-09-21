package com.pocketds.hub.reader

import kotlinx.serialization.Serializable

@Serializable
enum class EpubTheme { SYSTEM, LIGHT, SEPIA, DARK }

@Serializable
enum class EpubColumns { AUTO, ONE, TWO }

@Serializable
data class EpubReaderPreferences(
    val theme: EpubTheme = EpubTheme.SEPIA,
    val fontFamily: String = "publisher",
    val fontScale: Float = 1.0f,
    val lineHeight: Float = 1.25f,
    val pageMargins: Float = 1.0f,
    val columns: EpubColumns = EpubColumns.AUTO,
    val scroll: Boolean = false,
    val publisherStyles: Boolean = true,
    val textAlignment: String = "start"
)

object EpubLayoutPolicy {
    private const val AUTO_TWO_COLUMN_MIN_WIDTH_DP = 840

    fun columnCount(
        preferences: EpubReaderPreferences,
        viewportWidthDp: Int,
        publicationAllowsSpreads: Boolean
    ): Int {
        if (preferences.scroll || !publicationAllowsSpreads) return 1
        return when (preferences.columns) {
            EpubColumns.ONE -> 1
            EpubColumns.TWO -> 2
            EpubColumns.AUTO -> if (viewportWidthDp >= AUTO_TWO_COLUMN_MIN_WIDTH_DP) 2 else 1
        }
    }
}

data class EpubChromeInsets(val topDp: Int, val bottomDp: Int)

object EpubChromePolicy {
    private const val BAR_HEIGHT_DP = 58

    fun insets(visible: Boolean) = if (visible) {
        EpubChromeInsets(BAR_HEIGHT_DP, BAR_HEIGHT_DP)
    } else {
        EpubChromeInsets(0, 0)
    }
}

object EpubPreferenceAdjuster {
    fun nextTheme(value: EpubReaderPreferences) = value.copy(theme = when (value.theme) {
        EpubTheme.SYSTEM, EpubTheme.LIGHT -> EpubTheme.SEPIA
        EpubTheme.SEPIA -> EpubTheme.DARK
        EpubTheme.DARK -> EpubTheme.LIGHT
    })

    fun nextColumns(value: EpubReaderPreferences) = value.copy(columns = when (value.columns) {
        EpubColumns.AUTO -> EpubColumns.ONE
        EpubColumns.ONE -> EpubColumns.TWO
        EpubColumns.TWO -> EpubColumns.AUTO
    })

    fun changeFontSize(value: EpubReaderPreferences, delta: Float) =
        value.copy(fontScale = rounded((value.fontScale + delta).coerceIn(0.7f, 2.0f)))

    fun changeMargins(value: EpubReaderPreferences, delta: Float) =
        value.copy(pageMargins = rounded((value.pageMargins + delta).coerceIn(0.5f, 2.0f)))

    fun changeLineHeight(value: EpubReaderPreferences, delta: Float) =
        value.copy(lineHeight = rounded((value.lineHeight + delta).coerceIn(1.0f, 2.0f)))

    private fun rounded(value: Float): Float = kotlin.math.round(value * 10f) / 10f
}

/** Appearance is previewed live and persisted only after Done. */
class EpubPreferenceState(initial: EpubReaderPreferences) {
    var visible: EpubReaderPreferences = initial
        private set
    var saved: EpubReaderPreferences = initial
        private set
    var dirty: Boolean = false
        private set

    fun preview(value: EpubReaderPreferences) {
        visible = value
    }

    fun cancel() {
        visible = saved
    }

    fun commit(): Boolean {
        if (visible == saved) return false
        saved = visible
        dirty = true
        return true
    }

    fun markPersisted() {
        dirty = false
    }
}
