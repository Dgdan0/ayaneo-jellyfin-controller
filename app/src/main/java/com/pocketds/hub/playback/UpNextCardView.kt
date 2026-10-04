package com.pocketds.hub.playback

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.model.PlaybackItem
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.EpisodeLabel
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight
import com.pocketds.hub.ui.typeRole

/**
 * Near the end of an episode: what plays next, and a bar that fills smoothly
 * until it starts by itself.
 *
 * One continuous fill rather than a number counting down a second at a time,
 * which read as jumpy. The fill stops while the video is paused, so the next
 * episode never starts behind a pause.
 *
 * On Glass the card is the controls' dark glass, Play now the white pill and
 * Watch credits a glass one.
 */
class UpNextCardView(
    context: Context,
    private val colors: PocketColors,
    private val api: HubApi,
    ringVisible: () -> Boolean
) : LinearLayout(context) {
    var onPlayNow: (() -> Unit)? = null
    var onWatchCredits: (() -> Unit)? = null
    /** The bar is full. */
    var onFilled: (() -> Unit)? = null

    private val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val kicker = TextView(context).apply { typeRole(Type.Role.EYEBROW); setTextColor(colors.accent); maxLines = 1 }
    private val title = TextView(context).apply {
        textSize = 13.5f; textWeight(600); setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = TextView(context).apply { textSize = 11f; setTextColor(Color.argb(200, 220, 226, 234)); maxLines = 1 }
    private val bar = FillBar(context)
    private val glass = com.pocketds.hub.ui.Theme.isGlass(context)
    val playNow: TextView = PillButton.create(context, colors, "Play now", AppIcon.PLAY, primary = true, heightDp = 36f, glass = glass)
    val watchCredits: TextView = PillButton.create(context, colors, "Watch credits", heightDp = 36f, glass = glass)
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        val pad = dp(12)
        setPadding(pad, pad, pad, dp(8))
        if (!com.pocketds.hub.ui.OverlayButtons.panel(this, 18f)) background = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 18f)
            setColor(Color.argb(240, 14, 18, 25))
            setStroke(dp(1), Color.argb(26, 255, 255, 255))
        }
        elevation = Styler.dp(context, 16f)
        isClickable = true
        val top = LinearLayout(context).apply { orientation = HORIZONTAL }
        val frame = FrameLayout(context).apply {
            background = ThemeGradientDrawable().apply { cornerRadius = Styler.dp(context, 10f); setColor(Color.argb(255, 26, 33, 44)) }
            clipToOutline = true
            addView(still, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        top.addView(frame, LayoutParams(dp(128), dp(72)))
        val words = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(1), 0, 0)
            addView(kicker)
            addView(title, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
            addView(meta, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }
        top.addView(words, LayoutParams(0, WRAP, 1f))
        addView(top, LayoutParams(MATCH, WRAP))
        addView(bar, LayoutParams(MATCH, dp(4)).apply { topMargin = dp(12) })
        addView(TextView(context).apply {
            text = "Starts automatically when the bar fills"
            textSize = 11f; setTextColor(Color.argb(170, 220, 226, 234))
        }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; clipChildren = false }
        val ring = -dp(PillButton.RING_DP.toInt())
        buttons.addView(playNow, LayoutParams(0, WRAP, 1f).apply { marginStart = ring })
        buttons.addView(watchCredits, LayoutParams(0, WRAP, 1f).apply { marginEnd = ring })
        addView(buttons, LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        listOf(playNow, watchCredits).forEach { FocusDecorator.attach(it, ringVisible, scale = false) }
        playNow.activateOnTap { onPlayNow?.invoke() }
        watchCredits.activateOnTap { onWatchCredits?.invoke() }
    }

    fun bind(next: PlaybackItem) {
        val code = EpisodeLabel.code(next.seasonNumber, next.episodeNumber)
        kicker.text = listOf("UP NEXT", code).filter(String::isNotBlank).joinToString(" · ")
        title.text = next.title.ifBlank { next.displayTitle() }
        meta.text = next.seriesTitle
        meta.visibility = if (next.seriesTitle.isBlank()) GONE else VISIBLE
        // A fixed size: bound while the card is hidden, a size taken from the view would
        // wait for its first layout and only start loading once the card showed.
        Artwork.bindHub(still, api, com.pocketds.hub.net.HubEndpoints.sized(
            com.pocketds.hub.net.HubEndpoints.jellyfinImage(next.id, "Primary"), 360), opaque = true) { size(dp(128), dp(72)) }
        contentDescription = "Up next: ${next.displayTitle()}"
    }

    /** Fills from empty over [UpNext.COUNTDOWN_MILLIS]; [onFilled] when full. */
    fun start() {
        animator?.cancel()
        bar.fraction = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UpNext.COUNTDOWN_MILLIS
            interpolator = LinearInterpolator()
            addUpdateListener { bar.fraction = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onFilled?.invoke() }
            })
            start()
        }
    }

    fun pause() { animator?.takeIf { it.isRunning }?.pause() }
    fun resume() { animator?.takeIf { it.isPaused }?.resume() }

    fun stop() {
        animator?.cancel()
        animator = null
        bar.fraction = 0f
    }

    val counting: Boolean get() = animator?.isStarted == true

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    /** A track and the accent filling it. */
    private inner class FillBar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        var fraction = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = ColorUtils.setAlphaComponent(Color.WHITE, 0x26)
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (fraction <= 0f) return
            paint.color = colors.accent
            rect.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        @Suppress("unused") val CENTER = Gravity.CENTER
    }
}
