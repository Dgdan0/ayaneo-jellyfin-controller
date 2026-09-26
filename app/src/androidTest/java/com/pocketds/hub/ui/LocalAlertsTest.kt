package com.pocketds.hub.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.settings.*
import com.pocketds.hub.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAlertsTest {
    @Test fun transferAlertOpensTheExactFinishedTransferEvenFromBooks() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(android.content.Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val before=ContentModeSettings.get(activity)
        ContentModeSettings.set(activity,com.pocketds.hub.state.ContentMode.BOOKS)
        var requestedFinished=false
        var pushed:com.pocketds.hub.nav.Screen?=null
        val api=java.lang.reflect.Proxy.newProxyInstance(com.pocketds.hub.net.HubApi::class.java.classLoader,arrayOf(com.pocketds.hub.net.HubApi::class.java)) {_,method,args ->
            when(method.name) {
                "activity" -> {requestedFinished=args!![0] as Boolean;com.pocketds.hub.net.HubResult.Ok(ActivityResponse(items=listOf(
                    ActivityItem(id="other",title="Same title",diagnosis=ActivityDiagnosis(title="Wrong transfer")),
                    ActivityItem(id="target",title="Same title",stage=Stages.DONE,diagnosis=ActivityDiagnosis(title="Exact transfer diagnosis"))
                )))}
                else -> error("Unexpected ${method.name}")
            }
        } as com.pocketds.hub.net.HubApi
        val host=java.lang.reflect.Proxy.newProxyInstance(com.pocketds.hub.nav.ScreenHost::class.java.classLoader,arrayOf(com.pocketds.hub.nav.ScreenHost::class.java)) {_,method,args ->
            when(method.name) {"getViewContext" -> activity;"push" -> {pushed=args!![0] as com.pocketds.hub.nav.Screen;null};else -> null}
        } as com.pocketds.hub.nav.ScreenHost
        fun text(view:android.view.View):List<String> = (if(view is android.widget.TextView) listOf(view.text.toString()) else emptyList()) +
            if(view is android.view.ViewGroup) (0 until view.childCount).flatMap {text(view.getChildAt(it))} else emptyList()
        lateinit var root:android.view.View
        try {
            ins.runOnMainSync {
                com.pocketds.hub.screens.notifications.openLocalAlert(host,api,LocalAlert("transfer:target:ready","target","Same title","Complete","transfer")){true}
                root=checkNotNull(pushed).onCreateView(host,android.widget.FrameLayout(activity))
                activity.setContentView(root);pushed!!.onShow()
            }
            ins.waitForIdleSync();android.os.SystemClock.sleep(250);ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(requestedFinished)
                assertTrue(text(root).any {it.contains("Exact transfer diagnosis")})
                assertEquals(com.pocketds.hub.state.ContentMode.BOOKS,ContentModeSettings.get(activity))
            }
        } finally {
            ins.runOnMainSync {pushed?.onHide();pushed?.onDestroyView();activity.finish()}
            ContentModeSettings.set(ins.targetContext,before)
        }
    }

    @Test fun deduplicatesReadEventsSeparatesProfilesAndReplacesResolvedFailures() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val beforeUser=HubSettings.userId(context);val beforeName=HubSettings.userName(context)
        val enabled=NotificationSettings.alertsEnabled(context)
        val user="alert-fixture-${System.nanoTime()}"
        val scopes=mutableListOf<String>()
        try {
            NotificationSettings.setAlertsEnabled(context,false)
            HubSettings.selectUser(context,user,"Fixture")
            scopes+=LocalAlerts.scope(context)
            val failure=LocalAlert("subtitle:item:failed","item","Example","Needs attention","subtitles")
            LocalAlerts.publish(context,failure);LocalAlerts.publish(context,failure)
            assertEquals(1,LocalAlerts.list(context).size)
            LocalAlerts.markSeen(context,failure.id);LocalAlerts.publish(context,failure)
            assertEquals(0,LocalAlerts.unread(context))
            LocalAlerts.publish(context,failure.copy(id="subtitle:item:ready",message="Available offline"))
            assertEquals(listOf("subtitle:item:ready"),LocalAlerts.list(context).map {it.id})
            assertEquals(1,LocalAlerts.unread(context))
            LocalAlerts.markSeen(context,"subtitle:item:ready")
            LocalAlerts.publish(context,failure.copy(id="subtitle:item:ready",eventKey="second-update"))
            assertEquals("A new subtitle update is a new event",1,LocalAlerts.unread(context))
            HubSettings.selectUser(context,"$user-other","Other")
            scopes+=LocalAlerts.scope(context)
            assertTrue(LocalAlerts.list(context).isEmpty())
            HubSettings.selectUser(context,user,"Fixture")
            assertEquals("subtitles",LocalAlerts.list(context).single().destination)
            val transfer=ActivityItem(id="exact-transfer",title="Server film",stage=Stages.DOWNLOADING)
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(transfer)))
            assertEquals(1,LocalAlerts.list(context).size) // No flood from initial history.
            val failed=transfer.copy(stage=Stages.STUCK,diagnosis=ActivityDiagnosis(code="disk",title="Disk full",needsAttention=true))
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(failed)))
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(failed)))
            assertEquals(2,LocalAlerts.list(context).size)
            assertEquals("exact-transfer",LocalAlerts.list(context).first().itemId)
            assertEquals("transfer",LocalAlerts.list(context).first().destination)
            TransferAlertObserver.observe(context,ActivityResponse()) // Partial/empty response cannot replay failures.
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(failed)))
            assertEquals(2,LocalAlerts.list(context).size)
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(transfer.copy(stage=Stages.SEEDING,progress=1.0))))
            assertEquals("transfer:exact-transfer:ready",LocalAlerts.list(context).first().id)
            assertEquals(2,LocalAlerts.list(context).size)
            LocalAlerts.markSeen(context,"transfer:exact-transfer:ready")
            TransferAlertObserver.observe(context,ActivityResponse(items=listOf(transfer.copy(stage=Stages.DONE))))
            assertTrue(LocalAlerts.list(context).first {it.id=="transfer:exact-transfer:ready"}.seen)
        } finally {
            scopes.forEach {Prefs.of(context).edit().remove("local_alerts_$it").remove("transfer_alert_baseline_$it").commit()}
            HubSettings.selectUser(context,beforeUser,beforeName)
            NotificationSettings.setAlertsEnabled(context,enabled)
        }
    }
}
