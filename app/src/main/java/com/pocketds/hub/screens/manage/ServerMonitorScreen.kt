package com.pocketds.hub.screens.manage

import com.pocketds.hub.ui.FocusScrollView
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.pocketds.hub.state.Fmt

class ServerMonitorScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Server monitor"
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var host:ScreenHost?=null
    private lateinit var colors:PocketColors
    private lateinit var body:LinearLayout
    private lateinit var status:TextView
    private lateinit var refresh:TextView
    private val work = JobSlot()
    private val busy: Boolean get() = work.isBusy
    override fun onCreateView(host:ScreenHost,container:ViewGroup):View {
        this.host=host;colors=Theme.colors(host.viewContext)
        return LinearLayout(host.viewContext).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8));setBackgroundColor(colors.background)
            val header=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
            status=label("Loading host, disks and sessions…",12f);header.addView(status,LinearLayout.LayoutParams(0,-2,1f))
            refresh=label("Refresh",14f).apply {minHeight=dp(44);gravity=android.view.Gravity.CENTER;setPadding(dp(14),0,dp(14),0);background=Styler.chipBackground(context,colors);Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);activateOnTap{load()}}
            header.addView(refresh);addView(header)
            body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
            addView(FocusScrollView(context).apply {addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        }
    }
    override fun onShow() {load();scope.launch {while(isActive){delay(15000);load()}}}
    override fun onHide() {scope.coroutineContext.cancelChildren()}
    override fun onDestroyView() {scope.cancel();host=null}
    override fun requestInitialFocus()=refresh.requestFocus()
    override fun hints()=listOf(ButtonHint.activate("Refresh"),ButtonHint.back(),ButtonHint("⟳","Refresh",PadAction.Refresh))
    override fun onPad(action:PadAction):Boolean {if(action==PadAction.Refresh){load();return true};return false}
    private fun load() {
        if(busy)return
        work.launch(scope) {
            val result=api.serverMonitor()
            when(result){is HubResult.Ok->render(result.value);is HubResult.Failed->{status.text="Refresh failed · ${result.message} · Previous values may be stale";status.setTextColor(colors.dangerText)}}
        }
    }
    private fun render(value:ServerMonitor) {
        val selected=body.findFocus()?.tag as? String
        body.removeAllViews();status.setTextColor(colors.mutedText)
        val time=runCatching{DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value.checkedAt))}.getOrDefault("")
        status.text="${value.host.os} host · Checked $time · Refreshes every 15 seconds"
        val metrics=LinearLayout(checkNotNull(host).viewContext).apply {orientation=LinearLayout.HORIZONTAL}
        val total=value.host.memoryTotalBytes;val used=(total-value.host.memoryAvailableBytes).coerceAtLeast(0)
        listOf(
            card("cpu","CPU",value.host.cpuPercent?.let{String.format(Locale.US,"%.1f%% · short sample",it)}?:"Unavailable",value.host.cpuPercent),
            card("memory","Memory",if(total>0) "${Fmt.bytes(used)} / ${Fmt.bytes(total)}" else "Unavailable",if(total>0)100.0*used/total else null),
            card("uptime","Host uptime",if(value.host.uptimeSeconds>0) "${value.host.uptimeSeconds/86400} days ${(value.host.uptimeSeconds%86400)/3600} hours" else "Unavailable")
        ).forEach {metrics.addView(it,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(6)})}
        body.addView(metrics)
        value.host.warnings.forEach{body.addView(label(it,12f))}
        val columns=LinearLayout(checkNotNull(host).viewContext).apply {orientation=LinearLayout.HORIZONTAL}
        val disks=LinearLayout(checkNotNull(host).viewContext).apply{orientation=LinearLayout.VERTICAL}
        disks.addView(label("Disk space available to Hub",16f))
        if(value.host.disks.isEmpty())disks.addView(label("No fixed-disk statistics available.",13f))
        value.host.disks.forEach {disk->
            val free=if(disk.totalBytes>0)100.0*disk.availableBytes/disk.totalBytes else null
            val low=com.pocketds.hub.screens.downloads.ActivityDashboard.lowSpace(disk)
            disks.addView(card("disk:${disk.name}",disk.name,"${Fmt.bytes(disk.availableBytes)} free of ${Fmt.bytes(disk.totalBytes)}${if(low) " · Low space (<10%)" else ""}",free?.let{100-it},low))
        }
        disks.addView(label("Playing · selected Jellyfin profile",16f))
        if(value.sessionWarning.isNotEmpty())disks.addView(label(value.sessionWarning,13f))
        else if(value.sessions.isEmpty())disks.addView(label("Nothing playing for this profile.",13f))
        value.sessions.forEachIndexed {i,item->disks.addView(card("session:$i",item.title,"${item.device} · ${item.client}\n${if(item.paused) "Paused" else "Playing"} · ${item.method.ifEmpty{"Method unavailable"}}"))}
        columns.addView(disks,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(12)})
        val containers=LinearLayout(checkNotNull(host).viewContext).apply{orientation=LinearLayout.VERTICAL}
        containers.addView(label("Docker containers",16f))
        if(value.dockerWarning.isNotEmpty())containers.addView(label(value.dockerWarning,13f))
        else if(value.containers.isEmpty())containers.addView(label("No containers reported by Docker.",13f))
        value.containers.forEach{containers.addView(card("container:${it.name}",it.name,"${it.state} · ${it.status}\n${it.image}",warning=it.state in setOf("dead","restarting")||it.status.contains("unhealthy")))}
        columns.addView(containers,LinearLayout.LayoutParams(0,-2,1f));body.addView(columns)
        if(selected!=null)body.post{body.findViewWithTag<View>(selected)?.requestFocus()?:refresh.requestFocus()}
    }
    private fun card(id:String,title:String,detail:String,percentage:Double?=null,warning:Boolean=false)=LinearLayout(checkNotNull(host).viewContext).apply {
        orientation=LinearLayout.VERTICAL;tag=id;setPadding(dp(10),dp(8),dp(10),dp(8));background=Styler.chipBackground(context,colors)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)}
        addView(label(title,15f));addView(label(detail,12f).apply{if(warning)setTextColor(colors.dangerText)})
        percentage?.let {p->addView(ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply{max=1000;progress=(p*10).toInt().coerceIn(0,1000);progressTintList=android.content.res.ColorStateList.valueOf(if(warning)colors.dangerText else colors.accent)},LinearLayout.LayoutParams(-1,dp(6)).apply{topMargin=dp(6)})}
        Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);contentDescription="$title, $detail"
    }
    private fun label(value:String,size:Float)=TextView(checkNotNull(host).viewContext).apply{text=value;textSize=size;setTextColor(colors.primaryText);setPadding(0,dp(4),0,dp(4))}
    private fun dp(n:Int)=Styler.dpInt(checkNotNull(host).viewContext,n.toFloat())
}
