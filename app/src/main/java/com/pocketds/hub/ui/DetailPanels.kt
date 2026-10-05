package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader

/**
 * The people in a title, as faces in a row: a round portrait (or initials),
 * the name, and who they played. Each is focusable so the pad can run along the
 * row; [onOpen] decides whether choosing one does anything.
 *
 * Each face is [DetailArtworkCardView.portrait], the one round portrait with
 * its ring round it, as an author's is.
 */
class CastRowView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : FocusHorizontalScrollView(context) {
    data class Person(val key: String, val name: String, val role: String, val image: Any?)

    var onOpen: ((Person) -> Unit)? = null
    var onFocused: (() -> Unit)? = null
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        setPadding(dp(20), dp(10), dp(20), dp(10))
    }

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        clipChildren = false
        addView(row, ViewGroup.LayoutParams(WRAP, WRAP))
    }

    fun bind(people: List<Person>, loader: ImageLoader) {
        row.removeAllViews()
        people.forEach { person -> row.addView(card(person, loader), LinearLayout.LayoutParams(dp(CARD_DP), WRAP).apply { marginEnd = dp(10) }) }
    }

    val first: View? get() = row.getChildAt(0)

    private fun card(person: Person, loader: ImageLoader): View = DetailArtworkCardView(context, colors, ringVisible).apply {
        portrait(FACE_DP)
        setPadding(0, dp(4), 0, dp(4))
        contentDescription = listOf(person.name, person.role).filter(String::isNotBlank).joinToString(", as ")
        titleView.text = person.name
        titleView.textSize = 12f
        titleView.textWeight(600)
        (titleView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
        subtitleView.text = person.role
        subtitleView.visibility = if (person.role.isBlank()) View.GONE else View.VISIBLE
        (subtitleView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(1)
        Artwork.bind(image, loader, person.image, opaque = true, onMissing = {
            image.setImageDrawable(InitialsDrawable(person.name, colors))
        })
        FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) onFocused?.invoke() }
        activateOnTap { onOpen?.invoke(person) }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val CARD_DP = 86
        const val FACE_DP = 64
    }
}

/**
 * The facts about a title in small cards, three across: studio, director,
 * genres, the file. Focusable, so the pad can reach the bottom of a long page;
 * there is nothing to do with one.
 *
 * They are the prototype's Details list: a small capital label over the
 * words, no card, and only the focus ring round the one in focus.
 */
class FactsGridView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {
    data class Fact(val label: String, val value: String)

    var onFocused: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(16))
    }

    fun bind(facts: List<Fact>) {
        removeAllViews()
        facts.chunked(COLUMNS).forEach { chunk ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            chunk.forEachIndexed { index, fact ->
                line.addView(card(fact), LayoutParams(0, MATCH, 1f).apply { if (index > 0) marginStart = dp(10) })
            }
            repeat(COLUMNS - chunk.size) { line.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = dp(10) }) }
            addView(line, LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
    }

    val first: View? get() = (getChildAt(0) as? ViewGroup)?.getChildAt(0)

    private fun card(fact: Fact) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(12), dp(9), dp(12), dp(10))
        background = Styler.focusOutline(context, colors, 11f)
        contentDescription = "${fact.label}: ${fact.value}"
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(TextView(context).apply {
            text = fact.label.uppercase(); typeRole(Type.Role.EYEBROW); setTextColor(LABEL)
        })
        addView(TextView(context).apply {
            text = fact.value; textSize = 12.5f
            setTextColor(VALUE)
            setLineSpacing(0f, 1.12f)
        }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused -> if (focused) onFocused?.invoke() }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLUMNS = 3
        /** The label white at 55%, the words at 92%. */
        const val LABEL = 0x8CFFFFFF.toInt()
        const val VALUE = 0xEBFFFFFF.toInt()
    }
}
