package com.pocketds.hub.screens.discover

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingType
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FormOverlay
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren

/** Provider detail and the explicit BookKeeprr acquisition form. */
class ReadingDetailScreen(
    private val api: HubApi,
    private val item: ReadingItem,
    private val ringVisible: () -> Boolean
) : Screen {
    override val title: String = item.title
    override val focusOnShow: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var form: FormOverlay
    private lateinit var status: TextView
    private lateinit var flow: ReadingRequestFlow
    private var requestButton: TextView? = null
    private var requested = false

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(18), dp(14), dp(22), dp(90))
            setBackgroundColor(colors.background)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
        }
        content.addView(cover, LinearLayout.LayoutParams(dp(180), dp(270)).apply {
            marginEnd = dp(22)
        })
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(context).apply {
            text = item.title
            textSize = 25f
            setTextColor(colors.primaryText)
        })
        info.addView(TextView(context).apply {
            text = buildList {
                if (item.author.isNotBlank()) add(item.author)
                if (item.year > 0) add(item.year.toString())
                add(ReadingType.label(item.contentType))
            }.joinToString(" · ")
            textSize = 13f
            setTextColor(colors.mutedText)
            setPadding(0, dp(4), 0, dp(12))
        })
        if (item.inLibrary) {
            info.addView(TextView(context).apply {
                text = "In library"
                textSize = 12f
                setTextColor(colors.accentText)
                background = Styler.chipBackground(context, colors, selected = true)
                setPadding(dp(10), dp(5), dp(10), dp(5))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
        } else if (item.actions.contains("request")) {
            requestButton = TextView(context).apply {
                text = "＋  Download"
                contentDescription = "Choose how to download ${item.title}"
                textSize = 14f
                setTextColor(colors.primaryText)
                background = Styler.chipBackground(context, colors)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                Styler.makeFocusable(this)
                isClickable = true
                setOnClickListener { if (!flow.busy && !requested) flow.start(item) }
                FocusDecorator.attach(this, ringVisible, scale = false)
            }
            info.addView(requestButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }
        if (item.description.isNotBlank()) {
            info.addView(TextView(context).apply {
                text = item.description
                textSize = 14f
                setTextColor(colors.primaryText)
                setLineSpacing(0f, 1.12f)
            })
        }
        val sourceText = buildList {
            if (item.source.isNotBlank()) add(item.source)
            if (item.isbn.isNotBlank()) add("ISBN ${item.isbn}")
        }.joinToString(" · ")
        if (sourceText.isNotBlank()) {
            info.addView(TextView(context).apply {
                text = sourceText
                textSize = 11f
                setTextColor(colors.mutedText)
                setPadding(0, dp(16), 0, 0)
            })
        }
        status = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(0, dp(12), 0, 0)
        }
        info.addView(status)
        content.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        cover.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val url = api.imageUrl(item.cover)
        if (url.isNotEmpty()) {
            val loader = (api as? HubClient)?.imageLoader ?: ImageLoader(context)
            loader.enqueue(
                ImageRequest.Builder(context).data(url).target(cover)
                    .bitmapConfig(Bitmap.Config.RGB_565).build()
            )
        }

        val frame = FrameLayout(context)
        frame.addView(
            ScrollView(context).apply { isFocusable = false; addView(content) },
            FrameLayout.LayoutParams(MATCH, MATCH)
        )
        form = FormOverlay(context, colors, ringVisible)
        frame.addView(form, FrameLayout.LayoutParams(MATCH, MATCH))
        flow = ReadingRequestFlow(
            api = api,
            scope = scope,
            overlay = { form },
            onStatus = { message, failed ->
                status.setTextColor(if (failed) colors.dangerText else colors.mutedText)
                status.text = message
            },
            onNotify = host::notify,
            onHintsChanged = host::refreshHints,
            onRequested = {
                requested = true
                requestButton?.apply {
                    text = "✓  Search started"
                    isEnabled = false
                    alpha = 0.7f
                }
            }
        )
        return frame
    }

    override fun hints(): List<ButtonHint> = when {
        ::form.isInitialized && form.isOpen ->
            listOf(ButtonHint.activate("Change"), ButtonHint.back("Cancel"))
        requestButton != null && !requested ->
            listOf(ButtonHint.activate(if (::flow.isInitialized && flow.busy) "Working…" else "Download"), ButtonHint.back())
        else -> listOf(ButtonHint.back())
    }

    override fun onPad(action: PadAction): Boolean {
        if (::form.isInitialized && form.onPad(action)) {
            host?.refreshHints()
            return true
        }
        return false
    }

    override fun onSystemBack(): Boolean {
        if (::form.isInitialized && form.isOpen) {
            form.onPad(PadAction.Back)
            host?.refreshHints()
            return true
        }
        return false
    }

    override fun requestInitialFocus(): Boolean = requestButton?.requestFocus() == true

    override fun onShow() = Unit

    override fun onHide() {
        if (::form.isInitialized && form.isOpen) form.dismiss()
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    private fun dp(value: Int) = Styler.dpInt(host?.viewContext ?: error("screen detached"), value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
