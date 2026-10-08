package com.pocketds.hub.reader

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.*
import com.pocketds.hub.ui.*

/** Visible samples, immediate global preferences, and stable controller focus. */
class EpubAppearancePanel(context: Context, colors: PocketColors, private val ring: () -> Boolean) : SidePanelView(context, colors, ring) {
    private var value = EpubReaderPreferences()
    private var changed: (EpubReaderPreferences) -> Unit = {}
    private var pageInfo = PageInfoChoice()
    private var pageInfoChanged: (PageInfoChoice) -> Unit = {}
    private var brightness = 1f
    private var brightnessChanged: (Float) -> Unit = {}
    private var section = "font"

    /**
     * [pageInfo]: Kindle's corners (#42), changed from the "Page info" tab and handed to [onPageInfoChanged]. [brightness] is
     * the slider fixed at the foot of every tab (#47), where the page is seen as it is set; it moved here from Comfort.
     */
    fun show(
        initial: EpubReaderPreferences, onChanged: (EpubReaderPreferences) -> Unit, onClose: () -> Unit,
        pageInfo: PageInfoChoice = PageInfoChoice(), onPageInfoChanged: (PageInfoChoice) -> Unit = {},
        brightness: Float? = null, onBrightness: (Float) -> Unit = {}
    ) {
        value = initial; changed = onChanged
        this.pageInfo = pageInfo; pageInfoChanged = onPageInfoChanged
        this.brightness = brightness ?: 1f; brightnessChanged = onBrightness; hasBrightness = brightness != null
        section = if (section == "spacing") "font" else section
        open("Reading appearance", "All books · changes save automatically", onDismiss = onClose); render()
    }
    private fun update(next: EpubReaderPreferences, rebuild: Boolean = true) {
        value = next; changed(value); if (rebuild) render()
    }
    private var hasBrightness = false
    private fun updatePageInfo(next: PageInfoChoice) {
        pageInfo = next; pageInfoChanged(next); render()
    }

