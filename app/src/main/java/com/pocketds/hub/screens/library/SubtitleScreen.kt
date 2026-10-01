package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.FocusScrollView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
import com.pocketds.hub.offline.OfflineDownloadService
import com.pocketds.hub.offline.OfflineRepository
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*
import java.util.Locale

class SubtitleScreen(private val api: HubApi, private val itemId: String, private val itemTitle: String, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Subtitles"
    override val contentDomain = ContentMode.MEDIA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var status: TextView
    private lateinit var locations: TextView
    private lateinit var body: LinearLayout
    private lateinit var panel: ChoiceOverlay
    private lateinit var memory: SubtitleMemory
    private lateinit var offline: OfflineRepository
    private val work = JobSlot()
    private val busy: Boolean get() = work.isBusy
    private var onlineAvailable = false
    private var state = SubtitleState()
    private var candidates: List<SubtitleCandidate>? = null
    private var selectedLanguage: String? = null
    private var receiverRegistered = false
    private var lastUpdateMessage = ""
    private var awaitingDeviceUpdate = false
    private val changedReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if(candidates==null && ::body.isInitialized) render()
        }
    }

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host; colors = Theme.colors(host.viewContext); memory = SubtitleMemory(host.viewContext, itemId)
        offline = OfflineRepository.get(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(8),dp(18),dp(10)) }
            column.addView(label(itemTitle,18f))
            locations = label("Library · checking subtitles…",13f).apply {
                setPadding(dp(12),dp(9),dp(12),dp(9))
                background=Styler.chipBackground(context,colors)
                layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(7);bottomMargin=dp(7)}
            }
            column.addView(locations)
            status = label("Loading installed subtitles…",12f);column.addView(status)
            body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            column.addView(FocusScrollView(context).apply {addView(body) },LinearLayout.LayoutParams(-1,0,1f))
            addView(column,FrameLayout.LayoutParams(-1,-1))
            panel = ChoiceOverlay(context,colors,ringVisible,sidePanel=true);addView(panel,FrameLayout.LayoutParams(-1,-1))
        }
    }
    override fun onShow() {
        if(!receiverRegistered) {
            ContextCompat.registerReceiver(checkNotNull(host).viewContext,changedReceiver,
                IntentFilter(OfflineRepository.ACTION_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered=true
        }
        load()
    }
    override fun onHide() {
        if(receiverRegistered) {checkNotNull(host).viewContext.unregisterReceiver(changedReceiver);receiverRegistered=false}
        scope.coroutineContext.cancelChildren();panel.dismiss()
    }
    override fun onDestroyView() { scope.cancel();host=null }
    override fun hints() = listOf(ButtonHint.activate(if(busy) "Please wait…" else "Choose"),ButtonHint.back(),ButtonHint("⟳","Refresh",PadAction.Refresh))
    override fun requestInitialFocus(): Boolean = body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() ?: false
    override fun onPad(action: PadAction): Boolean {
        if(panel.onPad(action)) return true
        if(action==PadAction.Refresh) {if(!busy) load();return true}
        if(action==PadAction.Back&&candidates!=null) {candidates=null;render();return true}
        return false
    }
    private fun load() {
        if(busy) return
        status.text="Loading installed tracks and download history…"
        work.launch(scope, onIdle = { host?.refreshHints() }) {
            host?.refreshHints()
            val result=api.subtitles(itemId)
            when(result) {
                is HubResult.Ok -> {onlineAvailable=true;state=result.value.copy(records=memory.merge(result.value.records));candidates=null;render()}
                is HubResult.Failed -> {
                    onlineAvailable=false;candidates=null
                    state=SubtitleState(records=memory.merge(emptyList()),warning=when(result.kind) {
                        FailureKind.NO_NETWORK,FailureKind.TIMEOUT -> "Can't reach the Hub. Downloaded video and subtitles still work; connect and try again."
                        else -> result.message
                    })
                    render()
                }
            }
        }
    }
    private fun render(focus: String? = null) {
        val restore = focus ?: body.findFocus()?.tag as? String
        body.removeAllViews()
        val saved=offline.playbackPlan(itemId,"resume")
        val update=offline.subtitleSyncForItem(itemId)
        if(awaitingDeviceUpdate && update?.retryAt?.let {it<0}==true) {
            lastUpdateMessage="Library updated · copying to this AYANEO needs attention. Your video and saved tracks were kept."
        }
        if(awaitingDeviceUpdate && saved!=null && update==null) {
            lastUpdateMessage="Library updated · subtitles are now available on this AYANEO. Your video was kept."
            awaitingDeviceUpdate=false
        }
        status.text=listOf(lastUpdateMessage,state.warning).filter(String::isNotBlank).joinToString("\n")
            .ifEmpty { "Match score: release compatibility · Your rating: your viewing experience" }
        locations.text=buildList {
            add(if(onlineAvailable) "Library · ${state.records.count { it.installed }} installed subtitle tracks" else "Library · connection unavailable")
            add(when {
                saved==null -> "This AYANEO · video not downloaded"
                update?.retryAt?.let { it<0 }==true -> "This AYANEO · subtitle update needs attention"
                update!=null -> "This AYANEO · subtitle update pending · saved tracks still available"
                else -> "This AYANEO · ${saved.subtitleTracks.size} subtitle tracks available offline"
            })
        }.joinToString("\n")
        val results=candidates
        if(results!=null) {
            body.addView(button("← Installed subtitles and history"){candidates=null;render()})
            val languages=results.map{it.language}.distinct().sorted()
            body.addView(button("Language: ${selectedLanguage?.let(::language) ?: "All"}") {
                panel.show("Filter language","${results.size} search results",listOf(ChoiceOverlay.Choice("","All languages"))+languages.map{ChoiceOverlay.Choice(it,language(it))},onPick={selectedLanguage=it.ifEmpty{null};render()})
            })
            val filtered=results.filter{selectedLanguage==null||it.language==selectedLanguage}
            if(filtered.isEmpty()) body.addView(label("No matching subtitles found for the configured language profile.",14f))
            filtered.forEach { candidate -> body.addView(button("${language(candidate.language)}${flags(candidate.forced,candidate.hi)} · ${candidate.score.toInt()}% match · ${candidate.provider}\n${candidate.release.ifEmpty{"Release details unavailable"}}",maxLines=3){inspect(candidate)}) }
        } else {
            val local=offline.playbackPlan(itemId,"resume")
            if(local!=null) {
                body.addView(label("On this AYANEO · ${local.subtitleTracks.size} subtitle track${if(local.subtitleTracks.size==1) "" else "s"}",16f))
                if(local.subtitleTracks.isEmpty()) body.addView(label("No subtitles saved with this video yet.",13f))
                local.subtitleTracks.forEach { track -> body.addView(label("${language(track.language)}${flags(track.forced,track.hearingImpaired)} · ${if(track.external) "Downloaded" else "Embedded"}",13f)) }
                val pending=offline.subtitleSyncForItem(itemId)
                if(pending!=null) {
                    body.addView(label("Offline update ${if(pending.retryAt<0) "needs attention" else "pending"}${if(pending.error.isNotEmpty()) " · ${pending.error}" else ""}",13f))
                    body.addView(button("Retry offline update now") { queueOfflineSync(pending.expectedLanguage) })
                }
                if(onlineAvailable) {
                    val onlineTracks=state.records.filter { it.installed && !it.embedded }
                    if(onlineTracks.isNotEmpty()) {
                        val expected=onlineTracks.map { it.code.ifBlank { it.language } }.distinct().sorted().joinToString(",")
                        body.addView(button("Prepare online subtitles and refresh this AYANEO") { refreshOnlineItem(expected) })
                    } else if(pending==null) body.addView(label("No online subtitle to copy yet. Search providers below.",13f))
                }
            }
            if(onlineAvailable&&state.canDownload) body.addView(button("Search subtitle providers"){search()})
            else if(onlineAvailable) body.addView(label("This connection has read-only subtitle access.",13f))
            else body.addView(button("Try connecting again"){load()})
            for((heading,records) in listOf("Installed" to state.records.filter{it.installed},"Download history · saved scores" to state.records.filter{!it.installed})) {
                body.addView(label(heading,16f))
                if(records.isEmpty()) body.addView(label(if(heading=="Installed") "No indexed subtitle tracks." else "No recorded subtitle downloads.",13f))
                records.forEach { record ->
                    val rating=memory.rating(record.id)
                    val view=button("${record.language}${flags(record.forced,record.hi)} · ${if(record.embedded) "Embedded" else record.provider.ifEmpty{"External"}}\n${if(record.score.isEmpty()) "Match score unavailable" else "${record.score} match"}${if(rating.isNotEmpty()) " · You: $rating" else " · Not rated"}"){inspect(record)}
                    view.tag=record.id;body.addView(view)
                }
            }
        }
        body.post { if(!panel.isOpen && (restore==null||body.findViewWithTag<View>(restore)?.requestFocus()!=true)) requestInitialFocus();host?.refreshHints() }
    }
    private fun inspect(record: SubtitleRecord) {
        panel.resetBody();panel.open(record.language,if(record.installed) "Installed subtitle" else "Download history · installation not confirmed") {render(record.id)}
        panel.body.addView(label("${record.score.ifEmpty{"No recorded score"}}${if(record.score.isNotEmpty()) " Bazarr match" else ""}\n${record.provider}\n${record.date}\n${record.description}",14f).apply {isFocusable=true})
        panel.body.addView(label("Your rating · saved on this device for this Hub and profile",12f))
        listOf("Good","Out of sync","Wrong translation","").forEach { rating -> panel.choice(rating.ifEmpty{"Clear my rating"},selected=memory.rating(record.id)==rating) {memory.rate(record.id,rating);panel.dismiss();render(record.id)} }
        panel.focusBody();host?.refreshHints()
    }
    private fun inspect(candidate: SubtitleCandidate) {
        panel.resetBody();panel.open("${language(candidate.language)} · ${candidate.score.toInt()}% match",candidate.provider) {host?.refreshHints()}
        panel.body.addView(label("${candidate.release}\n\nMatches: ${candidate.matches.joinToString().ifEmpty{"Not reported"}}\n\nDoesn't match: ${candidate.mismatches.joinToString().ifEmpty{"None reported"}}",13f).apply {isFocusable=true})
        panel.body.addView(label("Downloading may replace an external subtitle with the same language and type. Embedded tracks are kept.",12f))
        panel.choice("Download this subtitle", "${language(candidate.language)}${flags(candidate.forced,candidate.hi)} · ${candidate.provider}") {panel.dismiss();download(candidate)}
        panel.focusBody();host?.refreshHints()
    }
    private fun search() {
        if(busy) return
        status.text="Searching configured subtitle providers… This can take a minute."
        work.launch(scope, onIdle = { host?.refreshHints() }) {
            host?.refreshHints()
            val result=api.searchSubtitles(itemId)
            when(result) {is HubResult.Ok -> {candidates=result.value.candidates;selectedLanguage=null;render()};is HubResult.Failed -> status.text=result.message}
        }
    }
    private fun download(candidate: SubtitleCandidate) {
        if(busy) return
        status.text="Downloading selected subtitle…"
        work.launch(scope, onIdle = { host?.refreshHints() }) {
            host?.refreshHints()
            val result=api.downloadSubtitle(itemId,candidate.ticket);candidates=null
            when(result) {
                is HubResult.Ok -> {
                    val queued=offline.queueSubtitleSync(itemId,candidate.language)
                    awaitingDeviceUpdate=queued
                    if(queued) OfflineDownloadService.start(checkNotNull(host).viewContext)
                    val warning=result.value.warning.ifEmpty { if(!result.value.jellyfinRefreshStarted) "Saved online, but Jellyfin did not accept the subtitle refresh. Try Refresh subtitles later." else "" }
                    lastUpdateMessage=warning.ifEmpty { if(queued) "Library updated · waiting for this AYANEO to finish copying subtitles. Your video stays in place." else "Library updated · refreshing installed tracks." }
                    host?.notify(warning.ifEmpty { if(queued) "Saved online · updating this AYANEO's subtitles" else "Saved online · refreshing installed tracks" })
                    load()
                }
                is HubResult.Failed -> {render();status.text=result.message}
            }
        }
    }
    private fun queueOfflineSync(language: String = "") {
        if(offline.queueSubtitleSync(itemId,language)) {
            awaitingDeviceUpdate=true
            lastUpdateMessage="Copying library subtitles to this AYANEO. Your video stays in place."
            OfflineDownloadService.start(checkNotNull(host).viewContext)
            host?.notify("Updating offline subtitles without downloading the video")
            render()
        }
    }
    private fun refreshOnlineItem(expectedLanguages: String) {
        if(busy) return
        status.text="Asking Jellyfin to refresh this title's subtitles…"
        work.launch(scope, onIdle = { host?.refreshHints() }) {
            host?.refreshHints()
            val result=api.refreshSubtitles(itemId)
            when(result) {
                is HubResult.Ok -> queueOfflineSync(expectedLanguages)
                is HubResult.Failed -> {render();status.text="Could not start Jellyfin's subtitle refresh: ${result.message}"}
            }
        }
    }
    private fun flags(forced:Boolean,hi:Boolean) = (if(forced) " · Forced" else "")+(if(hi) " · SDH" else "")
    private fun language(code:String)=Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty{code}
    private fun label(value:String,size:Float)=TextView(checkNotNull(host).viewContext).apply {text=value;textSize=size;setTextColor(colors.primaryText);setPadding(dp(4),dp(5),dp(4),dp(5))}
    private fun button(value:String,maxLines:Int=2,action:()->Unit)=label(value,14f).apply {
        tag=value;contentDescription=value
        minHeight=dp(46);this.maxLines=maxLines;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(12),dp(8),dp(12),dp(8));background=Styler.chipBackground(context,colors)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)}
        Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);activateOnTap {if(!busy) action()}
    }
    private fun dp(n:Int)=Styler.dpInt(checkNotNull(host).viewContext,n.toFloat())
}
