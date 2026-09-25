package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.*
import com.pocketds.hub.screens.discover.ReadingDetailScreen
import com.pocketds.hub.screens.library.AuthorShelvesView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class ReadingPolishIntegrationTest {
    private fun all(v:View):List<View> = listOf(v)+(v as? ViewGroup)?.let{g->(0 until g.childCount).flatMap{all(g.getChildAt(it))}}.orEmpty()
    @Test fun booksHomeWithoutProfileOffersChooserWithoutRenamingTheGreeting() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,method,_->when(method.name){
            "users"->HubResult.Ok(UsersResponse(listOf(JellyfinUser("test","Test reader"))))
            "readingLibraries"->HubResult.Ok(ReadingLibrariesResponse())
            "imageUrl"->""
            else->error(method.name)
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,_->if(method.name=="getViewContext")activity else null} as ScreenHost
        val screen=com.pocketds.hub.screens.home.HomeScreen(api){true}
        lateinit var root:View
        try {
            ins.runOnMainSync{
                com.pocketds.hub.settings.HubSettings.selectUser(activity,"","")
                com.pocketds.hub.settings.ContentModeSettings.set(activity,com.pocketds.hub.state.ContentMode.BOOKS)
                root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()
            };ins.waitForIdleSync()
            ins.runOnMainSync{
                assertTrue(all(root).filterIsInstance<TextView>().any{it.isShown && it.text=="Hello"})
                val choose=all(root).filterIsInstance<TextView>().first{it.isShown && it.text=="Choose profile"}
                assertTrue(choose.isFocusable);choose.performClick()
                assertTrue(all(root).filterIsInstance<TextView>().any{it.isShown && it.text=="Who is watching?"})
                assertTrue(screen.onPad(com.pocketds.hub.input.PadAction.Back))
            }
        }finally{ins.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }
    @Test fun discoverResolvesToTheSameBookActionsAndFormatIndicators() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val work=ReadingWork(id="rw_test",title="A verified book",editions=listOf(
            ReadingEdition(kind="ebook",source="storyteller",sourceItemId="12",availability="available")))
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,method,_->when(method.name){
            "readingResolve"->HubResult.Ok(ReadingResolveResponse(work.id,true))
            "readingWork"->HubResult.Ok(work)
            "imageUrl"->""
            else->error(method.name)
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,_->if(method.name=="getViewContext")activity else null} as ScreenHost
        val screen=ReadingDetailScreen(api,ReadingItem(key="test",title=work.title,source="openlibrary",sourceId="OL1W",inLibrary=true)){true}
        lateinit var root:View
        try {
            ins.runOnMainSync{root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()}
            ins.waitForIdleSync()
            ins.runOnMainSync{
                assertTrue(all(root).any{it.contentDescription=="Ebook, available" && !it.isFocusable && !it.isClickable})
                assertTrue(all(root).any{it.contentDescription=="Audiobook, not available"})
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text=="Read"})
                assertFalse(all(root).filterIsInstance<TextView>().any{it.text=="Ebook ready" || it.text=="Available"})
            }
        }finally{ins.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }
    @Test fun authorRowsKeepIndividualBooksAndExistingContentAfterAFailedSort() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val books=listOf(ReadingWork(id="red",title="Red Rising",authors=listOf("Pierce Brown")),ReadingWork(id="gold",title="Golden Son",authors=listOf("Pierce Brown")))
        val group=ReadingAuthor(id="pierce",name="Pierce Brown",total=2,totalPages=1,items=books)
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,method,args->when(method.name){
            "readingAuthors"-> if(args!![2]=="asc") HubResult.Ok(ReadingAuthorsResponse(authors=listOf(group),total=1,totalPages=1)) else HubResult.Failed(FailureKind.UNKNOWN,"Offline")
            "imageUrl"->""
            else->error(method.name)
        }} as HubApi
        var opened="";var status=""
        lateinit var shelves:AuthorShelvesView
        try {
            ins.runOnMainSync{
                shelves=AuthorShelvesView(activity,api,"storyteller:books",Theme.colors(activity),{true},{message,_->status=message},{},{opened=it.id})
                activity.setContentView(shelves);shelves.show(true)
            };ins.waitForIdleSync()
            ins.runOnMainSync{
                assertTrue(all(shelves).filterIsInstance<TextView>().any{it.text=="Pierce Brown"})
                val card=all(shelves).filterIsInstance<PosterCardView>().first{it.contentDescription.toString().startsWith("Golden Son")}
                card.performClick();assertEquals("gold",opened)
                shelves.show(false)
                assertTrue(status.contains("previous order kept"))
                assertTrue(all(shelves).filterIsInstance<PosterCardView>().any{it.contentDescription.toString().startsWith("Golden Son")})
            }
        }finally{ins.runOnMainSync{shelves.destroy();activity.finish()}}
    }
}
