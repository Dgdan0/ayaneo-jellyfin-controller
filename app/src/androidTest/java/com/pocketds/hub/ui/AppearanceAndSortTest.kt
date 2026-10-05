package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.*
import com.pocketds.hub.screens.settings.*
import com.pocketds.hub.screens.library.*
import com.pocketds.hub.model.*
import com.pocketds.hub.settings.*
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppearanceAndSortTest {
    private fun all(view:View):List<View> = listOf(view)+(view as? ViewGroup)?.let{g->(0 until g.childCount).flatMap{all(g.getChildAt(it))}}.orEmpty()
    /**
     * Settings in each look (#11): the same places and colour choices in both;
     * Glass has no theme to choose, since it is always dark, and Classic keeps
     * its light and dark.
     */
    @Test fun settingsShowsItsSectionsAndColoursApplyPerMediaType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldLook = LookSettings.get(context)
        val oldTheme = ThemeSettings.getMode(context)
        val oldUser = HubSettings.userId(context) to HubSettings.userName(context)
        try {
            for (look in listOf(Look.GLASS, Look.CLASSIC)) settingsIn(look)
        } finally {
            LookSettings.set(context, oldLook)
            ThemeSettings.setMode(context, oldTheme)
            HubSettings.selectUser(context, oldUser.first, oldUser.second)
        }
    }

    private fun settingsIn(look: Look) {
        val ins=InstrumentationRegistry.getInstrumentation()
        LookSettings.set(ins.targetContext, look)
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root:FrameLayout
        lateinit var current:Screen
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,args->when(method.name){
            "getViewContext"->activity
            "push"->{current=(args!![0] as Screen);root.removeAllViews();root.addView(current.onCreateView(hostRef!!,root));current.onShow();null}
            "refreshAppearance"->{Theme.refresh(activity,root);null}
            else->null
        }} as ScreenHost
        hostRef=host
        try {
            ins.runOnMainSync {
                HubSettings.selectUser(activity,UUID.randomUUID().toString(),"Appearance test")
                ContentModeSettings.set(activity,ContentMode.MEDIA)
                root=FrameLayout(activity);activity.setContentView(root)
                current=SettingsScreen(null){true};root.addView(current.onCreateView(host,root));current.onShow()
                val labels=all(root).filterIsInstance<TextView>().map{it.text.toString()}
                assertTrue(labels.containsAll(listOf("Appearance","Home","Playback","Subtitles","Downloads","More","Look","Movies and TV","Books")))
                // Glass is always dark: the theme is Classic's alone.
                assertEquals("A theme to choose in $look", look == Look.CLASSIC, labels.contains("Theme"))
                fun swatch(mode:String,id:String)=all(root.findViewWithTag<View>("palette:$mode")).first{it.tag==id}
                swatch("media","sky").performClick()
                swatch("books","rose").performClick()
                assertEquals(AccentPreset.SKY,DomainPreferences.accent(activity,ContentMode.MEDIA))
                assertEquals(AccentPreset.ROSE,DomainPreferences.accent(activity,ContentMode.BOOKS))
                // Settings changes colours; it never switches the app between Media and Books.
                assertEquals(ContentMode.MEDIA,ContentModeSettings.get(activity))
                all(root).first{it.contentDescription=="Subtitles"}.performClick()
                assertTrue(all(root).any{it is androidx.media3.ui.SubtitleView})
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text=="Look"})
                all(root).first{it.contentDescription=="Appearance"}.performClick()
                fun shot(name: String) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(activity,850f),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Styler.dpInt(activity,410f),View.MeasureSpec.EXACTLY))
                    root.layout(0,0,root.measuredWidth,root.measuredHeight)
                    val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(activity.getExternalFilesDir(null),"polish-appearance-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }
                if (look == Look.CLASSIC) for(mode in listOf("LIGHT","DARK")) {
                    (root.findViewWithTag<View>("theme") as BlobSegmentedView).optionView(mode)!!.performClick()
                    shot(mode.lowercase())
                } else {
                    assertNull("No theme on Glass", root.findViewWithTag<View>("theme"))
                    shot("glass")
                }
            }
        } finally {ins.runOnMainSync{current.onHide();current.onDestroyView();activity.finish()};hostRef=null}
    }
    @Test fun changingAccentRepaintsRetainedControlsWithoutReplacingThem() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val oldUser=HubSettings.userId(ins.targetContext) to HubSettings.userName(ins.targetContext)
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {ins.runOnMainSync {
            HubSettings.selectUser(activity,UUID.randomUUID().toString(),"Theme test")
            ContentModeSettings.set(activity,ContentMode.MEDIA)
            val colors=Theme.colors(activity)
            val root=LinearLayout(activity)
            val label=TextView(activity).apply{text="Progress";setTextColor(colors.accent);isFocusableInTouchMode=true}
            val bar=SeekBar(activity).apply{progress=37;progressTintList=android.content.res.ColorStateList.valueOf(colors.accent)}
            root.addView(label);root.addView(bar);activity.setContentView(root);label.requestFocus()
            DomainPreferences.setAccent(activity,ContentMode.MEDIA,AccentPreset.LILAC)
            Theme.refresh(activity,root)
            assertSame(label,root.getChildAt(0));assertTrue(label.hasFocus());assertEquals(37,bar.progress)
            assertEquals(Theme.preview(activity,ContentMode.MEDIA).accent,label.currentTextColor)
            assertEquals(label.currentTextColor,bar.progressTintList!!.defaultColor)
            assertEquals(label.currentTextColor,colors.accent)
        }}finally{ins.runOnMainSync{activity.finish()};HubSettings.selectUser(ins.targetContext,oldUser.first,oldUser.second)}
    }
    @Test fun rememberedSortIsIndependentByProfile() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val oldUser=HubSettings.userId(ins.targetContext) to HubSettings.userName(ins.targetContext)
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {ins.runOnMainSync {
            val user=UUID.randomUUID().toString();HubSettings.selectUser(activity,user,"Sort test")
            DomainPreferences.setSort(activity,ContentMode.BOOKS,SortPreference("author",false))
            assertEquals(SortPreference("title",true),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title"),"title"))
            assertEquals(SortPreference("author",false),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title","author"),"title"))
            assertEquals(SortPreference("name",true),DomainPreferences.sort(activity,ContentMode.MEDIA,listOf("name","added"),"name"))
            HubSettings.selectUser(activity,"other-$user","Other")
            assertEquals(SortPreference("series",true),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title","series","author"),"series"))
        }}finally{ins.runOnMainSync{activity.finish()};HubSettings.selectUser(ins.targetContext,oldUser.first,oldUser.second)}
    }
    /**
     * A service's logo follows the look (#11): Classic swaps its light and dark
     * assets in place; Glass is always dark, so it keeps the dark one whatever
     * the theme says.
     */
    @Test fun serviceLogoChangesWithAppearanceWithoutReplacingItsView() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val context=ins.targetContext
        val oldLook=LookSettings.get(context)
        val oldTheme=ThemeSettings.getMode(context)
        fun pixels(logo:ImageView):Int {
            val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
            logo.drawable.setBounds(0,0,64,64);logo.drawable.draw(android.graphics.Canvas(bitmap))
            val values=IntArray(4096);bitmap.getPixels(values,0,64,0,0,64,64);bitmap.recycle();return values.contentHashCode()
        }
        var dark=0
        try {
            LookSettings.set(context,Look.CLASSIC)
            val activity=ins.startActivitySync(Intent(context,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {ins.runOnMainSync {
                val root=FrameLayout(activity)
                val logo=ImageView(activity);root.addView(logo);activity.setContentView(root)
                ThemeSettings.setMode(activity,ThemeSettings.Mode.LIGHT);Theme.refresh(activity,root)
                ServiceLogo.bind(logo,com.pocketds.hub.R.drawable.logo_sonarr)
                val light=pixels(logo)
                ThemeSettings.setMode(activity,ThemeSettings.Mode.DARK);Theme.refresh(activity,root)
                dark=pixels(logo);assertNotEquals(light,dark);assertSame(logo,root.getChildAt(0))
                ThemeSettings.setMode(activity,ThemeSettings.Mode.LIGHT);Theme.refresh(activity,root)
                assertEquals(light,pixels(logo));assertNull(logo.imageTintList)
            }}finally{ins.runOnMainSync{activity.finish()}}
            // Glass: the dark asset, though the theme still says light.
            LookSettings.set(context,Look.GLASS)
            val glass=ins.startActivitySync(Intent(context,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {ins.runOnMainSync {
                val root=FrameLayout(glass)
                val logo=ImageView(glass);root.addView(logo);glass.setContentView(root)
                ThemeSettings.setMode(glass,ThemeSettings.Mode.LIGHT);Theme.refresh(glass,root)
                ServiceLogo.bind(logo,com.pocketds.hub.R.drawable.logo_sonarr)
                assertEquals("Glass is always dark",dark,pixels(logo));assertNull(logo.imageTintList)
            }}finally{ins.runOnMainSync{glass.finish()}}
        } finally {
            LookSettings.set(context,oldLook)
            ThemeSettings.setMode(context,oldTheme)
        }
    }
    private var hostRef:ScreenHost?=null
}
