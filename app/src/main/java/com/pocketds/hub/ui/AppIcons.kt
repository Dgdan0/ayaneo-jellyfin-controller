package com.pocketds.hub.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.TextView

enum class AppIcon { CLOSE, CONTENTS, APPEARANCE, PREVIOUS, NEXT, PREVIOUS_ITEM, NEXT_ITEM, THIRDS, FIT, DIRECTION, ZOOM_IN, ZOOM_OUT, SEARCH, OPEN, REFRESH, BOOK, HEADPHONES, READ_ALONG, BOOKMARK, BOOKMARK_FILLED, COMIC, MOVIE, TV, SETTINGS, CHECK, MEDIA, SORT, PANEL, PLAY, INFO, PERSON, SERIES, ADD, STAR, HOME, SUBTITLES, DOWNLOAD, MORE, ARRANGE, GRIP, PAD }

/** Original vector geometry; controls do not depend on the vendor's symbol font. */
class AppIconDrawable(private val icon: AppIcon, color: Int) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color;style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    fun recolor(replacements: Map<Int, Int>) { replacements[paint.color]?.let { paint.color=it; invalidateSelf() } }
    /** One colour, for an icon that follows its label's (a segment going from off to on). */
    fun tint(color: Int) { if (paint.color != color) { paint.color=color; invalidateSelf() } }
    override fun draw(canvas: Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        fun line(x:Float,y:Float,a:Float,b:Float)=canvas.drawLine(x,y,a,b,paint)
        fun chevron(next:Boolean) {val x=if(next) 9f else 15f;val tip=if(next) 15f else 9f;line(x,6f,tip,12f);line(tip,12f,x,18f)}
        fun path(data:String) = canvas.drawPath(androidx.core.graphics.PathParser.createPathFromPathData(data)!!,paint)
        when(icon) {
            AppIcon.CLOSE->{line(6f,6f,18f,18f);line(6f,18f,18f,6f)}
            AppIcon.CONTENTS->{for(y in listOf(6f,12f,18f)){line(8f,y,20f,y);line(4f,y,4.2f,y)}}
            AppIcon.APPEARANCE->{line(3f,18f,8f,5f);line(8f,5f,13f,18f);line(5f,13f,11f,13f);canvas.drawOval(15f,12f,21f,18f,paint);line(21f,12f,21f,18f)}
            AppIcon.PREVIOUS,AppIcon.NEXT->chevron(icon==AppIcon.NEXT)
            AppIcon.PREVIOUS_ITEM,AppIcon.NEXT_ITEM->{chevron(icon==AppIcon.NEXT_ITEM);val x=if(icon==AppIcon.NEXT_ITEM) 19f else 5f;line(x,5f,x,19f)}
            AppIcon.THIRDS->{canvas.drawRoundRect(5f,3f,19f,21f,2f,2f,paint);line(5f,9f,19f,9f);line(5f,15f,19f,15f)}
            AppIcon.FIT->{canvas.drawRect(7f,3f,17f,21f,paint);line(2f,8f,2f,16f);line(22f,8f,22f,16f)}
            AppIcon.DIRECTION->{line(3f,8f,21f,8f);line(17f,4f,21f,8f);line(21f,8f,17f,12f);line(21f,17f,3f,17f);line(7f,13f,3f,17f);line(3f,17f,7f,21f)}
            AppIcon.ZOOM_IN,AppIcon.ZOOM_OUT,AppIcon.SEARCH->{canvas.drawCircle(10f,10f,6f,paint);line(15f,15f,21f,21f);if(icon!=AppIcon.SEARCH)line(7f,10f,13f,10f);if(icon==AppIcon.ZOOM_IN)line(10f,7f,10f,13f)}
            AppIcon.OPEN->{line(13f,4f,20f,4f);line(20f,4f,20f,11f);line(11f,13f,20f,4f);line(5f,4f,5f,20f);line(5f,20f,20f,20f);line(20f,20f,20f,15f)}
            AppIcon.REFRESH->{canvas.drawArc(4f,4f,20f,20f,30f,285f,false,paint);line(19f,3f,19f,9f);line(19f,9f,13f,9f)}
            AppIcon.BOOK->path("M12 5c-3-2-7-2-10-1v15c3-1 7-1 10 1 3-2 7-2 10-1V4c-3-1-7-1-10 1Zm0 0v15")
            AppIcon.HEADPHONES->path("M3 13v-2a9 9 0 0 1 18 0v2M3 12h4v8H3Zm14 0h4v8h-4Z")
            AppIcon.READ_ALONG->path("M10 8C7 6 4 6 2 7v13c2-1 5-1 8 1 3-2 6-2 8-1v-6M10 8v13M10 8c1-.7 2-1 3-1M16 6v4m3-7v10m3-7v4")
            AppIcon.BOOKMARK,AppIcon.BOOKMARK_FILLED->{val path=Path().apply{moveTo(6f,3f);lineTo(18f,3f);lineTo(18f,21f);lineTo(12f,17f);lineTo(6f,21f);close()};if(icon==AppIcon.BOOKMARK_FILLED)paint.style=Paint.Style.FILL;canvas.drawPath(path,paint);paint.style=Paint.Style.STROKE}
            AppIcon.COMIC->{canvas.drawRect(3f,3f,21f,21f,paint);line(3f,10f,21f,10f);line(12f,10f,12f,21f)}
            AppIcon.MOVIE->{canvas.drawRoundRect(3f,3f,21f,21f,2f,2f,paint);line(7f,3f,7f,21f);line(17f,3f,17f,21f);for(y in listOf(7f,12f,17f)){line(3f,y,7f,y);line(17f,y,21f,y)}}
            AppIcon.TV->{canvas.drawRoundRect(3f,5f,21f,18f,2f,2f,paint);line(8f,22f,16f,22f);line(12f,18f,12f,22f)}
            AppIcon.SETTINGS->{canvas.drawCircle(12f,12f,3f,paint);canvas.drawCircle(12f,12f,8f,paint);for(a in 0..3){canvas.save();canvas.rotate(a*90f,12f,12f);line(12f,1f,12f,4f);canvas.restore()}}
            AppIcon.PANEL->{canvas.drawRoundRect(3f,4f,21f,20f,3f,3f,paint);path("M9 4v16m4-12 4 4-4 4")}
            AppIcon.MEDIA->{canvas.drawRoundRect(3f,4f,21f,20f,3f,3f,paint);path("m10 8 6 4-6 4Z")}
            AppIcon.SORT->path("M4 5h16M4 12h10M4 19h4m9-5v7m-3-3 3 3 3-3")
            AppIcon.CHECK->{line(5f,12f,10f,17f);line(10f,17f,20f,6f)}
            AppIcon.PLAY->{paint.style=Paint.Style.FILL;path("M7 4.5v15l12.5-7.5z");paint.style=Paint.Style.STROKE}
            AppIcon.INFO->{canvas.drawCircle(12f,12f,9f,paint);line(12f,11f,12f,17f);line(12f,7.5f,12f,8f)}
            AppIcon.PERSON->{canvas.drawCircle(12f,8f,4f,paint);path("M4 21c0-4 3.6-7 8-7s8 3 8 7")}
            AppIcon.SERIES->{canvas.drawRoundRect(4f,6f,16f,21f,1.5f,1.5f,paint);path("M8 3h10.5a1.5 1.5 0 0 1 1.5 1.5V18")}
            AppIcon.ADD->{line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
            AppIcon.STAR->path("M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.6l-5.1 2.7 1-5.7-4.1-4 5.7-.8z")
            AppIcon.HOME->path("M4 11 12 4l8 7v9h-5v-6H9v6H4z")
            AppIcon.SUBTITLES->{canvas.drawRoundRect(3f,5f,21f,19f,2.5f,2.5f,paint);path("M10.5 10.2a2.4 2.4 0 1 0 0 3.6m7-3.6a2.4 2.4 0 1 0 0 3.6")}
            AppIcon.DOWNLOAD->path("M12 4v11m-5-5 5 5 5-5M5 20h14")
            AppIcon.MORE->{paint.style=Paint.Style.FILL;for(x in listOf(6f,12f,18f))canvas.drawCircle(x,12f,1.6f,paint);paint.style=Paint.Style.STROKE}
            // Four tiles, one of them lifted out of line: arranging a Library root (#15).
            AppIcon.ARRANGE->{canvas.drawRoundRect(4f,4f,10.5f,10.5f,1.8f,1.8f,paint);canvas.drawRoundRect(4f,13.5f,10.5f,20f,1.8f,1.8f,paint);canvas.drawRoundRect(13.5f,13.5f,20f,20f,1.8f,1.8f,paint);canvas.save();canvas.rotate(12f,16.75f,7.25f);canvas.drawRoundRect(13.5f,3f,20f,9.5f,1.8f,1.8f,paint);canvas.restore()}
            // A controller: what the keys do (the readers' Controls sheet, #16).
            AppIcon.PAD->{path("M7.5 7h9a4.5 4.5 0 0 1 4.3 5.8l-1 3.6a2.6 2.6 0 0 1-4.4 1.1L13.6 16h-3.2l-1.8 1.5a2.6 2.6 0 0 1-4.4-1.1l-1-3.6A4.5 4.5 0 0 1 7.5 7Z");line(6.5f,11.5f,9.5f,11.5f);line(8f,10f,8f,13f);paint.style=Paint.Style.FILL;canvas.drawCircle(16f,10.6f,1.1f,paint);canvas.drawCircle(15.9f,13.4f,1.1f,paint);paint.style=Paint.Style.STROKE}
            // Two columns of three dots: something that can be held and moved (#15).
            AppIcon.GRIP->{paint.style=Paint.Style.FILL;for(x in listOf(8.5f,15.5f))for(y in listOf(5f,12f,19f))canvas.drawCircle(x,y,2.1f,paint);paint.style=Paint.Style.STROKE}
        }
        canvas.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

object AppIcons {
    fun button(context:Context, colors:PocketColors, icon:AppIcon,label:String):TextView=TextView(context).apply {
        minimumWidth=Styler.dpInt(context,48f);minimumHeight=Styler.dpInt(context,48f)
        gravity=Gravity.CENTER;contentDescription=label
        val drawable=AppIconDrawable(icon,colors.primaryText).apply {setBounds(0,0,Styler.dpInt(context,22f),Styler.dpInt(context,22f))}
        setCompoundDrawables(drawable,null,null,null)
        setPadding(Styler.dpInt(context,13f),0,Styler.dpInt(context,13f),0)
        Styler.makeFocusable(this)
        background=Styler.cardBackground(context,colors,8f,Color.TRANSPARENT,2f)
    }
}
