package com.pocketds.hub.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.reader.EpubAppearancePanel
import com.pocketds.hub.reader.EpubReaderPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Deterministic native baselines: synthetic art, no account, network, player or download writes. */
@RunWith(AndroidJUnit4::class)
class VisualFixtureTest {
    @Test fun captureThemesRailWidthsAndLargeText() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            for(dark in listOf(false,true)) for(width in listOf(663,785)) {
                val context=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply{uiMode=if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO;fontScale=1f})
                val colors=Theme.colors(context)
                val page=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.background)}
                val header=DetailHeaderView(context,colors){true}.apply {
                    compact=true;titleView.text="The Meridian Collection";subtitleView.visibility=View.GONE
                    metadataView.text="Alex Morgan · 3 available · 1 missing";overview.bind("Four lives intersect as a city searches for its vanished past. A fixture synopsis with enough words to exercise the bounded description.")
                    setPresentation("book",false,true);poster.setImageDrawable(art(0xff386273.toInt(),0xff111a29.toInt()))
                    continuation.addView(ContinuationCardView(context,colors,{true},portrait=true).apply{bind("Continue reading","A City of Glass · Book 2 · 38% read",.38,false);image.setImageDrawable(art(0xff80472d.toInt(),0xff251915.toInt()))})
                }
                page.addView(header)
                page.addView(TextView(context).apply{text="Books";textSize=18f;setTextColor(colors.primaryText);setPadding(dp(context,24),dp(context,8),0,0)})
                val row=LinearLayout(context).apply{setPadding(dp(context,24),dp(context,10),dp(context,24),dp(context,10))}
                repeat(4){index->row.addView(DetailArtworkCardView(context,colors,{true}).apply {
                    artworkHeight(120);titleView.text=listOf("The Last Horizon","A City of Glass","A Distant Shore","The Returning Light")[index];titleView.minLines=2
                    subtitleView.text=if(index==3)"Book 4 · Missing" else "Book ${index+1}"
                    image.setImageDrawable(art(listOf(0xff386273,0xff80472d,0xff52644a,0xff756f63)[index].toInt(),0xff171921.toInt()));available(index!=3)
                },LinearLayout.LayoutParams(dp(context,88),-2).apply{marginEnd=dp(context,14)})}
                page.addView(row)
                capture(activity,ScrollView(context).apply{addView(page)},"collection-${if(dark)"dark" else "light"}-$width",width)
                val lastCaption=(row.getChildAt(3) as DetailArtworkCardView).subtitleView
                val bounds=android.graphics.Rect().also{lastCaption.getDrawingRect(it);page.offsetDescendantRectToMyCoords(lastCaption,it)}
                assertTrue("Book captions should fit the first viewport: ${bounds.bottom}",bounds.bottom<=dp(context,408))
            }
            for(dark in listOf(false,true)) {
                val context=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply{uiMode=if(dark)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO;fontScale=1f})
                val colors=Theme.colors(context)
                val root=FrameLayout(context).apply {setBackgroundColor(colors.background)}
                root.addView(TextView(context).apply{text="Chapter Seven\n\nThe journey continued through the silent valley.\n\nOpening this panel keeps the page in place.";textSize=20f;setTextColor(colors.primaryText);setPadding(dp(context,36),dp(context,42),dp(context,350),0)},FrameLayout.LayoutParams(-1,-1))
                val panel=EpubAppearancePanel(context,colors){true};root.addView(panel,FrameLayout.LayoutParams(-1,-1))
                activity.setContentView(root);panel.show(EpubReaderPreferences(),{},{})
                panel.getChildAt(0).animate().cancel();panel.getChildAt(0).alpha=1f
                capture(activity,root,"appearance-${if(dark)"dark" else "light"}",853)
            }
            val context=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {fontScale=1.5f;uiMode=Configuration.UI_MODE_NIGHT_YES})
            val colors=Theme.colors(context)
            val utilities=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.background);setPadding(dp(context,24),dp(context,16),dp(context,24),dp(context,16))}
            listOf("Appearance" to "Follow system","Notifications" to "Sonarr 60 · Radarr 40 · Bazarr 40","Offline downloads" to "Wi-Fi only · while charging · keep 10240 MB free").forEach {(label,detail)->utilities.addView(UtilityRowView(context,colors,label,detail,AppIcon.SETTINGS))}
            capture(activity,utilities,"utilities-large-type",663)
        }} finally {i.runOnMainSync{activity.finish()}}
    }
    private fun capture(activity:android.app.Activity,root:View,name:String,widthDp:Int) {
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(root.context,widthDp),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dp(root.context,408),View.MeasureSpec.EXACTLY))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
        val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val directory=File(activity.cacheDir,"visual-baselines").apply{mkdirs()}
        File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun art(a:Int,b:Int)=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(a,b))
    private fun dp(context:Context,n:Int)=Styler.dpInt(context,n.toFloat())
}
