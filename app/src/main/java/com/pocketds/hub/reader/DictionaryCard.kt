package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.Styler

/** Small anchored definition card; the transparent layer dismisses on an outside tap. */
class DictionaryCard(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + .5f).toInt()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = ThemeGradientDrawable().apply {
            setColor(0xFF252931.toInt())
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), 0xFF5D7280.toInt())
        }
        elevation = dp(14).toFloat()
        isClickable = true
    }
    private val heading = TextView(context).apply { textSize = 19f; setTextColor(Color.WHITE) }
    private val definition = TextView(context).apply {
        textSize = 14f
        setTextColor(0xFFE4E9EC.toInt())
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private val play = TextView(context).apply {
        text = "▶  Play from this sentence"
        textSize = 14f
        setTextColor(0xFF80E9DD.toInt())
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Play narration from selected sentence"
        Styler.makeFocusable(this)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private val close = TextView(context).apply {
        text = "Close"
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = "Close definition"
        Styler.makeFocusable(this)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private var anchor = RectF()
    var onClose: () -> Unit = {}
    var onPlay: () -> Unit = {}
    val isOpen get() = visibility == View.VISIBLE

    init {
        visibility = View.GONE
        isClickable = true
        setOnClickListener { onClose() }
        addView(panel, LayoutParams(dp(300), LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(context).apply {
            text = "OFFLINE DICTIONARY"
            textSize = 10f
            letterSpacing = .12f
            setTextColor(0xFF8FAEB4.toInt())
        })
        panel.addView(heading)
        panel.addView(ScrollView(context).apply {
            addView(definition)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(68)).apply {
            topMargin = dp(6)
        })
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(play)
            addView(close)
        })
        close.setOnClickListener { onClose() }
        play.setOnClickListener { onPlay() }
    }

    fun showLoading(word: String, rect: RectF, canPlay: Boolean) {
        anchor = RectF(rect)
        heading.text = word
        definition.text = "Looking up locally…"
        play.visibility = if (canPlay) View.VISIBLE else View.GONE
        visibility = View.VISIBLE
        positionAfterLayout()
        if (canPlay) play.requestFocus() else close.requestFocus()
    }

    fun show(entry: DictionaryEntry) {
        if (!isOpen) return
        heading.text = entry.requested
        definition.text = if (entry.definitions.isEmpty()) {
            "No offline entry for this word."
        } else buildString {
            if (entry.headword != entry.requested.lowercase()) append("${entry.headword}\n")
            entry.definitions.take(5).forEachIndexed { index, item ->
                if (index > 0) append("\n\n")
                append("${index + 1}. ")
                if (item.partOfSpeech.isNotBlank()) append("(${item.partOfSpeech}) ")
                append(item.text)
            }
        }
        positionAfterLayout()
    }

    fun showFailure(message: String) {
        if (!isOpen) return
        definition.text = message
    }

    fun dismiss() { visibility = View.GONE }

    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        when (action) {
            PadAction.Back -> onClose()
            PadAction.Activate -> (findFocus() ?: close).performClick()
            is PadAction.Step -> if (action.direction == Direction.LEFT || action.direction == Direction.UP) {
                if (play.visibility == View.VISIBLE) play.requestFocus() else close.requestFocus()
            } else close.requestFocus()
            else -> Unit
        }
        return true
    }

    private fun positionAfterLayout() = post {
        if (!isOpen) return@post
        val margin = dp(12)
        val width = dp(300).coerceAtMost((this.width - margin * 2).coerceAtLeast(dp(220)))
        val bounds = panel.layoutParams as LayoutParams
        bounds.width = width
        val estimatedHeight = panel.height.takeIf { it > 0 } ?: dp(175)
        bounds.leftMargin = (anchor.centerX().toInt() - width / 2).coerceIn(margin,
            (this.width - width - margin).coerceAtLeast(margin))
        val below = anchor.bottom.toInt() + dp(10)
        bounds.topMargin = if (below + estimatedHeight < height - dp(64)) below.coerceAtLeast(dp(64))
            else (anchor.top.toInt() - estimatedHeight - dp(10)).coerceIn(dp(64),
                (height - estimatedHeight - dp(64)).coerceAtLeast(dp(64)))
        panel.layoutParams = bounds
    }
}
