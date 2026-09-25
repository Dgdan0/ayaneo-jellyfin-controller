package com.pocketds.hub.reader

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.ui.*

/** Every adjustment applies and saves immediately; closing returns to the same book position. */
class EpubAppearancePanel(context:Context,colors:PocketColors,private val ring:()->Boolean):SidePanelView(context,colors,ring) {
    private var value=EpubReaderPreferences()
    private var changed:(EpubReaderPreferences)->Unit={}
    private var section="text"
    private var moreTypography=false
    fun show(initial:EpubReaderPreferences,onChanged:(EpubReaderPreferences)->Unit,onClose:()->Unit) {
        value=initial;changed=onChanged
        open("Reading appearance", "Changes save automatically", onDismiss=onClose);render()
    }
    private fun update(next:EpubReaderPreferences,rebuild:Boolean=false) {
        value=next;changed(value);if(rebuild)render()
    }
    private fun render() {
        val focusedLabel=findFocus()?.contentDescription
        resetBody();tabs(listOf("text" to "Text","page" to "Page","theme" to "Theme"),section){section=it;render()}
        if(section=="theme") {
            val swatches=LinearLayout(context)
            listOf(Triple(EpubTheme.LIGHT,"Light",0xfffaf7f1.toInt()),Triple(EpubTheme.SEPIA,"Sepia",0xffeadbc0.toInt()),Triple(EpubTheme.DARK,"Dark",0xff202126.toInt())).forEach {(theme,label,fill)->
                swatches.addView(TextView(context).apply {
                    text="Aa";textSize=20f;gravity=Gravity.CENTER;typeface=Typeface.SERIF
                    contentDescription="$label page colour";isSelected=value.theme==theme
                    setTextColor(SemanticColor.foreground(fill));background=ThemeGradientDrawable().apply{cornerRadius=dp(10).toFloat();setColor(fill);setStroke(dp(if(isSelected)3 else 1),if(isSelected)this@EpubAppearancePanel.colors.accent else this@EpubAppearancePanel.colors.mutedText)}
                    Styler.makeFocusable(this);FocusDecorator.attach(this,ring,scale=false)
                    activateOnTap{update(value.copy(theme=theme),true)}
                },LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(3),dp(4),dp(3),dp(12))})
            }
            body.addView(swatches)
            choice("Use system colours",selected=value.theme==EpubTheme.SYSTEM){update(value.copy(theme=EpubTheme.SYSTEM),true)}
        } else if(section=="text") {
            body.addView(ValueAdjusterView(context,colors,"Text size",ValueRange(.7f,2f,.1f),value.fontScale,{"${(it*100).toInt()}%"}) {update(value.copy(fontScale=it))})
            listOf("publisher" to "Publisher","serif" to "Serif","sans-serif" to "Sans serif").forEach {(id,label)->
                val row=choice(label,selected=value.fontFamily==id){update(value.copy(fontFamily=id),true)} as LinearLayout
                val copy=row.getChildAt(0) as LinearLayout
                (copy.getChildAt(0) as TextView).typeface=if(id=="serif")Typeface.SERIF else Typeface.SANS_SERIF
            }
            body.addView(ValueAdjusterView(context,colors,"Line spacing",ValueRange(1f,2f,.05f),value.lineHeight,{"${(it*100).toInt()}%"}){update(value.copy(lineHeight=it))})
            choice(if(moreTypography) "Less typography" else "More typography") { moreTypography=!moreTypography;render() }
            if(moreTypography) {
                choice("Publisher styling",if(value.publisherStyles)"On" else "Off",value.publisherStyles){update(value.copy(publisherStyles=!value.publisherStyles),true)}
                choice("Justified text",if(value.textAlignment=="justify")"On" else "Off",value.textAlignment=="justify"){update(value.copy(textAlignment=if(value.textAlignment=="justify")"start" else "justify"),true)}
            }
        } else {
            body.addView(ValueAdjusterView(context,colors,"Page margins",ValueRange(.5f,2f,.1f),value.pageMargins,{"${(it*100).toInt()}%"}){update(value.copy(pageMargins=it))})
            EpubColumns.entries.forEach {col->choice(when(col){EpubColumns.AUTO->"Automatic columns";EpubColumns.ONE->"One column";EpubColumns.TWO->"Two columns"},selected=value.columns==col){update(EpubLayoutPolicy.selectColumns(value,col),true)}}
            choice("Continuous scrolling",if(value.scroll)"On" else "Off",value.scroll){update(EpubLayoutPolicy.selectScroll(value,!value.scroll),true)}
        }
        focusBody(focusedLabel?.let { label -> getFocusables(FOCUS_FORWARD).firstOrNull { it.contentDescription==label } })
    }
}
