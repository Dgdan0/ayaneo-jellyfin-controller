package com.pocketds.hub.screens.library

import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
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
    private lateinit var body: LinearLayout
    private lateinit var panel: ChoiceOverlay
    private lateinit var memory: SubtitleMemory
    private var busy = false
    private var state = SubtitleState()
    private var candidates: List<SubtitleCandidate>? = null
    private var selectedLanguage: String? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host; colors = Theme.colors(host.viewContext); memory = SubtitleMemory(host.viewContext, itemId)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18),dp(8),dp(18),dp(10)) }
            column.addView(label(itemTitle,18f))
            status = label("Loading installed subtitles…",12f);column.addView(status)
            body = LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
            column.addView(ScrollView(context).apply { isFocusable=false;addView(body) },LinearLayout.LayoutParams(-1,0,1f))
            addView(column,FrameLayout.LayoutParams(-1,-1))
            panel = ChoiceOverlay(context,colors,ringVisible,sidePanel=true);addView(panel,FrameLayout.LayoutParams(-1,-1))
        }
    }
    override fun onShow() { load() }
    override fun onHide() { scope.coroutineContext.cancelChildren();busy=false;panel.dismiss() }
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
        busy=true;status.text="Loading installed tracks and download history…"
        scope.launch {
            val result=api.subtitles(itemId);busy=false
            when(result) {
                is HubResult.Ok -> {state=result.value.copy(records=memory.merge(result.value.records));candidates=null;render()}
                is HubResult.Failed -> {status.text=result.message;body.removeAllViews();body.addView(button("Try again"){load()})}
            }
        }
    }
    private fun render(focus: String? = null) {
        body.removeAllViews()
        status.text=state.warning.ifEmpty { "Bazarr match score = release compatibility · Your rating = your experience" }
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
            if(state.canDownload) body.addView(button("Search subtitle providers"){search()})
            else body.addView(label("This connection has read-only subtitle access.",13f))
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
        body.post { if(focus==null||body.findViewWithTag<View>(focus)?.requestFocus()!=true) requestInitialFocus();host?.refreshHints() }
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
        busy=true;status.text="Searching configured subtitle providers… This can take a minute."
        scope.launch {
            val result=api.searchSubtitles(itemId);busy=false
            when(result) {is HubResult.Ok -> {candidates=result.value.candidates;selectedLanguage=null;render()};is HubResult.Failed -> status.text=result.message}
        }
    }
    private fun download(candidate: SubtitleCandidate) {
        if(busy) return
        busy=true;status.text="Downloading selected subtitle…"
        scope.launch {
            val result=api.downloadSubtitle(itemId,candidate.ticket);busy=false;candidates=null
            when(result) {is HubResult.Ok -> {host?.notify("Bazarr finished the download request. Refreshing installed tracks…");load()};is HubResult.Failed -> {render();status.text=result.message}}
        }
    }
    private fun flags(forced:Boolean,hi:Boolean) = (if(forced) " · Forced" else "")+(if(hi) " · SDH" else "")
    private fun language(code:String)=Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty{code}
    private fun label(value:String,size:Float)=TextView(checkNotNull(host).viewContext).apply {text=value;textSize=size;setTextColor(colors.primaryText);setPadding(dp(4),dp(5),dp(4),dp(5))}
    private fun button(value:String,maxLines:Int=2,action:()->Unit)=label(value,14f).apply {
        minHeight=dp(46);this.maxLines=maxLines;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(12),dp(8),dp(12),dp(8));background=Styler.chipBackground(context,colors)
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5)}
        Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);activateOnTap {if(!busy) action()}
    }
    private fun dp(n:Int)=Styler.dpInt(checkNotNull(host).viewContext,n.toFloat())
}
