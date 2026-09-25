package com.pocketds.hub.screens.discover

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.CalendarResponse
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class UpcomingScreen(private val api:HubApi,private val ringVisible:()->Boolean):Screen {
 override val title="Upcoming"
 override val contentDomain=ContentMode.MEDIA
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private var job:Job?=null
 private var host:ScreenHost?=null
 private lateinit var colors:PocketColors
 private lateinit var agenda:LinearLayout
 private lateinit var details:LinearLayout
 private lateinit var status:TextView
 private lateinit var rangeLabel:TextView
 private lateinit var previous:TextView
 private lateinit var next:TextView
 private var detailAction:View?=null
 private val rows=mutableListOf<Pair<View,UpcomingPresentation.Group>>()
 private val artwork=mutableListOf<Disposable>()
 private var detailArtwork:Disposable?=null
 private var body:CalendarResponse?=null
 private var week=0
 private var selectedId=""
 private var anchor=LocalDate.now()
 private val zone get()=ZoneId.systemDefault()
 private val range get()=UpcomingPresentation.range(anchor,week)
 private val imageLoader by lazy { (api as? HubClient)?.imageLoader ?: ImageLoader.Builder(checkNotNull(host).viewContext).build() }

 override fun onCreateView(host:ScreenHost,container:ViewGroup):View {
  this.host=host;colors=Theme.colors(host.viewContext)
  return LinearLayout(host.viewContext).apply {
   orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.background);setPadding(dp(12),dp(8),dp(12),dp(12))
   addView(label("Your monitored movies and series",12f,true))
   addView(LinearLayout(context).apply {
    gravity=Gravity.CENTER_VERTICAL
    previous=button("‹ Previous"){changeWeek(-1)}
    next=button("Next ›"){changeWeek(1)}
    rangeLabel=label("",14f).apply{gravity=Gravity.CENTER}
    addView(previous);addView(rangeLabel,LinearLayout.LayoutParams(0,dp(42),1f));addView(next)
   },LinearLayout.LayoutParams(MATCH,dp(46)))
   status=label("Loading schedule…",11f,true)
   addView(status)
   addView(LinearLayout(context).apply {
    orientation=LinearLayout.HORIZONTAL
    agenda=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(3),dp(3),dp(8),dp(12))}
    details=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=Styler.cardBackground(context,colors)}
    addView(ScrollView(context).apply{isFocusable=false;addView(agenda)},LinearLayout.LayoutParams(0,MATCH,1.1f))
    addView(ScrollView(context).apply{isFocusable=false;addView(details)},LinearLayout.LayoutParams(0,MATCH,1f))
   },LinearLayout.LayoutParams(MATCH,0,1f))
  }
 }
 override fun onShow(){ if(body==null)load() }
 override fun onHide(){job?.cancel();job=null}
 override fun onDestroyView(){scope.cancel();artwork.forEach{it.dispose()};detailArtwork?.dispose();host=null}
 override fun hints()=listOf(ButtonHint.activate("Open"),ButtonHint.back(),ButtonHint("L2 / R2","Week",PadAction.Page(Direction.RIGHT)))
 override fun requestInitialFocus():Boolean=(rows.firstOrNull{it.second.id==selectedId}?.first?:rows.firstOrNull()?.first?:next).requestFocus()
 override fun onPad(action:PadAction):Boolean {
  if(action==PadAction.Refresh){load();return true}
  if(action is PadAction.Page){changeWeek(if(action.direction==Direction.LEFT||action.direction==Direction.UP)-1 else 1);return true}
  if(action is PadAction.Step){
   val index=rows.indexOfFirst{it.first.hasFocus()}
   if(index>=0)when(action.direction){
    Direction.UP->{if(index>0)rows[index-1].first.requestFocus() else next.requestFocus();return true}
    Direction.DOWN->{rows.getOrNull(index+1)?.first?.requestFocus();return true}
    Direction.RIGHT->{detailAction?.requestFocus();return true}
    Direction.LEFT->return true
   }
   if(action.direction==Direction.LEFT&&detailAction?.hasFocus()==true){requestInitialFocus();return true}
  }
  return false
 }
 private fun changeWeek(delta:Int){val target=(week+delta).coerceIn(-52,52);if(target!=week){week=target;selectedId="";body=null;load()}}
 private fun load(){
  job?.cancel();body=null
  val requested=range
  rangeLabel.text=requested.start.format(DateTimeFormatter.ofPattern("d MMM"))+" – "+requested.endExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
  previous.isEnabled=week> -52;next.isEnabled=week<52
  status.text="Loading schedule…"
  agenda.removeAllViews();details.removeAllViews();rows.clear();detailAction=null
  artwork.forEach{it.dispose()};artwork.clear();detailArtwork?.dispose()
  job=scope.launch {
   when(val result=api.calendar(requested.start.toString(),requested.endExclusive.toString(),zone.id)){
    is HubResult.Ok->{body=result.value;render(result.value)}
    is HubResult.Failed->{status.text=result.message;details.addView(button("Try again"){load()})}
   }
  }
 }
 private fun render(data:CalendarResponse){
  status.text=if(data.partial.isEmpty())"${data.items.size} ${if(data.items.size==1) "release" else "releases"} · ${zone.id}" else data.partial.joinToString(" · "){it.message}
  val grouped=UpcomingPresentation.groups(data.items)
  for(day in UpcomingPresentation.days(range)){
   val prefix=when(day){LocalDate.now()->"Today · ";LocalDate.now().plusDays(1)->"Tomorrow · ";else->""}
   agenda.addView(label(prefix+day.format(DateTimeFormatter.ofPattern("EEE d MMM")),12f,true).apply{setPadding(dp(4),dp(9),0,dp(5))})
   val groups=grouped.filter{it.first.date==day.toString()}
   if(groups.isEmpty()){agenda.addView(label("No scheduled releases",12f,true).apply{setPadding(dp(8),dp(5),0,dp(8))});continue}
   for(group in groups){
    val row=LinearLayout(checkNotNull(host).viewContext).apply {
     orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
     setPadding(dp(8),dp(7),dp(8),dp(7));background=Styler.cardBackground(context,colors)
     Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false)
     addView(poster(group.first.media.poster),LinearLayout.LayoutParams(dp(43),dp(64)))
     addView(LinearLayout(context).apply{
      orientation=LinearLayout.VERTICAL;setPadding(dp(10),0,0,0)
      addView(label(group.first.media.title,15f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
      addView(label(group.label,12f,true))
      addView(label(timeLabel(group),11f,true))
     },LinearLayout.LayoutParams(0,WRAP,1f))
     setOnFocusChangeListener {v,focused->FocusDecorator.refresh(v,ringVisible());if(focused){select(group);host?.refreshHints()}}
     setOnClickListener {select(group);open(group)}
     contentDescription=group.first.media.title+", "+group.label+", "+timeLabel(group)
    }
    agenda.addView(row,LinearLayout.LayoutParams(MATCH,WRAP).apply{bottomMargin=dp(6)})
    rows.add(row to group)
   }
  }
  val selected=grouped.firstOrNull{it.id==selectedId}?:grouped.firstOrNull{week==0&&it.first.date>=LocalDate.now().toString()}?:grouped.firstOrNull()
  if(selected!=null){select(selected);agenda.post{requestInitialFocus()}}
  else{details.addView(label("No scheduled releases this week",17f));details.addView(label("Use Previous or Next to explore another week.",13f,true))}
 }
 private fun select(group:UpcomingPresentation.Group){
  selectedId=group.id;details.removeAllViews();detailArtwork?.dispose()
  val first=group.first
  details.addView(LinearLayout(checkNotNull(host).viewContext).apply{
   gravity=Gravity.CENTER_VERTICAL
   addView(poster(first.media.poster,true),LinearLayout.LayoutParams(dp(60),dp(90)))
   addView(LinearLayout(context).apply{
    orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0)
    addView(label(first.media.title,19f).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END})
    addView(label(group.label,13f,true))
   },LinearLayout.LayoutParams(0,WRAP,1f))
  })
  details.addView(label(LocalDate.parse(first.date).format(DateTimeFormatter.ofPattern("EEEE d MMMM"))+" · "+timeLabel(group),12f,true).apply{setPadding(0,dp(12),0,dp(8))})
  details.addView(label(first.overview.ifBlank{"No description available"},14f).apply{maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END})
  val downloaded=group.items.count{it.hasFile}
  details.addView(label(if(downloaded==group.items.size)"Downloaded · ${first.service}" else if(downloaded>0)"$downloaded / ${group.items.size} downloaded" else "Monitored · ${first.service}",12f,true).apply{setPadding(0,dp(10),0,dp(5))})
  detailAction=button("Open title"){open(group)}.apply{isEnabled=first.media.key.isNotBlank()}
  details.addView(detailAction)
  if(first.media.key.isBlank())details.addView(label("Title details unavailable: missing metadata ID",11f,true))
 }
 private fun open(group:UpcomingPresentation.Group){
  if(group.first.media.key.isNotBlank())host?.push(MediaDetailScreen(api,group.first.media.key,group.first.media.title,ringVisible))
  else host?.notify("No metadata ID is available for this title")
 }
 private fun timeLabel(group:UpcomingPresentation.Group):String {
  val times=group.items.mapNotNull {runCatching{Instant.parse(it.at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrNull()}.distinct()
  return if(times.isEmpty())"Time not announced" else if(times.size==1)times.first() else "${times.first()} – ${times.last()}"
 }
 private fun poster(path:String,detail:Boolean=false):ImageView=ImageView(checkNotNull(host).viewContext).apply{
  scaleType=ImageView.ScaleType.CENTER_CROP;setBackgroundColor(colors.posterPlaceholder)
  if(path.isNotBlank()){
   val d=imageLoader.enqueue(ImageRequest.Builder(context).data(api.imageUrl(path)).target(this).build())
   if(detail)detailArtwork=d else artwork.add(d)
  }
 }
 private fun label(value:String,size:Float,muted:Boolean=false)=TextView(checkNotNull(host).viewContext).apply{
  text=value;textSize=size;setTextColor(if(muted)colors.mutedText else colors.primaryText)
 }
 private fun button(value:String,action:()->Unit)=label(value,13f).apply{
  gravity=Gravity.CENTER;minHeight=dp(40);setPadding(dp(12),dp(6),dp(12),dp(6))
  background=Styler.chipBackground(context,colors);Styler.makeFocusable(this);FocusDecorator.attach(this,ringVisible,false);setOnClickListener{action()}
 }
 private fun dp(value:Int)=Styler.dpInt(checkNotNull(host).viewContext,value.toFloat())
 private companion object {const val MATCH=ViewGroup.LayoutParams.MATCH_PARENT;const val WRAP=ViewGroup.LayoutParams.WRAP_CONTENT}
}
