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
    @Test fun settingsOpensAllSettingsAndAppearancePreviewDoesNotNavigate() {
        val ins=InstrumentationRegistry.getInstrumentation()
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
                current=SettingsScreen{true};root.addView(current.onCreateView(host,root));current.onShow()
                val labels=all(root).filterIsInstance<TextView>().map{it.text.toString()}
                assertTrue(labels.containsAll(listOf("Appearance","Notifications","Playback","Offline downloads","Controller test")))
                all(root).filterIsInstance<UtilityRowView>().first { it.contentDescription.toString().startsWith("Appearance") }.performClick()
                assertTrue(current is AppearanceScreen)
                root.findViewWithTag<View>("media:blue").performClick()
                root.findViewWithTag<View>("books:rose").performClick()
                assertEquals(AccentPreset.BLUE,DomainPreferences.accent(activity,ContentMode.MEDIA))
                assertEquals(AccentPreset.ROSE,DomainPreferences.accent(activity,ContentMode.BOOKS))
                all(root).first {it.contentDescription=="Show books"}.performClick()
                assertEquals(ContentMode.MEDIA,ContentModeSettings.get(activity))
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text=="Continue reading"})
                assertEquals(3,all(root).count{it.contentDescription?.toString()?.let{d->d.startsWith("Ebook,") || d.startsWith("Audiobook,") || d.startsWith("Read along,")}==true})
                assertFalse(all(root).filterIsInstance<TextView>().any{it.text=="Available" || it.text=="Not available"})
                for(mode in listOf("LIGHT","DARK")) {
                    root.findViewWithTag<View>("mode:$mode").performClick()
                    root.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(activity,850f),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Styler.dpInt(activity,410f),View.MeasureSpec.EXACTLY))
                    root.layout(0,0,root.measuredWidth,root.measuredHeight)
                    val bitmap=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    java.io.File(activity.getExternalFilesDir(null),"polish-appearance-${mode.lowercase()}.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    bitmap.recycle()
                }

            }
        } finally {ins.runOnMainSync{current.onHide();current.onDestroyView();activity.finish()};hostRef=null}
    }
    @Test fun changingAccentRepaintsRetainedControlsWithoutReplacingThem() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {ins.runOnMainSync {
            HubSettings.selectUser(activity,UUID.randomUUID().toString(),"Theme test")
            ContentModeSettings.set(activity,ContentMode.MEDIA)
            val colors=Theme.colors(activity)
            val root=LinearLayout(activity)
            val label=TextView(activity).apply{text="Progress";setTextColor(colors.accent);isFocusableInTouchMode=true}
            val bar=SeekBar(activity).apply{progress=37;progressTintList=android.content.res.ColorStateList.valueOf(colors.accent)}
            root.addView(label);root.addView(bar);activity.setContentView(root);label.requestFocus()
            DomainPreferences.setAccent(activity,ContentMode.MEDIA,AccentPreset.VIOLET)
            Theme.refresh(activity,root)
            assertSame(label,root.getChildAt(0));assertTrue(label.hasFocus());assertEquals(37,bar.progress)
            assertEquals(Theme.preview(activity,ContentMode.MEDIA).accent,label.currentTextColor)
            assertEquals(label.currentTextColor,bar.progressTintList!!.defaultColor)
            assertEquals(label.currentTextColor,colors.accent)
        }}finally{ins.runOnMainSync{activity.finish()}}
    }
    @Test fun rememberedSortIsIndependentByProfileAndPanelAppliesWithoutClosing() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {ins.runOnMainSync {
            val user=UUID.randomUUID().toString();HubSettings.selectUser(activity,user,"Sort test")
            DomainPreferences.setSort(activity,ContentMode.BOOKS,SortPreference("author",false))
            assertEquals(SortPreference("title",true),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title"),"title"))
            assertEquals(SortPreference("author",false),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title","author"),"title"))
            assertEquals(SortPreference("name",true),DomainPreferences.sort(activity,ContentMode.MEDIA,listOf("name","added"),"name"))
            HubSettings.selectUser(activity,"other-$user","Other")
            assertEquals(SortPreference("series",true),DomainPreferences.sort(activity,ContentMode.BOOKS,listOf("title","series","author"),"series"))
            val colors=Theme.colors(activity);val root=FrameLayout(activity);activity.setContentView(root)
            val opener=LibrarySortPanel.control(activity,colors){};root.addView(opener)
            val panel=SidePanelView(activity,colors,{true});root.addView(panel)
            var changes=0
            LibrarySortPanel.show(panel,opener,listOf("name" to "Name","added" to "Date added"),SortPreference("name",true),{changes++},{})
            all(panel).first{it.contentDescription?.toString()?.startsWith("Date added")==true}.performClick()
            assertEquals(1,changes);assertTrue(panel.isOpen)
            all(panel).first{it.contentDescription?.toString()?.startsWith("Date added")==true}.performClick()
            assertEquals(1,changes)
            panel.cancel();assertTrue(opener.hasFocus())
        }}finally{ins.runOnMainSync{activity.finish()}}
    }
    @Test fun serviceLogoChangesWithAppearanceWithoutReplacingItsView() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {ins.runOnMainSync {
            val root=FrameLayout(activity)
            val logo=ImageView(activity);root.addView(logo);activity.setContentView(root)
            ThemeSettings.setMode(activity,ThemeSettings.Mode.LIGHT);Theme.refresh(activity,root)
            ServiceLogo.bind(logo,com.pocketds.hub.R.drawable.logo_sonarr)
            fun pixels():Int {
                val bitmap=android.graphics.Bitmap.createBitmap(64,64,android.graphics.Bitmap.Config.ARGB_8888)
                logo.drawable.setBounds(0,0,64,64);logo.drawable.draw(android.graphics.Canvas(bitmap))
                val values=IntArray(4096);bitmap.getPixels(values,0,64,0,0,64,64);bitmap.recycle();return values.contentHashCode()
            }
            val light=pixels()
            ThemeSettings.setMode(activity,ThemeSettings.Mode.DARK);Theme.refresh(activity,root)
            val dark=pixels();assertNotEquals(light,dark);assertSame(logo,root.getChildAt(0))
            ThemeSettings.setMode(activity,ThemeSettings.Mode.LIGHT);Theme.refresh(activity,root)
            assertEquals(light,pixels());assertNull(logo.imageTintList)
        }}finally{ins.runOnMainSync{activity.finish()}}
    }
    private var hostRef:ScreenHost?=null
}
