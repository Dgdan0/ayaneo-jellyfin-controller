package com.pocketds.hub.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.pocketds.hub.settings.SortPreference

/**
 * Two buttons above a library grid: what to sort by, and which way.
 *
 * The field button opens a list of fields; the direction button flips with one
 * press and opens nothing. They used to be one panel listing the fields and
 * then Ascending / Descending underneath, so changing only the direction meant
 * scrolling past every field, and the two choices read as one list.
 */
class LibrarySortControls(
    context: Context,
    private val colors: PocketColors,
    private val fields: List<Pair<String, String>>,
    initial: SortPreference,
    private val overlay: () -> ChoiceOverlay,
    private val onChange: (SortPreference) -> Unit,
    /** The hint bar follows the menu opening and closing. */
    private val onMenu: () -> Unit
) : LinearLayout(context) {
    var value: SortPreference = initial
        private set
    val fieldButton = button(AppIcon.SORT) { showFields() }
    val directionButton = button(null) { set(value.copy(ascending = !value.ascending)) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(fieldButton)
        addView(directionButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = Styler.dpInt(context, 8f)
        })
        refresh()
    }

    /** Shows [next] without reporting a change: the screen changed it itself. */
    fun update(next: SortPreference) {
        value = next
        refresh()
    }

    private fun set(next: SortPreference) {
        if (next == value) return
        value = next
        refresh()
        onChange(next)
    }

    private fun fieldLabel(field: String) = fields.firstOrNull { it.first == field }?.second ?: field

    private fun refresh() {
        fieldButton.text = "${fieldLabel(value.field)}  ▾"
        fieldButton.contentDescription = "Sort by ${fieldLabel(value.field)}"
        directionButton.text = "${if (value.ascending) "↑" else "↓"}  ${value.directionLabel()}"
        directionButton.contentDescription = "${value.directionLabel()}, press to reverse"
    }

    /** Y on the grid opens this too. */
    fun showFields() {
        overlay().pickValue(
            "Sort by", "",
            fields.map { it.first }, value.field, ::fieldLabel,
            onCancel = onMenu
        ) { picked ->
            // A new field starts in its own natural direction: newest first for dates.
            set(if (picked == value.field) value else SortPreference.forField(picked))
            onMenu()
        }
        onMenu()
    }

    private fun button(icon: AppIcon?, open: () -> Unit) = CenteredIconTextView(context).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        minimumHeight = Styler.dpInt(context, 48f)
        setTextColor(colors.primaryText)
        setPadding(Styler.dpInt(context, 12f), 0, Styler.dpInt(context, 12f), 0)
        if (icon != null) setCenteredIcon(AppIconDrawable(icon, colors.mutedText), Styler.dpInt(context, 20f), Styler.dpInt(context, 8f))
        background = Styler.chipBackground(context, colors)
        Styler.makeFocusable(this)
        activateOnTap(open)
    }
}
