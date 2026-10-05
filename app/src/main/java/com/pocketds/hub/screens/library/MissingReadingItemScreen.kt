package com.pocketds.hub.screens.library

import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.Artwork
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingType
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.screens.discover.ReadingDetailScreen
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.ui.showStatus

/**
 * Missing volumes remain inspectable; acquisition still goes through the explicit request flow.
 * It is a book page: the cover (dimmed, it is not here) beside the words.
 */
class MissingReadingItemScreen(private val api:HubApi,private val item:ReadingSectionItem,private val ring:()->Boolean):Screen {
    override val title=item.title
    override val showsOwnTitle = true
    override val pageArtwork: String? get() = item.artwork.takeIf(String::isNotBlank)
    private lateinit var host:ScreenHost
    private lateinit var header:DetailHeaderView
    private lateinit var button:TextView
    private lateinit var status:TextView
    private lateinit var choices:ChoiceOverlay
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var searchJob:Job?=null
    override fun onCreateView(host:ScreenHost,container:ViewGroup):View {
        this.host=host;val context=host.viewContext;val colors=Theme.colors(context)
        val root=FrameLayout(context).apply {setBackgroundColor(colors.background)}
        header=DetailHeaderView(context,colors,ring,glass=true).apply {
            book = true
            squareCover = item.kind == ReadingType.AUDIOBOOK
            eyebrowView.text = listOfNotNull(item.number.takeIf(String::isNotBlank)?.let { "Book $it" }, "Not in your library").joinToString(" · ")
            poster.alpha = .42f
            titleView.text=item.title;subtitleView.text=item.authors.joinToString(", ");subtitleView.visibility=View.VISIBLE
            metadataView.text=listOfNotNull(item.number.takeIf(String::isNotBlank)?.let{"Book $it"},"Missing from library").joinToString(" · ")
            overview.bind("This volume is part of the collection but is not available to read. Search for an edition to see its details and available request options.")
            bindArtwork("book",null,item.artwork.takeIf(String::isNotBlank)?.let(api::imageUrl),Artwork.loader(api, context))
        }
        // The Books side's main action, gold (PillButton.mainFace).
        button=PillButton.create(context,colors,"Find this book",AppIcon.SEARCH,primary=true,heightDp=31f,glass=true,
            side=com.pocketds.hub.state.ContentMode.BOOKS)
            .apply { FocusDecorator.attach(this,ring,scale=false);activateOnTap(::search) }
        header.actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = -Styler.dpInt(context, PillButton.RING_DP)
        })
        status=TextView(context).apply{textSize=13f;setTextColor(com.pocketds.hub.ui.glass.GlassColors.QUIET)}
        header.continuation.addView(status)
        root.addView(FocusScrollView(context).apply{addView(header)},FrameLayout.LayoutParams(-1,-1))
        choices=ChoiceOverlay(context,colors,ring,sidePanel=true);root.addView(choices,FrameLayout.LayoutParams(-1,-1))
        return root
    }
    override fun onShow() = Unit
    override fun requestInitialFocus()=button.requestFocus()
    override fun onHide(){searchJob?.cancel();searchJob=null;choices.dismiss();button.isEnabled=true;status.text=""}
    override fun onDestroyView(){scope.cancel()}
    override fun hints()=listOf(ButtonHint.activate(if(choices.isOpen)"View edition" else "Find this book"),ButtonHint.back())
    override fun onPad(action:PadAction):Boolean {
        if(choices.onPad(action)||header.overview.onPad(action))return true
        if(action==PadAction.Refresh){search();return true};return false
    }
    private fun search() {
        if(searchJob?.isActive==true)return
        status.text="Searching editions…";button.isEnabled=false
        searchJob=scope.launch {
            val query=listOf(item.title,item.authors.firstOrNull().orEmpty()).filter(String::isNotBlank).joinToString(" ")
            when(val result=api.readingSearch(query,ReadingType.EBOOK)) {
                is HubResult.Failed->status.showStatus(StatusText.failed(result.message,result.kind,hasData=false),Theme.colors(status.context))
                is HubResult.Ok->{
                    val results=result.value.results.ifEmpty { result.value.broaderResults }
                    status.text=if(results.isEmpty())"No matching editions found" else ""
                    if(results.isNotEmpty()) choices.show("Choose an edition","Check the title and author before requesting.",results.mapIndexed {index,found->ChoiceOverlay.Choice(index.toString(),found.title,found.subtitle)}) { id->
                        results.getOrNull(id.toIntOrNull() ?: -1)?.let {host.push(ReadingDetailScreen(api,it,ring))}
                    }
                }
            }
            button.isEnabled=true;searchJob=null
        }
    }
}
