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
import androidx.core.graphics.ColorUtils
import coil.ImageLoader

/**
 * The people in a title, as faces in a row: a round portrait (or initials),
 * the name, and who they played. Each is focusable so the pad can run along the
 * row; [onOpen] decides whether choosing one does anything.
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

    private fun card(person: Person, loader: ImageLoader): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        contentDescription = listOf(person.name, person.role).filter(String::isNotBlank).joinToString(", as ")
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val face = FrameLayout(context).apply {
            isDuplicateParentStateEnabled = true
            background = ThemeGradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(this@CastRowView.colors.posterPlaceholder) }
            clipToOutline = true
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ThemeGradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), this@CastRowView.colors.focusRing)
                })
            }
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        face.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(face, LinearLayout.LayoutParams(dp(FACE_DP), dp(FACE_DP)))
        addView(TextView(context).apply {
            text = person.name; textSize = 12f; textWeight(600); setTextColor(colors.primaryText)
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        if (person.role.isNotBlank()) addView(TextView(context).apply {
            text = person.role; textSize = 11f; setTextColor(colors.mutedText)
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(1) })
        Artwork.bind(image, loader, person.image, opaque = true, onMissing = {
            image.setImageDrawable(InitialsDrawable(person.name, colors))
        })
        FocusDecorator.attach(this, ringVisible)
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
        background = Styler.cardBackground(context, colors, cornerDp = 12f, focusStrokeDp = 2f)
        contentDescription = "${fact.label}: ${fact.value}"
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        addView(TextView(context).apply {
            text = fact.label.uppercase(); typeRole(Type.Role.EYEBROW); setTextColor(colors.mutedText)
        })
        addView(TextView(context).apply {
            text = fact.value; textSize = 12.5f; setTextColor(ColorUtils.blendARGB(colors.mutedText, colors.primaryText, .75f))
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
    }
}
