package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.*
import com.pocketds.hub.screens.library.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingCollectionTest {
    @Test fun missingBookCanBeFocusedAndOpenedWithoutStartingAcquisition() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val work=ReadingWork(id="collection",entityType="collection",title="A collection",sections=listOf(ReadingSection(title="Books",items=listOf(ReadingSectionItem(title="The missing volume",number="2",availability="missing")))))
        var opened:Any?=null;var searchRequests=0
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,m,_-> when(m.name){"readingWork"->HubResult.Ok(work);"imageUrl"->"";"readingSearch"->{searchRequests++;HubResult.Ok(ReadingSearchResponse())};else->error("Unexpected request ${m.name}")}} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,m,a->when(m.name){"getViewContext"->activity;"push"->{opened=a!![0];null};else->null}} as ScreenHost
        val screen=ReadingWorkScreen(api,"collection","A collection", ringVisible = { true })
        lateinit var root:View
        fun all(v:View):List<View> = listOf(v)+(v as? ViewGroup)?.let{g->(0 until g.childCount).flatMap{all(g.getChildAt(it))}}.orEmpty()
        try {
            i.runOnMainSync {root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()}
            i.waitForIdleSync()
            i.runOnMainSync {
                val missing=all(root).filterIsInstance<DetailArtworkCardView>().single()
                assertTrue(missing.requestFocus());missing.performClick()
                assertTrue(opened is MissingReadingItemScreen);assertEquals(0,searchRequests)
            }
        } finally {i.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }
}
