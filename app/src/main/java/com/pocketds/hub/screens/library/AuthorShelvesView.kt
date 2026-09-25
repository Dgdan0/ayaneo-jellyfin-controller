package com.pocketds.hub.screens.library

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.model.*
import com.pocketds.hub.net.*
import com.pocketds.hub.reader.ReadingCompletionRepository
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*

/** Vertically recycled author rows, with independent, stable horizontal book paging. */
class AuthorShelvesView(context:Context,private val api:HubApi,private val libraryId:String,
    private val colors:PocketColors,private val ringVisible:()->Boolean,
    private val onStatus:(String,Boolean)->Unit,private val onReady:()->Unit,
    private val onOpen:(ReadingWork)->Unit) : RecyclerView(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val groups=mutableListOf<ReadingAuthor>()
    private val jobs=mutableMapOf<String,Job>()
    private val errors=mutableMapOf<String,String>()
    private val rowPositions=mutableMapOf<String,Int>()
    private val loader=(api as? HubClient)?.imageLoader ?: ImageLoader(context)
    private var generation=0
    private var direction=""
    private var requestedDirection="asc"
    private var page=0
    private var totalPages=0
    private var selectedAuthor=""
    private var selectedWork=""
    private var visible=false
    private val rows=Rows()
    init {
        layoutManager=LinearLayoutManager(context);adapter=rows
        clipToPadding=false;clipChildren=false;setPadding(dp(18),dp(8),dp(18),dp(16))
        addOnScrollListener(object:OnScrollListener(){override fun onScrolled(view:RecyclerView,dx:Int,dy:Int){
            if((layoutManager as LinearLayoutManager).findLastVisibleItemPosition()>=groups.size-2 && page<totalPages && direction==requestedDirection) load(page+1)
        }})
    }
    fun show(ascending:Boolean,force:Boolean=false) {
        val requested=if(ascending)"asc" else "desc"
        if(force || requested!=requestedDirection){generation++;jobs.values.forEach(Job::cancel);jobs.clear()}
        visible=true;requestedDirection=requested
        if(force || page==0 || direction!=requestedDirection) load(1) else {onReady();rows.notifyDataSetChanged()}
    }
    fun hide(){visible=false;generation++;jobs.values.forEach(Job::cancel);jobs.clear()}
    fun destroy(){hide();scope.cancel()}
    fun restoreFocus():Boolean {
        val index=groups.indexOfFirst { it.id==selectedAuthor }.takeIf { it>=0 } ?: 0
        if(groups.isEmpty())return false
        scrollToPosition(index)
        post { (findViewHolderForAdapterPosition(index) as? RowHolder)?.focusBook(selectedWork) }
        return true
    }
    private fun load(next:Int) {
        if(!visible || jobs["root"]?.isActive==true)return
        val token=++generation
        jobs.values.forEach(Job::cancel);jobs.clear()
        val requested=requestedDirection
        onStatus(if(groups.isEmpty())"Loading authors…" else "Loading author shelves…",false)
        jobs["root"]=scope.launch {
            when(val result=api.readingAuthors(libraryId,next,requested)) {
                is HubResult.Ok -> if(visible && token==generation) {
                    if(next==1){groups.clear();rowPositions.clear()}
                    result.value.authors.forEach { if(groups.none { prior -> prior.id==it.id })groups.add(it) }
                    page=next;totalPages=result.value.totalPages;direction=requested
                    errors.remove("root");rows.notifyDataSetChanged();onReady()
                    onStatus(if(groups.isEmpty())"No authors in this library" else "${result.value.total} authors${if(result.value.cache.stale) " · cached" else ""}",false)
                }
                is HubResult.Failed -> if(visible && token==generation) {
                    errors["root"]=result.message;onStatus("${result.message} · previous order kept · Refresh retries",true);rows.notifyDataSetChanged()
                }
            }
            if(token==generation)jobs.remove("root")
        }
    }
    private fun loadAuthor(id:String) {
        if(!visible || jobs[id]?.isActive==true)return
        val index=groups.indexOfFirst { it.id==id };val group=groups.getOrNull(index) ?: return
        if(group.page>=group.totalPages)return
        val token=generation
        jobs[id]=scope.launch {
            when(val result=api.readingAuthors(libraryId,group.page+1,direction,id)) {
                is HubResult.Ok -> if(visible && token==generation) {
                    result.value.authors.firstOrNull()?.let { next ->
                        groups[index]=next.copy(items=(group.items+next.items).distinctBy(ReadingWork::id))
                        errors.remove(id);rows.notifyItemChanged(index)
                    }
                }
                is HubResult.Failed -> if(visible && token==generation) {
                    errors[id]=result.message;rows.notifyItemChanged(index)
                }
            }
            if(token==generation)jobs.remove(id)
        }
    }
    private inner class Rows:Adapter<RowHolder>() {
        override fun getItemCount()=groups.size+if(page<totalPages || errors.containsKey("root"))1 else 0
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=RowHolder(LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL;clipChildren=false;setPadding(dp(4),dp(8),dp(4),dp(14));layoutParams=LayoutParams(-1,-2)
        })
        override fun onBindViewHolder(holder:RowHolder,position:Int)=holder.bind(groups.getOrNull(position))
    }
    private inner class RowHolder(private val column:LinearLayout):ViewHolder(column) {
        private var current:ReadingAuthor?=null
        private var books:RecyclerView?=null
        fun focusBook(id:String) {
            val group=current ?: return
            val target=group.items.indexOfFirst { it.id==id }.takeIf{it>=0} ?: (rowPositions[group.id] ?: 0)
            books?.scrollToPosition(target);books?.post{books?.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()}
        }
        fun bind(group:ReadingAuthor?) {
            val focused=column.hasFocus()
            current=group;column.removeAllViews()
            if(group==null) {
                column.addView(more(if(errors.containsKey("root"))"Retry authors" else "More authors") {load(if(page==0)1 else page+1)})
                return
            }
            val heading=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(6),0,dp(6),dp(8))}
            val avatar=FrameLayout(context).apply {background=ThemeGradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(this@AuthorShelvesView.colors.focusFill)};clipToOutline=true}
            avatar.addView(TextView(context).apply {text=group.name.split(' ').filter(String::isNotBlank).take(2).map{it.first()}.joinToString("").uppercase();textSize=17f;gravity=Gravity.CENTER;setTextColor(colors.accent)},FrameLayout.LayoutParams(-1,-1))
            if(group.artwork.isNotBlank())avatar.addView(ImageView(context).apply {
                scaleType=ImageView.ScaleType.CENTER_CROP
                loader.enqueue(ImageRequest.Builder(context).data(api.imageUrl(group.artwork)).target(this).build())
            },FrameLayout.LayoutParams(-1,-1))
            heading.addView(avatar,LinearLayout.LayoutParams(dp(44),dp(44)))
            val copy=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0)}
            copy.addView(TextView(context).apply{text=group.name;textSize=18f;setTypeface(typeface,Typeface.BOLD);setTextColor(colors.primaryText)})
            copy.addView(TextView(context).apply{text="${group.total} books";textSize=11f;setTextColor(colors.mutedText)})
            heading.addView(copy);column.addView(heading)
            val strip=RecyclerView(context).apply {
                layoutManager=LinearLayoutManager(context,HORIZONTAL,false)
                clipToPadding=false;clipChildren=false;setPadding(dp(4),dp(4),dp(4),dp(8))
                adapter=Books(group)
                (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(rowPositions[group.id] ?: 0,0)
            }
            books=strip;column.addView(strip,LinearLayout.LayoutParams(-1,dp(260)))
            if(focused)focusBook(selectedWork)
        }
    }
    private inner class Books(private val group:ReadingAuthor):Adapter<BookHolder>() {
        override fun getItemCount()=group.items.size+if(group.page<group.totalPages)1 else 0
        override fun getItemViewType(position:Int)=if(position<group.items.size)0 else 1
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):BookHolder {
            val view=if(type==0) PosterCardView(context,colors,184f).apply {FocusDecorator.attach(this,ringVisible,scale=false)}
                else more("More books") {loadAuthor(group.id)}
            view.layoutParams=LayoutParams(dp(130),ViewGroup.LayoutParams.WRAP_CONTENT).apply {setMargins(dp(6),dp(4),dp(6),dp(4))}
            return BookHolder(view)
        }
        override fun onBindViewHolder(holder:BookHolder,position:Int) {
            val work=group.items.getOrNull(position)
            if(work==null) {(holder.itemView as TextView).text=if(errors.containsKey(group.id))"Retry books" else "More books";return}
            (holder.itemView as PosterCardView).bindReadingWork(ReadingCompletionRepository.get(context).project(work),loader,api::imageUrl)
            holder.itemView.setOnFocusChangeListener { v,focused ->
                FocusDecorator.refresh(v,ringVisible());if(focused){selectedAuthor=group.id;selectedWork=work.id;rowPositions[group.id]=position}
            }
            holder.itemView.activateOnTap {selectedAuthor=group.id;selectedWork=work.id;onOpen(work)}
        }
    }
    private class BookHolder(view:View):ViewHolder(view)
    private fun more(label:String,action:()->Unit)=TextView(context).apply {
        text=label;textSize=13f;gravity=Gravity.CENTER;minimumHeight=dp(52);setTextColor(colors.accent)
        background=Styler.chipBackground(context,colors);Styler.makeFocusable(this);activateOnTap(action)
    }
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())
}
