package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.screens.library.*
import java.lang.reflect.Proxy
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingListsRemovalTest {
    private fun views(v:View):List<View> = listOf(v)+(v as? ViewGroup)?.let {g->(0 until g.childCount).flatMap {views(g.getChildAt(it))}}.orEmpty()
    @Test fun unpromotedYearsAndCrossSeriesIssuesKeepTheirReadingOrder() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var pushed:Screen?=null
        val lists=(1980 downTo 1962).map {ServerReadingList(id=it,title="My Marvelous Year $it",itemCount=23,promoted=false)}
        val entries=listOf(
            ServerReadingListEntry(id=2,order=1,workId="fantasy",sourceItemId="8338",seriesTitle="Amazing Adult Fantasy",title="Issue #7",pageCount=32),
            ServerReadingListEntry(id=1,order=0,workId="four",sourceItemId="8421",seriesTitle="Fantastic Four",title="Issue #1",pageCount=36))
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,m,_->when(m.name){
            "serverReadingLists"->HubResult.Ok(ServerReadingListsResponse(lists))
            "serverReadingList"->HubResult.Ok(ServerReadingListResponse(lists.last(),entries))
            else->error("Unexpected ${m.name}")
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,m,a->when(m.name){"getViewContext"->activity;"push"->{pushed=a!![0] as Screen;null};"back"->true;else->null}} as ScreenHost
        var screen:Screen=ServerReadingListsScreen(api,{true})
        lateinit var root:View
        try {
            ins.runOnMainSync {root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()};ins.waitForIdleSync()
            ins.runOnMainSync {
                val rows=views(root).filter {it.tag?.toString()?.toIntOrNull() in 1962..1980}
                assertEquals(19,rows.size);assertEquals("1962",rows.first().tag);assertEquals("1980",rows.last().tag)
                rows.first().performClick();assertTrue(pushed is ServerReadingListsScreen)
                screen.onHide();screen.onDestroyView();screen=pushed!!;root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()
            };ins.waitForIdleSync()
            ins.runOnMainSync {
                val rows=views(root).filter {it.tag=="1" || it.tag=="2"}
                assertEquals(listOf("1","2"),rows.map {it.tag});rows.first().requestFocus()
                assertTrue(rows.first().contentDescription.contains("Fantastic Four"));rows[1].performClick()
                val reader=pushed as PagedImageReaderScreen
                val field=reader.javaClass.getDeclaredField("initialSourceItemId").apply {isAccessible=true}
                assertEquals("8338",field.get(reader))
                assertEquals("Amazing Adult Fantasy · Issue #7",reader.title)
                reader.onCreateView(host,FrameLayout(activity))
                reader.javaClass.getDeclaredMethod("movePublication",Int::class.javaPrimitiveType).apply {isAccessible=true}.invoke(reader,-1)
                val previous=pushed as PagedImageReaderScreen
                assertEquals("Fantastic Four · Issue #1",previous.title)
                assertEquals(true,previous.javaClass.getDeclaredField("openAtEnd").apply {isAccessible=true}.get(previous))
                reader.onHide();reader.onDestroyView()
            }
            File(activity.getExternalFilesDir(null),"reading-list-issues.png").outputStream().use {ins.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        } finally {ins.runOnMainSync {screen.onHide();screen.onDestroyView();activity.finish()}}
    }

    @Test fun deletionRequiresSeparateConfirmationAndCancelNeverMutates() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var deletes=0;var backs=0
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,m,a->when(m.name){
            "removalPreview"->HubResult.Ok(MediaRemovalPreview("fixture-ticket","Test comic","Permanently delete the listed server files. Offline copies stay available.",listOf("Test comic #1.cbz","Test comic #2.cbz"),2))
            "removeMedia"->{assertEquals("fixture-ticket",a!![0]);deletes++;HubResult.Ok(ActionAck(ok=true))}
            else->error("Unexpected ${m.name}")
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,m,_->when(m.name){"getViewContext"->activity;"back"->{backs++;true};else->null}} as ScreenHost
        val screen=MediaRemovalScreen(api,"reading","fixture",{true})
        lateinit var root:View
        fun click(label:String) {
            var v:View=views(root).filterIsInstance<TextView>().first {it.text.toString()==label}
            while(!v.isClickable)v=v.parent as View
            assertTrue("Click $label",v.performClick())
        }
        try {
            ins.runOnMainSync {root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()};ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(0,deletes);click("Delete from server…");assertEquals(0,deletes)
                click("Keep media");assertEquals(0,deletes);assertEquals(0,backs)
                click("Delete from server…")
            };ins.waitForIdleSync()
            android.os.SystemClock.sleep(250)
            File(activity.getExternalFilesDir(null),"server-delete-confirmation.png").outputStream().use {ins.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            ins.runOnMainSync {click("Delete server files")};ins.waitForIdleSync()
            assertEquals(1,deletes);assertEquals(2,backs)
        } finally {ins.runOnMainSync {screen.onDestroyView();activity.finish()}}
    }
}
