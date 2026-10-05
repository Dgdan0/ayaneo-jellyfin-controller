package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.FocusScrollView
import android.view.*
import android.widget.*
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.reader.PagedImageReaderScreen
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*

/**
 * Kavita's ordered server lists are kept separate from editable device-only shelves.
 * On Glass each list or issue is a glass row (the prototype's `.lrow`) with a chevron.
 */
class ServerReadingListsScreen(private val api: HubApi, private val ring: () -> Boolean, private val selected: ServerReadingList? = null) : Screen {
    override val title = selected?.title ?: "Reading lists"
    override val contentDomain = com.pocketds.hub.state.ContentMode.BOOKS
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var colors: PocketColors
    private var job: Job? = null
    private var focusId: String? = null
    private val rows = mutableListOf<View>()
    private var glass = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host=host; colors=Theme.colors(host.viewContext); glass=Theme.onGlass(colors)
        return LinearLayout(host.viewContext).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(colors.background)
            status=TextView(context).apply {
                textSize=if(glass) 12f else 13f
                setTextColor(if(glass) com.pocketds.hub.ui.glass.GlassColors.QUIET else colors.mutedText)
                setPadding(dp(22),dp(10),dp(22),dp(8))
            }
            addView(status)
            val scroll=FocusScrollView(context).apply { clipToPadding=false }
            body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(if(glass) 22 else 20),dp(5),dp(if(glass) 22 else 20),dp(24)); clipChildren=false }
            scroll.addView(body); addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        }
    }
    override fun onShow() { load() }
    override fun onHide() { job?.cancel() }
    override fun onDestroyView() { scope.cancel() }
    override fun hints() = listOf(ButtonHint.activate(if(selected==null) "Open list" else "Read issue"),ButtonHint.back(),ButtonHint("⟳","Refresh",PadAction.Refresh))
    override fun onPad(action: PadAction): Boolean = if(action==PadAction.Refresh) { load();true } else false
    override fun requestInitialFocus(): Boolean = (rows.firstOrNull { it.tag==focusId } ?: rows.firstOrNull())?.requestFocus() ?: false
    private fun load() {
        job?.cancel(); status.text="Loading reading lists…"
        job=scope.launch {
            if(selected==null) when(val result=api.serverReadingLists()) {
                is HubResult.Ok -> {
                    body.removeAllViews();rows.clear()
                    val lists=result.value.lists.sortedBy { it.title.lowercase() }
                    status.text="${lists.size} Kavita lists · title order"
                    lists.forEach { list -> row(list.id.toString(),list.title,"${list.itemCount} issues") { host.push(ServerReadingListsScreen(api,ring,list)) } }
                    if(lists.isEmpty()) status.text="No reading lists are available in Kavita."
                    requestInitialFocus()
                }
                is HubResult.Failed -> failure(result)
            } else when(val result=api.serverReadingList(selected.id)) {
                is HubResult.Ok -> {
                    body.removeAllViews();rows.clear()
                    val entries=result.value.items.sortedBy { it.order }
                    status.text="${entries.size} issues · Kavita reading order"
                    entries.forEachIndexed { index,entry ->
                        row(entry.id.toString(),"${index+1}. ${entry.seriesTitle} · ${entry.title}",
                            listOfNotNull(entry.volume.takeIf { it.isNotBlank() },"${entry.pageCount} pages",entry.progress?.let { if(it.completed) "Read" else "${com.pocketds.hub.state.Fmt.readingPercentLabel(it.percentage)} read" }).joinToString(" · ")) {
                            host.push(PagedImageReaderScreen(api,entry.workId,entry.sourceItemId,"${entry.seriesTitle} · ${entry.title}",ring, readingList = entries, readingListIndex = index))
                        }
                    }
                    if(entries.isEmpty()) status.text="This reading list is empty."
                    requestInitialFocus()
                }
                is HubResult.Failed -> failure(result)
            }
        }
    }
    private fun failure(result: HubResult.Failed) {
        status.text=result.message; body.removeAllViews(); rows.clear()
        row("retry","Try again","Reload from Kavita") { load() };requestInitialFocus()
    }
    private fun row(id: String, title: String, detail: String, action: () -> Unit) {
        if (glass) return glassRow(id, title, detail, action)
        val view=LinearLayout(host.viewContext).apply {
            tag=id; orientation=LinearLayout.VERTICAL; minimumHeight=dp(72); setPadding(dp(16),dp(12),dp(16),dp(12))
            background=Styler.chipBackground(context,colors); contentDescription="$title, $detail"
            addView(TextView(context).apply { text=title; textSize=17f; setTextColor(colors.primaryText) })
            addView(TextView(context).apply { text=detail; textSize=12f; setTextColor(colors.mutedText);setPadding(0,dp(5),0,0) })
            Styler.makeFocusable(this); FocusDecorator.attach(this,ring,scale=false)
            FocusDecorator.listen(this,ring) { v,focused -> if(focused)focusId=id }
            activateOnTap(action)
        }
        body.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(10)});rows+=view
    }

    /** Glass: the prototype's list row, a 13dp glass row with the words and a chevron, ringed on focus. */
    private fun glassRow(id: String, title: String, detail: String, action: () -> Unit) {
        val view=LinearLayout(host.viewContext).apply {
            tag=id; orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, Styler.dp(context, 13f))
            foreground=Styler.focusOutline(context, colors, 13f, 3f)
            setPadding(dp(14),dp(10),dp(12),dp(10)); contentDescription="$title, $detail"
            addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                addView(TextView(context).apply { text=title; textSize=12.5f; textWeight(700); setTextColor(colors.primaryText); maxLines=2 })
                addView(TextView(context).apply { text=detail; textSize=11f; setTextColor(com.pocketds.hub.ui.glass.GlassColors.QUIET); setPadding(0,dp(3),0,0) })
            }, LinearLayout.LayoutParams(0,-2,1f))
            addView(ImageView(context).apply {
                setImageDrawable(AppIconDrawable(AppIcon.NEXT, android.graphics.Color.WHITE)); alpha=.5f
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18),dp(18)))
            Styler.makeFocusable(this); FocusDecorator.attach(this,ring,scale=false)
            FocusDecorator.listen(this,ring) { v,focused -> if(focused)focusId=id }
            activateOnTap(action)
        }
        body.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(7)});rows+=view
    }
    private fun dp(n: Int)=Styler.dpInt(host.viewContext,n.toFloat())
}
