package com.pocketds.hub.screens.downloads

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.BandwidthChange
import com.pocketds.hub.model.BandwidthState
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.math.roundToLong

class BandwidthScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Bandwidth"
    override val contentDomain = ContentMode.MEDIA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var panel: ChoiceOverlay
    private var busy = false
    private var state: BandwidthState? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            val column = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12)) }
            status = label("Loading qBittorrent settings…", 13f)
            column.addView(status)
            body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            column.addView(ScrollView(context).apply { isFocusable=false;addView(body) }, LinearLayout.LayoutParams(-1,0,1f))
            addView(column,FrameLayout.LayoutParams(-1,-1))
            panel=ChoiceOverlay(context,colors,ringVisible,sidePanel=true)
            addView(panel,FrameLayout.LayoutParams(-1,-1))
        }
    }
    override fun onShow() { load() }
    override fun onHide() { scope.coroutineContext.cancelChildren();busy=false;panel.dismiss() }
    override fun onDestroyView() { scope.cancel();host=null }
    override fun hints()=listOf(ButtonHint.activate(if(busy) "Saving…" else "Choose"),ButtonHint.back(),ButtonHint("⟳","Refresh",PadAction.Refresh))
    override fun requestInitialFocus():Boolean=body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() ?: false
    override fun onPad(action:PadAction):Boolean {
        if(panel.onPad(action)) return true
        if(action==PadAction.Refresh) { if(!busy) load();return true }
        return false
    }
    private fun load() {
        if(busy) return
        busy=true;status.text="Loading qBittorrent settings…"
        scope.launch {
            val response=api.bandwidth();busy=false
            when(response) {
                is HubResult.Ok -> render(response.value)
                is HubResult.Failed -> {status.text=response.message;body.removeAllViews();body.addView(button("Try again"){load()})}
            }
        }
    }
    private fun render(value:BandwidthState) {
        state=value;body.removeAllViews()
        status.text="Global qBittorrent limits · ${if(value.mode=="alternative") "Alternative mode active" else "Normal mode active"}"
        body.addView(label("Normal · ↓ ${rate(value.downloadBps)}  ↑ ${rate(value.uploadBps)}",16f))
        body.addView(label("Alternative (quiet) · ↓ ${rate(value.alternativeDownloadBps)}  ↑ ${rate(value.alternativeUploadBps)}",16f))
        if(value.schedulerEnabled) body.addView(label("qBittorrent's schedule is enabled and may change the active mode.",13f))
        body.addView(label(if(value.queueingEnabled) "Queue priority is available in each transfer's actions." else "Torrent queueing is disabled in qBittorrent. Priority controls are unavailable.",13f))
        if(!value.canControl) body.addView(label("This connection has read-only access to bandwidth settings.",13f))
        else {
            if(value.modeSwitchSupported) {
                body.addView(button("Use normal limits${if(value.mode=="normal") " · active" else ""}"){save(BandwidthChange(mode="normal"))})
                body.addView(button("Use alternative limits${if(value.mode=="alternative") " · active" else ""}"){save(BandwidthChange(mode="alternative"))})
            } else body.addView(label("Switching modes here requires qBittorrent 5 or later.",13f))
            body.addView(button("Edit normal limits"){edit("normal",value.downloadBps,value.uploadBps)})
            body.addView(button("Edit alternative limits"){edit("alternative",value.alternativeDownloadBps,value.alternativeUploadBps)})
        }
        body.post { requestInitialFocus();host?.refreshHints() }
    }
    private fun edit(mode:String,down:Long,up:Long) {
        if(busy) return
        panel.resetBody();panel.open("${mode.replaceFirstChar { it.uppercase() }} limits","Applies to all qBittorrent transfers. 0 means unlimited."){host?.refreshHints()}
        fun field(title:String,initial:Long):EditText {
            panel.body.addView(label("$title (KiB/s)",13f))
            return EditText(checkNotNull(host).viewContext).apply {
                inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale.US,"%.6f",initial/1024.0).trimEnd('0').trimEnd('.'))
                setTextColor(colors.primaryText);textSize=16f;minHeight=dp(48);contentDescription="$title limit in KiB per second"
                Styler.makeFocusable(this);panel.body.addView(this)
            }
        }
        val download=field("Download",down);val upload=field("Upload",up)
        val error=label("",12f);error.setTextColor(colors.dangerText);panel.body.addView(error)
        panel.choice("Apply limits","Keeps the current normal/alternative mode") {
            fun parse(field:EditText):Long?=field.text.toString().toDoubleOrNull()?.takeIf { it.isFinite()&&it>=0&&it<=1048576 }?.let {(it*1024).roundToLong()}
            val d=parse(download);val u=parse(upload)
            if(d==null||u==null) error.text="Enter a value from 0 to 1,048,576 KiB/s."
            else {panel.dismiss();save(BandwidthChange(limitsFor=mode,downloadBps=d,uploadBps=u))}
        }
        panel.focusBody(download);host?.refreshHints()
    }
    private fun save(change:BandwidthChange) {
        if(busy) return
        busy=true;status.text="Applying and verifying settings…";host?.refreshHints()
        scope.launch {
            val result=api.setBandwidth(change);busy=false
            when(result) {
                is HubResult.Ok -> {render(result.value);host?.notify("Bandwidth settings verified")}
                is HubResult.Failed -> {status.text=result.message;host?.notify(result.message)}
            }
            host?.refreshHints()
        }
    }
    private fun rate(value:Long)=if(value<=0) "Unlimited" else String.format(Locale.US,"%,.1f KiB/s",value/1024.0)
    private fun label(value:String,size:Float)=TextView(checkNotNull(host).viewContext).apply {text=value;textSize=size;setTextColor(colors.primaryText);setPadding(dp(4),dp(5),dp(4),dp(5))}
    private fun button(value:String,action:()->Unit)=label(value,14f).apply {
        minHeight=dp(44);gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(8));background=Styler.chipBackground(context,colors)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)}
        Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);activateOnTap { if(!busy) action() }
    }
    private fun dp(n:Int)=Styler.dpInt(checkNotNull(host).viewContext,n.toFloat())
}
