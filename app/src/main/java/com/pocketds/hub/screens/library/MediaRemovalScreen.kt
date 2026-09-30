package com.pocketds.hub.screens.library

import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.*
import com.pocketds.hub.net.*
import com.pocketds.hub.ui.*
import kotlinx.coroutines.*

/** Read-only preview followed by a separate, one-use server confirmation. */
class MediaRemovalScreen(private val api: HubApi, private val kind: String, private val id: String, private val ring: () -> Boolean, private val closeDetails: Boolean = true) : Screen {
    override val title = "Delete from server"
    override val contentDomain = if(kind=="reading") com.pocketds.hub.state.ContentMode.BOOKS else com.pocketds.hub.state.ContentMode.MEDIA
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var overlay: ChoiceOverlay
    private lateinit var colors: PocketColors
    private val deleting = JobSlot()
    private val busy: Boolean get() = deleting.isBusy
    private var loaded = false
    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host=host;colors=Theme.colors(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(16),dp(24),dp(24))}
            status=label("Loading the deletion preview…",16f);body.addView(status)
            addView(ScrollView(context).apply {isFocusable=false;addView(body)},FrameLayout.LayoutParams(-1,-1))
            this@MediaRemovalScreen.overlay=ChoiceOverlay(context,colors,ring);addView(this@MediaRemovalScreen.overlay,FrameLayout.LayoutParams(-1,-1))
        }
    }
    override fun onShow() {if(!loaded)load()}
    override fun onDestroyView(){scope.cancel()}
    override fun onHide(){if(!busy)scope.coroutineContext.cancelChildren()}
    override fun onSystemBack():Boolean {if(busy){host.notify("Finishing the deletion…");return true};if(overlay.isOpen){overlay.dismiss();return true};return false}
    override fun onPad(action: PadAction):Boolean {
        if(busy)return true
        return overlay.onPad(action)
    }
    override fun hints()=if(busy)emptyList() else listOf(ButtonHint.activate("Choose"),ButtonHint.back("Cancel"))
    override fun requestInitialFocus()=body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() ?: false
    private fun load() {
        loaded=true
        scope.launch {
            when(val result=api.removalPreview(kind,id)) {
                is HubResult.Failed -> {status.text=result.message;button("Back") {host.back()};requestInitialFocus()}
                is HubResult.Ok -> {
                    val preview=result.value
                    status.text="${preview.title}\n${preview.fileCount} server file${if(preview.fileCount==1) "" else "s"}"
                    body.addView(label(preview.description,14f))
                    button("Cancel") {host.back()}
                    button("Delete from server…",danger=true) {
                        overlay.show("Permanently delete ${preview.title}?","${preview.fileCount} server files. This cannot be undone. Offline copies will remain.",listOf(
                            ChoiceOverlay.Choice("cancel","Keep media"),
                            ChoiceOverlay.Choice("delete","Delete server files",danger=true)
                        ),onCancel=host::refreshHints) {choice->
                            if(choice=="delete") {
                                status.text="Deleting ${preview.title}…";body.getFocusables(View.FOCUS_FORWARD).forEach {it.isEnabled=false}
                                deleting.launch(scope) {
                                    host.refreshHints()
                                    when(val deleted=api.removeMedia(preview.ticket)) {
                                        is HubResult.Ok -> {MediaLibraryChanges.changed();host.notify("Deleted from server · offline copies kept");leave()}
                                        is HubResult.Failed -> {MediaLibraryChanges.changed();body.removeAllViews();status=label(deleted.message,16f);body.addView(status);button("Back to library") {leave()};requestInitialFocus();host.refreshHints()}
                                    }
                                }
                            }
                            host.refreshHints()
                        };host.refreshHints()
                    }
                    body.addView(label("Files included",14f))
                    preview.files.forEach {name->body.addView(label(name,13f).apply {
                        minimumHeight=dp(44);setPadding(dp(10),dp(8),dp(10),dp(8))
                        Styler.makeFocusable(this);FocusDecorator.attach(this,ring,scale=false)
                    })}
                    requestInitialFocus()
                }
            }
        }
    }
    private fun leave() { host.back(); if(closeDetails)host.back() }
    private fun button(text:String,danger:Boolean=false,action:()->Unit) {
        body.addView(TextView(host.viewContext).apply {
            this.text=text;textSize=15f;minimumHeight=dp(48);setPadding(dp(14),dp(12),dp(14),dp(12))
            DetailStyler.action(this,colors);if(danger)setTextColor(colors.dangerText)
            FocusDecorator.attach(this,ring,scale=false);activateOnTap(action)
        },LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(12)})
    }
    private fun label(text:String,size:Float)=TextView(host.viewContext).apply {this.text=text;textSize=size;setTextColor(colors.primaryText);setPadding(0,dp(8),0,dp(8))}
    private fun dp(n:Int)=Styler.dpInt(host.viewContext,n.toFloat())
}
