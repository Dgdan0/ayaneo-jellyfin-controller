package com.pocketds.hub.screens.discover

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.model.ReadingItem
import com.pocketds.hub.model.ReadingType
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme

/** Read-only provider detail while acquisition/library mutations remain gated. */
class ReadingDetailScreen(
    private val api: HubApi,
    private val item: ReadingItem
) : Screen {
    override val title: String = item.title

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        val context = host.viewContext
        val colors = Theme.colors(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(context, 18), dp(context, 14), dp(context, 22), dp(context, 90))
            setBackgroundColor(colors.background)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(colors.posterPlaceholder)
        }
        content.addView(cover, LinearLayout.LayoutParams(dp(context, 180), dp(context, 270)).apply {
            marginEnd = dp(context, 22)
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
            setPadding(0, dp(context, 4), 0, dp(context, 14))
        })
        if (item.inLibrary) info.addView(TextView(context).apply {
            text = "In library"
            textSize = 12f
            setTextColor(colors.accentText)
            background = Styler.chipBackground(context, colors, selected = true)
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 13)
        })
        if (item.description.isNotBlank()) info.addView(TextView(context).apply {
            text = item.description
            textSize = 14f
            setTextColor(colors.primaryText)
            setLineSpacing(0f, 1.12f)
        })
        val sourceText = buildList {
            if (item.source.isNotBlank()) add(item.source)
            if (item.isbn.isNotBlank()) add("ISBN ${item.isbn}")
        }.joinToString(" · ")
        if (sourceText.isNotBlank()) info.addView(TextView(context).apply {
            text = sourceText
            textSize = 11f
            setTextColor(colors.mutedText)
            setPadding(0, dp(context, 16), 0, 0)
        })
        content.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        cover.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val url = api.imageUrl(item.cover)
        if (url.isNotEmpty()) {
            val loader = (api as? HubClient)?.imageLoader ?: ImageLoader(context)
            loader.enqueue(ImageRequest.Builder(context).data(url).target(cover).bitmapConfig(Bitmap.Config.RGB_565).build())
        }
        return ScrollView(context).apply { isFocusable = false; addView(content) }
    }

    override fun hints(): List<ButtonHint> = listOf(ButtonHint.back())

    override fun onShow() = Unit
    override fun onHide() = Unit
    override fun onDestroyView() = Unit

    private fun dp(context: android.content.Context, value: Int) = Styler.dpInt(context, value.toFloat())
}
