package com.pocketds.hub.reader

import kotlinx.serialization.Serializable

@Serializable
enum class EpubTheme { SYSTEM, LIGHT, SEPIA, DARK, BLUE }

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
    val textAlignment: String = "start",
    val onePagePerScreen: Boolean = false
)

object EpubLayoutPolicy {
    private const val AUTO_TWO_COLUMN_MIN_WIDTH_DP = 840

    fun columnCount(
        preferences: EpubReaderPreferences,
        viewportWidthDp: Int,
        publicationAllowsSpreads: Boolean
    ): Int {
        if (preferences.onePagePerScreen || preferences.scroll || !publicationAllowsSpreads) return 1
        return when (preferences.columns) {
            EpubColumns.ONE -> 1
            EpubColumns.TWO -> 2
            EpubColumns.AUTO -> if (viewportWidthDp >= AUTO_TWO_COLUMN_MIN_WIDTH_DP) 2 else 1
        }
    }

    fun selectColumns(value: EpubReaderPreferences, columns: EpubColumns): EpubReaderPreferences =
        value.copy(columns = columns, scroll = if (columns == EpubColumns.TWO) false else value.scroll, onePagePerScreen = false)

    fun selectScroll(value: EpubReaderPreferences, enabled: Boolean): EpubReaderPreferences =
        value.copy(scroll = enabled, columns = if (enabled && value.columns == EpubColumns.TWO) EpubColumns.AUTO else value.columns,
            onePagePerScreen = if (enabled) false else value.onePagePerScreen)

    fun selectOnePage(value: EpubReaderPreferences, enabled: Boolean): EpubReaderPreferences =
        if (enabled) value.copy(onePagePerScreen = true, columns = EpubColumns.ONE, scroll = false)
        else value.copy(onePagePerScreen = false)
}

object EpubChromePolicy {
    fun handlesTap(horizontalFraction: Float, controlsVisible: Boolean) = controlsVisible || horizontalFraction in .3f.. .7f
}

/** Appearance is applied live and committed with each change. Closing retains the last setting. */
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