    /** B on the Spacing page goes back to the Font tab it was opened from, not out of the sheet. */
    override fun onPad(action: com.pocketds.hub.input.PadAction): Boolean {
        if (isOpen && section == "spacing" && action == com.pocketds.hub.input.PadAction.Back) { section = "font"; render(); return true }
        return super.onPad(action)
    }
    private fun heading(label: String) { body.addView(TextView(context).apply {
        text = label; textSize = 12f; setTextColor(colors.mutedText); setPadding(dp(4), dp(10), dp(4), dp(5))
    }) }
    private fun row(vararg views: View) { body.addView(LinearLayout(context).apply {
        clipChildren = false; clipToPadding = false
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(5)) }) }
    }) }
    private fun sample(label: String, key: String, selected: Boolean, preview: View, previewDp: Int = 49, pick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = key
        setPadding(dp(5), dp(5), dp(5), dp(5)); isSelected = selected
        contentDescription = "$label${if (selected) ", selected" else ""}"
        background = ThemeGradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(this@EpubAppearancePanel.colors.cardSurface)
            setStroke(dp(if (selected) 2 else 1), if (selected) this@EpubAppearancePanel.colors.accent else this@EpubAppearancePanel.colors.cardSurfacePressed)
        }
        addView(preview, LinearLayout.LayoutParams(-1, dp(previewDp)))
        addView(TextView(context).apply {
            text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(colors.primaryText)
            setPadding(0, dp(5), 0, 0); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, -2))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        Styler.makeFocusable(this); FocusDecorator.attach(this, ring, scale = false); activateOnTap(pick)
    }
    private fun illustration(columns: Int = 1, margin: Float = .12f, spacing: Int = 6): View = ImageView(context).apply {
        setImageDrawable(PageSampleDrawable(colors.primaryText, colors.cardSurfacePressed, columns, margin, spacing))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun render() {
        val focusKey = findFocus()?.tag
        resetBody()
        tabs(listOf("font" to "Font", "layout" to "Layout", "themes" to "Themes", "info" to "Page info"), if (section == "spacing") "font" else section, dividers = true) { section = it; render() }
        // Kindle's brightness, fixed at the foot of every tab (#47).
        if (hasBrightness) footer.addView(ValueAdjusterView(context, colors, "Brightness", ScreenComfort.BRIGHTNESS_RANGE, brightness, ScreenComfort::brightnessLabel, AdjusterStyle.FOOT) {
            brightness = it; brightnessChanged(it)
        }.apply { tag = "brightness" }, LinearLayout.LayoutParams(0, -2, 1f))
        when (section) {
            "info" -> {
                // Kindle's corners (#42): each can be turned off, and a tap on the bottom left moves to the next.
                heading("Top")
                choice("Book title", if (pageInfo.title) "On · in the middle" else "Off", pageInfo.title) { updatePageInfo(pageInfo.copy(title = !pageInfo.title)) }.tag = "info:title"
                choice("Clock", if (pageInfo.clock) "On · at the right" else "Off", pageInfo.clock) { updatePageInfo(pageInfo.copy(clock = !pageInfo.clock)) }.tag = "info:clock"
                heading("Bottom left")
                PageInfoCorner.entries.forEach { corner ->
                    choice(corner.choice, selected = pageInfo.corner == corner) { updatePageInfo(pageInfo.copy(corner = corner)) }.tag = "info:${corner.name}"
                }
                heading("Bottom right")
                choice("Percentage", if (pageInfo.percentage) "On" else "Off", pageInfo.percentage) { updatePageInfo(pageInfo.copy(percentage = !pageInfo.percentage)) }.tag = "info:percentage"
                note("A tap on the bottom left moves to the next choice. The corners are hidden while the menu is open.")
            }
            "themes" -> {
                heading("Page colour")
                val tiles = EpubPagePalette.CHOICES.map { theme ->
                    val palette = EpubPagePalette.of(theme)
                    sample(EpubPagePalette.label(theme), "theme:$theme", value.theme == theme, TextView(context).apply {
                        text = "Aa  The story\ncontinues."; textSize = 15f; typeface = Typeface.SERIF; gravity = Gravity.CENTER
                        setTextColor(palette.second); setBackgroundColor(palette.first); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }) { update(value.copy(theme = theme)) }
                }
                row(tiles[0], tiles[1], tiles[2]); row(tiles[3], tiles[4], View(context))
                choice("Use system colours", "Paper by day, Dark at night", selected = value.theme == EpubTheme.SYSTEM) { update(value.copy(theme = EpubTheme.SYSTEM)) }.tag = "system"
            }
            "font" -> {
                // A typeface of the reader's own needs the reader's typography; the book's own face does not
                // take the book's whole look with it: "Publisher styling" is that switch (#42, Part 3).
                // Each tile draws its "Aa" in the very file the book is given (#47).
                val faces = EpubFonts.CHOICES.map { face ->
                    sample(face.label, "font:${face.id}", EpubFonts.face(value.fontFamily) == face, TextView(context).apply {
                        text = "Aa"; textSize = 25f; gravity = Gravity.CENTER
                        typeface = EpubTypefaces.of(context, face)
                        setTextColor(colors.primaryText); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, previewDp = 36) { update(value.copy(fontFamily = face.id, publisherStyles = if (face == EpubFonts.Face.ORIGINAL) value.publisherStyles else false)) }
                }
                row(faces[0], faces[1]); row(faces[2], faces[3])
                // Kindle's size slider: a small A, a large A, a mark at every 10% from 70 to 200; left and right on the pad move a step.
                body.addView(ValueAdjusterView(context, colors, "Size", EpubLayoutPolicy.SIZES, value.fontScale, EpubLayoutPolicy::sizeLabel, AdjusterStyle.SIZE) {
                    update(value.copy(fontScale = it), rebuild = false)
                }.apply { tag = "size" })
                // Line spacing and the margins are one page of their own, as on Kindle.
                choice("Spacing", EpubLayoutPolicy.spacingSummary(value)) { section = "spacing"; render() }.tag = "spacing"
            }
            "spacing" -> {
                choice("‹ Font", "Back · B") { section = "font"; render() }.tag = "spacing-back"
                heading("Line spacing")
                row(*EpubLayoutPolicy.SPACING.mapIndexed { index, (amount, label) ->
                    sample(label, "spacing:$amount", kotlin.math.abs(value.lineHeight - amount) < .01f, illustration(spacing = 5 + index * 3)) { update(value.copy(lineHeight = amount, publisherStyles = false)) }
                }.toTypedArray())
                heading("Margins")
                row(*PageGeometry.Margin.entries.map { margin ->
                    sample(margin.label, "margin:${margin.stored}", PageGeometry.preset(value.pageMargins) == margin, illustration(margin = margin.outerDp / 240f)) { update(value.copy(pageMargins = margin.stored)) }
                }.toTypedArray())
            }
            else -> {
                heading("Columns")
                row(*listOf(EpubColumns.ONE to "One page", EpubColumns.TWO to "Two pages").map { (column, label) ->
                    sample(label, "column:$column", value.columns == column, illustration(if (column == EpubColumns.TWO) 2 else 1)) { update(EpubLayoutPolicy.selectColumns(value, column)) }
                }.toTypedArray())
                choice("Automatic columns", selected = value.columns == EpubColumns.AUTO) { update(EpubLayoutPolicy.selectColumns(value, EpubColumns.AUTO)) }.tag = "auto"
                choice("Continuous scrolling", if (value.scroll) "On" else "Off", value.scroll) { update(EpubLayoutPolicy.selectScroll(value, !value.scroll)) }.tag = "scroll"
                // One column, no scrolling. Ebook page breaks follow font and screen size; these are not printed page numbers.
                choice("One full page per screen", if (value.onePagePerScreen) "On" else "Off", value.onePagePerScreen) {
                    update(EpubLayoutPolicy.selectOnePage(value, !value.onePagePerScreen))
                }.tag = "one-page"
                choice("Publisher styling", if (value.publisherStyles) "On" else "Off", value.publisherStyles) { update(value.copy(publisherStyles = !value.publisherStyles)) }.tag = "publisher"
                choice("Justified text", if (value.textAlignment == "justify") "On" else "Off", value.textAlignment == "justify") {
                    update(value.copy(textAlignment = if (value.textAlignment == "justify") "start" else "justify", publisherStyles = false))
                }.tag = "alignment"
                // Words break at their syllables, which keeps justified lines even. The book's own styling ignores it.
                choice("Hyphenation", if (value.hyphenation) "On" else "Off", value.hyphenation) {
                    update(value.copy(hyphenation = !value.hyphenation, publisherStyles = if (value.hyphenation) value.publisherStyles else false))
                }.tag = "hyphenation"
                // For a device that changed its look once and so never saw the new default: one press, and only the text's style moves.
                choice("Reset text style", EpubLayoutPolicy.textStyleSummary()) { update(EpubLayoutPolicy.resetTextStyle(value)) }.tag = "reset-text-style"
            }
        }
        focusBody(getFocusables(FOCUS_FORWARD).firstOrNull { focusKey != null && it.tag == focusKey })
    }
}

private class PageSampleDrawable(private val ink: Int, private val paper: Int, private val columns: Int, private val margin: Float, private val spacing: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val left = bounds.left.toFloat(); val top = bounds.top.toFloat(); val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        paint.color = paper; canvas.drawRect(bounds, paint)
        paint.color = ink; paint.alpha = 150; paint.strokeWidth = (h / 50f).coerceAtLeast(1f)
        val inset = w * margin; val gutter = if (columns == 2) w * .08f else 0f
        val columnWidth = (w - inset * 2 - gutter) / columns
        repeat(columns) { column ->
            val x = left + inset + column * (columnWidth + gutter); var y = top + h * .15f
            while (y < top + h * .86f) { canvas.drawLine(x, y, x + columnWidth, y, paint); y += h * spacing / 50f }
        }
        paint.alpha = 255
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
