package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.model.LibraryView
import com.pocketds.hub.model.ReadingLibrary

/** Wide library tile: banners fill it; portrait covers remain fully visible. */
class LibraryCardView(
    context: Context,
    private val colors: PocketColors
) : FrameLayout(context) {
    private val face = FrameLayout(context)
    private val backdrop = ImageView(context)
    private val dim = View(context)
    private val posterHolder = FrameLayout(context)
    private val poster = ImageView(context)
    private val placeholder = ImageView(context)
    private val portraitText = textStack(context)
    private val bannerText = textStack(context)

    init {
        foreground = Styler.focusOutline(context, colors)
        background = Styler.cardBackground(context, colors, 12f, Color.TRANSPARENT, 2f)
        Styler.makeFocusable(this)
        isClickable = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        face.background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 12f)
            setColor(this@LibraryCardView.colors.posterPlaceholder)
        }
        face.clipToOutline = true
        face.outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(face, LayoutParams(MATCH, MATCH))

        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        face.addView(backdrop, LayoutParams(MATCH, MATCH))
        face.addView(dim, LayoutParams(MATCH, MATCH))

        posterHolder.background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 7f)
            setColor(0xFF222730.toInt())
        }
        posterHolder.clipToOutline = true
        posterHolder.outlineProvider = ViewOutlineProvider.BACKGROUND
        poster.scaleType = ImageView.ScaleType.FIT_CENTER
        posterHolder.addView(poster, LayoutParams(MATCH, MATCH))
        placeholder.scaleType = ImageView.ScaleType.CENTER_INSIDE
        posterHolder.addView(placeholder, LayoutParams(dp(36), dp(36), Gravity.CENTER))
        face.addView(posterHolder, LayoutParams(dp(76), MATCH, Gravity.START).apply {
            setMargins(dp(8), dp(8), 0, dp(8))
        })

        face.addView(portraitText, LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(98)
            rightMargin = dp(12)
        })
        bannerText.background = ThemeGradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xF50A0C12.toInt(), 0x8C0A0C12.toInt(), 0x000A0C12)
        )
        bannerText.setPadding(dp(12), dp(20), dp(12), dp(10))
        face.addView(bannerText, LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(
                LibraryTileSizing.heightForWidth(width), MeasureSpec.EXACTLY
            ))
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val coverWidth = (w * .34f).toInt().coerceAtLeast(dp(55))
        (posterHolder.layoutParams as LayoutParams).apply {
            width = coverWidth
            posterHolder.layoutParams = this
        }
        (portraitText.layoutParams as LayoutParams).apply {
            leftMargin = coverWidth + dp(20)
            portraitText.layoutParams = this
        }
    }

    fun bind(view: LibraryView, imageLoader: ImageLoader, imageUrl: (String) -> String) {
        setText(view.name, if (view.kind == "tvshows") "TV LIBRARY" else "MOVIE LIBRARY")
        placeholder.setImageDrawable(AppIconDrawable(
            if (view.kind == "tvshows") AppIcon.TV else AppIcon.MOVIE, colors.mutedText
        ))
        setArtwork(imageUrl(view.image), LibraryArtworkStyle.media(view.imageStyle, view.id, view.image), imageLoader)
        contentDescription = view.name + if (view.kind == "tvshows") ", TV library" else ", movie library"
    }

    fun bindReading(view: ReadingLibrary, imageLoader: ImageLoader, imageUrl: (String) -> String) {
        val kind = when (view.kind) {
            "comic" -> "COMICS"
            "manga" -> "MANGA"
            else -> "BOOKS & AUDIO"
        }
        setText(view.title, kind)
        placeholder.setImageDrawable(AppIconDrawable(
            if (view.kind == "comic" || view.kind == "manga") AppIcon.COMIC else AppIcon.BOOK,
            colors.mutedText
        ))
        setArtwork(imageUrl(view.artwork), view.artworkStyle, imageLoader)
        contentDescription = "${view.title}, ${view.kind} library, ${view.source}"
    }

    private fun setText(title: String, type: String) {
        for (stack in listOf(portraitText, bannerText)) {
            (stack.getChildAt(0) as TextView).text = type
            (stack.getChildAt(1) as TextView).text = title
        }
    }

    private fun setArtwork(url: String, style: String, imageLoader: ImageLoader) {
        val wide = style == "banner" && url.isNotEmpty()
        val icon = style == "icon" && url.isNotEmpty()
        posterHolder.visibility = if (wide) View.GONE else View.VISIBLE
        portraitText.visibility = if (wide) View.GONE else View.VISIBLE
        bannerText.visibility = if (wide) View.VISIBLE else View.GONE
        dim.background = if (wide || icon) ColorDrawable(Color.TRANSPARENT) else ColorDrawable(0x9911161D.toInt())
        backdrop.alpha = if (wide) 1f else .52f
        poster.scaleType = if (icon) ImageView.ScaleType.CENTER_INSIDE else ImageView.ScaleType.FIT_CENTER
        placeholder.visibility = if (url.isEmpty()) View.VISIBLE else View.GONE
        backdrop.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
        poster.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
        if (url.isEmpty()) return
        if (!icon) imageLoader.enqueue(ImageRequest.Builder(context).data(url).target(backdrop)
            .bitmapConfig(Bitmap.Config.RGB_565).build())
        if (!wide) imageLoader.enqueue(ImageRequest.Builder(context).data(url).target(poster)
            .bitmapConfig(Bitmap.Config.RGB_565).build())
    }

    private fun textStack(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            textSize = 10f
            letterSpacing = .11f
            setTextColor(0xFF9DE0D7.toInt())
        })
        addView(TextView(context).apply {
            textSize = 16f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
