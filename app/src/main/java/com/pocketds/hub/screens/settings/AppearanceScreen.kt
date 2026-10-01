package com.pocketds.hub.screens.settings

import com.pocketds.hub.ui.ThemeGradientDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.nav.*
import com.pocketds.hub.settings.*
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.*
import com.pocketds.hub.screens.home.HomeHeaderLabel
import com.pocketds.hub.screens.library.*

/** The preview has its own domain. It never changes the app's content mode or starts media. */
class AppearanceScreen(private val ringVisible:()->Boolean) : Screen {
    override val title="Appearance"
    override fun onShow() = Unit
    override fun onHide() = Unit
    override fun onDestroyView() { choices.clear() }
    private lateinit var host:ScreenHost
    private lateinit var root:LinearLayout
    private lateinit var controls:LinearLayout
    private lateinit var preview:LinearLayout
    private lateinit var colors:PocketColors
    private var previewMode=ContentMode.MEDIA
    private var previewTab="Home"
    private val choices=linkedMapOf<String,TextView>()
    private var selected="mode:SYSTEM"

    override fun onCreateView(host:ScreenHost,container:ViewGroup):View {
        this.host=host; colors=Theme.colors(host.viewContext)
        previewMode=ContentModeSettings.get(host.viewContext)
        root=LinearLayout(host.viewContext).apply {
            orientation=LinearLayout.HORIZONTAL;setPadding(dp(20),dp(12),dp(20),dp(16));setBackgroundColor(colors.background)
        }
        controls=LinearLayout(host.viewContext).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(3),0,dp(16),dp(12))}
        val scroll=ScrollView(host.viewContext).apply { isFocusable=false;clipToPadding=false;addView(controls) }
        root.addView(scroll,LinearLayout.LayoutParams(0,-1,1f))
        preview=LinearLayout(host.viewContext).apply {orientation=LinearLayout.VERTICAL;setTag(AccentRebinder.PREVIEW_TAG,true);setPadding(dp(16),dp(8),dp(16),dp(12))}
        val previewScroll=ScrollView(host.viewContext).apply {isFocusable=false;clipToPadding=false;addView(preview)}
        root.addView(previewScroll,LinearLayout.LayoutParams(0,-1,1.1f))
        root.addOnLayoutChangeListener { _,left,_,right,_,_,_,_,_->
            val stacked=(right-left)/host.viewContext.resources.displayMetrics.density<620
            val orientation=if(stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if(root.orientation!=orientation) {
                root.orientation=orientation
                scroll.layoutParams=LinearLayout.LayoutParams(if(stacked)-1 else 0,if(stacked)0 else -1,1f)
                previewScroll.layoutParams=LinearLayout.LayoutParams(if(stacked)-1 else 0,if(stacked)0 else -1,1.1f)
            }
        }
        renderControls();renderPreview();return root
    }
    private fun renderControls() {
        controls.removeAllViews();choices.clear()
        label(controls,"Display",18f)
        val modes=LinearLayout(host.viewContext)
        ThemeSettings.Mode.entries.forEach { mode ->
            val id="mode:${mode.name}"
            modes.addView(choice(id,if(mode==ThemeSettings.Mode.SYSTEM) "System" else mode.name.lowercase().replaceFirstChar(Char::uppercase),ThemeSettings.getMode(host.viewContext)==mode) {
                ThemeSettings.setMode(host.viewContext,mode);host.refreshAppearance();renderControls();renderPreview();restoreFocus()
            },LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(4)})
        };controls.addView(modes)
        ContentMode.entries.forEach { domain ->
            label(controls,"${domain.label} color",16f)
            val saved=DomainPreferences.accent(host.viewContext,domain)
            AccentPreset.entries.chunked(2).forEach { pair ->
                val row=LinearLayout(host.viewContext)
                pair.forEach { preset ->
                    val id="${domain.stored}:${preset.id}"
                    val view=choice(id,preset.label,saved==preset) {
                        DomainPreferences.setAccent(host.viewContext,domain,preset)
                        host.refreshAppearance();renderControls();renderPreview();restoreFocus()
                    }
                    view.setCompoundDrawablesWithIntrinsicBounds(ThemeGradientDrawable().apply {
                        shape=GradientDrawable.OVAL;setSize(dp(14),dp(14));setColor(if(Theme.isDark(host.viewContext))preset.dark else preset.light)
                    },null,null,null)
                    view.compoundDrawablePadding=dp(8)
                    row.addView(view,LinearLayout.LayoutParams(0,dp(48),1f).apply{marginEnd=dp(4);bottomMargin=dp(3)})
                };controls.addView(row)
            }
        }
    }
    private fun choice(id:String,text:String,checked:Boolean,action:()->Unit):TextView = TextView(host.viewContext).apply {
        tag=id
        this.text=if(checked) "$text  ✓" else text;textSize=12f;gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(10),0,dp(8),0);setTextColor(if(checked)colors.accent else colors.primaryText)
        isSelected=checked;contentDescription="$text${if(checked)", selected" else ""}"
        background=Styler.selectionBackground(context,colors,checked);Styler.makeFocusable(this)
        FocusDecorator.attach(this,ringVisible,scale=false)
        FocusDecorator.listen(this,ringVisible) { view,focused -> if(focused)selected=id }
        activateOnTap(action);choices[id]=this
    }
    private fun renderPreview() {
        preview.removeAllViews()
        val palette=Theme.preview(host.viewContext,previewMode)
        preview.background=ThemeGradientDrawable().apply{cornerRadius=dp(18).toFloat();setColor(palette.cardSurface);setStroke(dp(1),palette.cardSurfacePressed)}
        label(preview,"Preview",12f,palette.mutedText)
        val switch=ContentModeToggleView(host.viewContext,palette).apply{
            select(previewMode);onModeSelected={previewMode=it;renderPreview();preview.post { (preview.getChildAt(1) as? ContentModeToggleView)?.focus(it) }}
        };preview.addView(switch)
        val tabs=LinearLayout(host.viewContext)
        listOf("Home","Library").forEach { name ->
            tabs.addView(TextView(host.viewContext).apply {
                text=name;textSize=12f;gravity=Gravity.CENTER;setTextColor(if(name==previewTab)palette.accent else palette.mutedText)
                background=Styler.selectionBackground(context,palette,name==previewTab);isSelected=name==previewTab;Styler.makeFocusable(this)
                contentDescription="Preview $name";activateOnTap {previewTab=name;renderPreview();preview.findViewWithTag<View>("preview:$name")?.requestFocus()};tag="preview:$name"
            },LinearLayout.LayoutParams(0,dp(48),1f))
        };preview.addView(tabs)
        label(preview,if(previewTab=="Home") HomeHeaderLabel.forUser(HubSettings.userName(host.viewContext)) else "Your library",20f,palette.primaryText)
        label(preview,if(previewTab=="Library") "Recently added" else if(previewMode==ContentMode.BOOKS) "Continue reading" else "Continue watching",14f,palette.primaryText)
        val sample=LinearLayout(host.viewContext).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=Styler.selectionBackground(context,palette,true)}
        sample.addView(ImageView(host.viewContext).apply {setImageDrawable(AppIconDrawable(if(previewMode==ContentMode.BOOKS)AppIcon.BOOK else AppIcon.MEDIA,palette.accent))},LinearLayout.LayoutParams(dp(40),dp(48)))
        val copy=LinearLayout(host.viewContext).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0)}
        label(copy,if(previewMode==ContentMode.BOOKS)"A good story" else "Your next episode",15f,palette.primaryText)
        label(copy,if(previewMode==ContentMode.BOOKS)"Chapter 4 · 36% read" else "S1 E4 · 12 minutes left",12f,palette.mutedText)
        copy.addView(ProgressBar(host.viewContext,null,android.R.attr.progressBarStyleHorizontal).apply {progress=36;progressBackgroundTintList=android.content.res.ColorStateList.valueOf(palette.cardSurfacePressed);progressTintList=android.content.res.ColorStateList.valueOf(palette.accent)},LinearLayout.LayoutParams(-1,dp(6)).apply{topMargin=dp(10)})
        sample.addView(copy,LinearLayout.LayoutParams(0,-2,1f));preview.addView(sample)
        if(previewMode==ContentMode.BOOKS) preview.addView(ReadingFormatStatusView(host.viewContext,palette).apply {
            bind(listOf(ReadingFormatStatus("ebook","Ebook",FormatReadiness.READY),ReadingFormatStatus("audiobook","Audiobook",FormatReadiness.READY),ReadingFormatStatus("readaloud","Read along",FormatReadiness.MISSING)))
        })
        label(preview,"Sample content · Your library stays where you left it",11f,palette.mutedText)
    }
    private fun label(parent:LinearLayout,text:String,size:Float,color:Int=colors.primaryText) {
        parent.addView(TextView(host.viewContext).apply {this.text=text;textSize=size;setTextColor(color);setPadding(0,dp(if(parent===controls)12 else 6),0,dp(if(parent===controls)8 else 4));if(size>=16)setTypeface(typeface,Typeface.BOLD)})
    }
    private fun restoreFocus(){choices[selected]?.requestFocus()}
    override fun requestInitialFocus()=choices[selected]?.requestFocus()==true || choices.values.firstOrNull()?.requestFocus()==true
    override fun hints()=listOf(ButtonHint.activate("Choose"),ButtonHint.back())
    private fun dp(value:Int)=Styler.dpInt(host.viewContext,value.toFloat())
}
