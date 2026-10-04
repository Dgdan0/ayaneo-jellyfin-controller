package com.pocketds.hub.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.screens.library.FormatReadiness
import com.pocketds.hub.screens.library.ReadingFormatStatus

/** Information, never actions. Each format is one accessibility group, not a pad stop. */
class ReadingFormatStatusView(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    private val glass = Theme.onGlass(colors)
    init { orientation=HORIZONTAL; isFocusable=false; clipChildren=false }
    fun bind(values: List<ReadingFormatStatus>) {
        removeAllViews()
        visibility=if(values.isEmpty()) GONE else VISIBLE
        if (glass) return values.forEach(::glassChip)
        values.forEach { format ->
            val color=if(format.readiness==FormatReadiness.READY) colors.accent else colors.mutedText
            val group=LinearLayout(context).apply {
                orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(40)
                isFocusable=false; isClickable=false; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription="${format.label}, ${format.readiness.description}"
            }
            group.addView(ImageView(context).apply {
                setImageDrawable(AppIconDrawable(when(format.kind){"audiobook"->AppIcon.HEADPHONES;"readaloud"->AppIcon.READ_ALONG;else->AppIcon.BOOK},color))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LayoutParams(dp(24),dp(24)))
            group.addView(TextView(context).apply { text=format.label; textSize=12f; setTextColor(color)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart=dp(8) })
            if(format.readiness==FormatReadiness.PENDING) group.addView(TextView(context).apply {
                text=" ·"; setTextColor(color); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            addView(group,LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd=dp(22) })
        }
    }
    /**
     * Glass: the prototype's format chip (`.fmt`), a pill of the page's glass
     * with the format's icon and name; one that is not there is dimmed, and
     * one on its way says so with a dot.
     */
    private fun glassChip(format: ReadingFormatStatus) {
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, 999f))
            setPadding(dp(8), dp(4), dp(9), dp(4))
            alpha = if (format.readiness == FormatReadiness.READY) 1f else DIM
            isFocusable = false; isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "${format.label}, ${format.readiness.description}"
            addView(ImageView(context).apply {
                setImageDrawable(AppIconDrawable(when (format.kind) { "audiobook" -> AppIcon.HEADPHONES; "readaloud" -> AppIcon.READ_ALONG; else -> AppIcon.BOOK },
                    android.graphics.Color.WHITE))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(13), dp(13)))
            addView(TextView(context).apply {
                text = format.label + if (format.readiness == FormatReadiness.PENDING) " ·" else ""
                textSize = 11f; textWeight(700); setTextColor(android.graphics.Color.WHITE)
                includeFontPadding = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Wrap the three complete groups vertically on narrow screens / larger text sizes.
        orientation=HORIZONTAL
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        val required=(0 until childCount).sumOf { getChildAt(it).measuredWidth + (getChildAt(it).layoutParams as LayoutParams).marginEnd }
        if(required>MeasureSpec.getSize(widthMeasureSpec) && MeasureSpec.getMode(widthMeasureSpec)!=MeasureSpec.UNSPECIFIED) {
            orientation=VERTICAL; super.onMeasure(widthMeasureSpec,heightMeasureSpec)
        }
    }
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())

    private companion object {
        /** A format that is not there: the prototype's 42%. */
        const val DIM = .42f
    }
}
