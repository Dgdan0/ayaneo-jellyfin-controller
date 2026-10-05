package com.pocketds.hub.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.reader.EpubAppearancePanel
import com.pocketds.hub.reader.EpubReaderPreferences
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Deterministic native baselines: synthetic art, no account, network, player or download writes. */
@RunWith(AndroidJUnit4::class)
class VisualFixtureTest {
    /**
     * The reading appearance panel over a page, and the utility rows in large
     * type. The look is one dark one, so each is captured once.
     */
    @Test fun captureAppearanceAndLargeText() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            val context=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply{fontScale=1f})
            val colors=Theme.colors(context)
            val root=FrameLayout(context).apply {setBackgroundColor(colors.background)}
            root.addView(TextView(context).apply{text="Chapter Seven\n\nThe journey continued through the silent valley.\n\nOpening this panel keeps the page in place.";textSize=20f;setTextColor(colors.primaryText);setPadding(dp(context,36),dp(context,42),dp(context,350),0)},FrameLayout.LayoutParams(-1,-1))
            val panel=EpubAppearancePanel(context,colors){true};root.addView(panel,FrameLayout.LayoutParams(-1,-1))
            activity.setContentView(root);panel.show(EpubReaderPreferences(),{},{})
            panel.getChildAt(0).animate().cancel();panel.getChildAt(0).alpha=1f
            capture(activity,root,"appearance",853)
            val large=activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {fontScale=1.5f})
            val largeColors=Theme.colors(large)
            val utilities=LinearLayout(large).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(largeColors.background);setPadding(dp(large,24),dp(large,16),dp(large,24),dp(large,16))}
            listOf("Appearance" to "Teal · Gold","Notifications" to "Sonarr 60 · Radarr 40 · Bazarr 40","Offline downloads" to "Wi-Fi only · while charging · keep 10240 MB free").forEach {(label,detail)->utilities.addView(UtilityRowView(large,largeColors,label,detail,AppIcon.SETTINGS))}
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
    private fun dp(context:Context,n:Int)=Styler.dpInt(context,n.toFloat())
}
