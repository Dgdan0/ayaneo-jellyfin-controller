package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.model.LibraryView

/** A bounded square library tile, with its folder name kept readable over art. */
class LibraryCardView(
    context: Context,
    private val colors: PocketColors
) : FrameLayout(context) {

    private val placeholder: TextView
    private val artwork: ImageView
    private val label: TextView

    init {
        background = Styler.cardBackground(context, colors, cornerDp = 12f)
        Styler.makeFocusable(this)
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val inset = Styler.dpInt(context, 5f)
        setPadding(inset, inset, inset, inset)

        val face = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = Styler.dp(context, 9f)
                setColor(this@LibraryCardView.colors.posterPlaceholder)
            }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        addView(face, LayoutParams(MATCH, MATCH))

        placeholder = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 42f
            setTextColor(colors.mutedText)
        }
        face.addView(placeholder, LayoutParams(MATCH, MATCH))

        artwork = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        face.addView(artwork, LayoutParams(MATCH, MATCH))

        label = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(205, 10, 12, 18))
            val h = Styler.dpInt(context, 10f)
            val v = Styler.dpInt(context, 7f)
            setPadding(h, v, h, v)
        }
        face.addView(label, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    fun bind(view: LibraryView, imageLoader: ImageLoader, imageUrl: (String) -> String) {
        label.text = view.name
        placeholder.text = view.name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        artwork.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
        val url = imageUrl(view.image)
        if (url.isNotEmpty()) {
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .target(artwork)
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()
            )
        }
        contentDescription = view.name + when (view.kind) {
            "tvshows" -> ", TV library"
            else -> ", movie library"
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
