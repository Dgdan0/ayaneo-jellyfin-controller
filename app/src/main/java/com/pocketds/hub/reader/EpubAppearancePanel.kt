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
    private var section = "font"

    /** [pageInfo]: Kindle's corners (#42), changed from the "Page info" tab and handed to [onPageInfoChanged]. */
    fun show(
        initial: EpubReaderPreferences, onChanged: (EpubReaderPreferences) -> Unit, onClose: () -> Unit,
        pageInfo: PageInfoChoice = PageInfoChoice(), onPageInfoChanged: (PageInfoChoice) -> Unit = {}
    ) {
        value = initial; changed = onChanged
        this.pageInfo = pageInfo; pageInfoChanged = onPageInfoChanged
        open("Reading appearance", "All books · changes save automatically", onDismiss = onClose); render()
    }
    private fun update(next: EpubReaderPreferences, rebuild: Boolean = true) {
        value = next; changed(value); if (rebuild) render()
    }
    private fun updatePageInfo(next: PageInfoChoice) {
        pageInfo = next; pageInfoChanged(next); render()
    }
    private fun heading(label: String) { body.addView(TextView(context).apply {
        text = label; textSize = 12f; setTextColor(colors.mutedText); setPadding(dp(4), dp(10), dp(4), dp(5))
    }) }
    private fun row(vararg views: View) { body.addView(LinearLayout(context).apply {
        clipChildren = false; clipToPadding = false
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(5)) }) }
    }) }
    private fun sample(label: String, key: String, selected: Boolean, preview: View, pick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; tag = key
        setPadding(dp(5), dp(5), dp(5), dp(5)); isSelected = selected
        contentDescription = "$label${if (selected) ", selected" else ""}"
        background = ThemeGradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(this@EpubAppearancePanel.colors.cardSurface)
            setStroke(dp(if (selected) 2 else 1), if (selected) this@EpubAppearancePanel.colors.accent else this@EpubAppearancePanel.colors.cardSurfacePressed)
        }
        addView(preview, LinearLayout.LayoutParams(-1, dp(49)))
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
        tabs(listOf("font" to "Font", "layout" to "Layout", "themes" to "Themes", "info" to "Page info"), section, dividers = true) { section = it; render() }
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
                heading("Typeface")
                // A typeface of the reader's own needs the reader's typography; the book's own face does not
                // take the book's whole look with it: "Publisher styling" is that switch (#42, Part 3).
                row(*listOf("publisher" to "Publisher", "serif" to "Serif", "sans-serif" to "Sans").map { (id, label) ->
                    sample(label, "font:$id", value.fontFamily == id, TextView(context).apply {
                        text = "Aa"; textSize = 25f; gravity = Gravity.CENTER
                        typeface = if (id == "sans-serif") Typeface.SANS_SERIF else Typeface.SERIF
                        setTextColor(colors.primaryText); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }) { update(value.copy(fontFamily = id, publisherStyles = if (id == "publisher") value.publisherStyles else false)) }
                }.toTypedArray())
                body.addView(ValueAdjusterView(context, colors, "Font size", ValueRange(.7f, 2f, .1f), value.fontScale, { "${(it * 100).toInt()}%" }) {
                    update(value.copy(fontScale = it), rebuild = false)
                })
                body.addView(CheckBox(context).apply {
                    text = "One full page per screen"; textSize = 14f; setTextColor(colors.primaryText)
                    buttonTintList = android.content.res.ColorStateList.valueOf(colors.accent)
                    isChecked = value.onePagePerScreen; tag = "one-page"; minimumHeight = dp(48)
                    contentDescription = "One full page per screen"; FocusDecorator.attach(this, ring, scale = false)
                    setOnCheckedChangeListener { _, checked -> update(EpubLayoutPolicy.selectOnePage(value, checked), rebuild = false) }
                })
                body.addView(TextView(context).apply {
                    text = "One column, no scrolling. Ebook page breaks follow font and screen size; these are not printed page numbers."
                    textSize = 12f; setTextColor(colors.mutedText); setPadding(dp(5), 0, dp(5), dp(8))
                })
            }
            else -> {
                heading("Columns")
                row(*listOf(EpubColumns.ONE to "One page", EpubColumns.TWO to "Two pages").map { (column, label) ->
                    sample(label, "column:$column", value.columns == column, illustration(if (column == EpubColumns.TWO) 2 else 1)) { update(EpubLayoutPolicy.selectColumns(value, column)) }
                }.toTypedArray())
                heading("Margins")
                row(*PageGeometry.Margin.entries.map { margin ->
                    sample(margin.label, "margin:${margin.stored}", PageGeometry.preset(value.pageMargins) == margin, illustration(margin = margin.outerDp / 240f)) { update(value.copy(pageMargins = margin.stored)) }
                }.toTypedArray())
                heading("Line spacing")
                row(*EpubLayoutPolicy.SPACING.mapIndexed { index, (amount, label) ->
                    sample(label, "spacing:$amount", kotlin.math.abs(value.lineHeight - amount) < .01f, illustration(spacing = 5 + index * 3)) { update(value.copy(lineHeight = amount, publisherStyles = false)) }
                }.toTypedArray())
                choice("Automatic columns", selected = value.columns == EpubColumns.AUTO) { update(EpubLayoutPolicy.selectColumns(value, EpubColumns.AUTO)) }.tag = "auto"
                choice("Continuous scrolling", if (value.scroll) "On" else "Off", value.scroll) { update(EpubLayoutPolicy.selectScroll(value, !value.scroll)) }.tag = "scroll"
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
