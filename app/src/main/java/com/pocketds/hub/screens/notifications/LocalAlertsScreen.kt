package com.pocketds.hub.screens.notifications

import com.pocketds.hub.ui.FocusScrollView
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.settings.LocalAlert
import com.pocketds.hub.settings.LocalAlerts
import com.pocketds.hub.ui.*

class LocalAlertsScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Downloads & subtitles"
    override fun onHide() = Unit
    override fun onDestroyView() = Unit
    private lateinit var host: ScreenHost
    private lateinit var body: LinearLayout
    private var selected = ""
    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host=host
        body=LinearLayout(host.viewContext).apply {orientation=LinearLayout.VERTICAL;setPadding(Styler.dpInt(context,16f),Styler.dpInt(context,12f),Styler.dpInt(context,16f),Styler.dpInt(context,12f))}
        return FocusScrollView(host.viewContext).apply {addView(body)}
    }
    override fun onShow() {
        body.removeAllViews()
        val colors=Theme.colors(host.viewContext)
        val alerts=LocalAlerts.list(host.viewContext)
        if(alerts.isEmpty()) body.addView(TextView(host.viewContext).apply {text="Completed downloads, subtitle updates and failures will appear here.";textSize=15f;setTextColor(colors.mutedText)})
        alerts.forEach { alert -> body.addView(TextView(host.viewContext).apply {
            tag=alert.id;text="${if(alert.seen) "" else "New · "}${alert.title}\n${alert.message}\n${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(alert.occurredAt))} · Open details"
            textSize=14f;setTextColor(colors.primaryText);setPadding(Styler.dpInt(context,14f),Styler.dpInt(context,12f),Styler.dpInt(context,14f),Styler.dpInt(context,12f))
            background=Styler.cardBackground(context,colors)
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=Styler.dpInt(context,10f)}
            Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false)
            activateOnTap {selected=alert.id;LocalAlerts.markSeen(context,alert.id);openLocalAlert(host,api,alert,ringVisible)}
        }) }
        body.post {requestInitialFocus()}
    }
    override fun requestInitialFocus(): Boolean = (body.findViewWithTag<View>(selected) ?: body.getChildAt(0))?.requestFocus()==true
    override fun hints()=listOf(ButtonHint.activate("Open details"),ButtonHint.back(),ButtonHint("⟳","Refresh",PadAction.Refresh))
    override fun onPad(action: PadAction): Boolean {if(action==PadAction.Refresh){onShow();return true};return false}
}

fun openLocalAlert(host: ScreenHost, api: HubApi, alert: LocalAlert, ringVisible: () -> Boolean) {
    when(alert.destination) {
        "subtitles" -> host.push(com.pocketds.hub.screens.library.SubtitleScreen(api,alert.itemId,alert.title,ringVisible))
        "offline" -> host.push(com.pocketds.hub.screens.offline.OfflineScreen(api,ringVisible,alert.itemId))
        "transfer" -> host.push(com.pocketds.hub.screens.downloads.DownloadsScreen(api,ringVisible,targetTransferId=alert.itemId))
    }
}
