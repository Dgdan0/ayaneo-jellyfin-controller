package com.pocketds.hub.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.screens.library.ReadingFormatChips
import com.pocketds.hub.ui.glass.GlassColors

/**
 * The formats of a book as a row of icon and name (#39): Audiobook, Ebook, Read along. A format the
 * book has is in the Books accent and is a control: Ⓐ or a tap opens it at your place. One it does not
 * have, or that is still on its way, is quiet grey and no stop for the pad; a tap on it says why not.
 * Which chips there are and what they open is [ReadingFormatChips]'; this draws them.
 */
class ReadingFormatRowView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {
    /** The chips by their kind (audiobook, ebook, readaloud), for focus and for hints. */
    val chipViews = linkedMapOf<String, TextView>()

    init { orientation = HORIZONTAL; clipChildren = false; clipToPadding = false }

    fun bind(
        chips: List<ReadingFormatChips.Chip>,
        /** A ready chip chosen. */
        onOpen: (ReadingFormatChips.Chip) -> Unit,
        /** A quiet chip pressed: [ReadingFormatChips.Chip.note] is what to say. */
        onQuiet: (ReadingFormatChips.Chip) -> Unit,
        /** A ready chip took focus. */
        onFocused: (ReadingFormatChips.Chip) -> Unit
    ) {
        removeAllViews(); chipViews.clear()
        visibility = if (chips.isEmpty()) GONE else VISIBLE
        chips.forEach { chip ->
            val icon = when (chip.kind) { "audiobook" -> AppIcon.HEADPHONES; "readaloud" -> AppIcon.READ_ALONG; else -> AppIcon.BOOK }
            val ink = if (chip.ready) colors.accent else GlassColors.QUIET
            val view = PillButton.create(context, colors, chip.label, icon, heightDp = HEIGHT_DP).apply {
                setTextColor(ink)
                val size = Styler.dpInt(context, 14f)
                setCompoundDrawables(AppIconDrawable(icon, ink).apply { setBounds(0, 0, size, size) }, null, null, null)
                contentDescription = if (chip.ready) "${chip.label}, opens at your place" else "${chip.label}, ${chip.readiness.description}"
                if (chip.ready) {
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) onFocused(chip) }
                    activateOnTap { onOpen(chip) }
                } else {
                    // Nothing to do on the pad, so no stop; a finger is told why.
                    isFocusable = false; isFocusableInTouchMode = false
                    alpha = QUIET_ALPHA
                    setOnClickListener { onQuiet(chip) }
                }
            }
            chipViews[chip.kind] = view
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = Styler.dpInt(context, 2f) })
        }
    }

    /** The first chip the pad can reach, if any. */
    fun firstReady(): View? = chipViews.values.firstOrNull { it.isFocusable }

    private companion object {
        const val HEIGHT_DP = 26f
        /** A format that is not there: the prototype's 42%, kept off the words so they stay readable. */
        const val QUIET_ALPHA = 0.72f
    }
}
