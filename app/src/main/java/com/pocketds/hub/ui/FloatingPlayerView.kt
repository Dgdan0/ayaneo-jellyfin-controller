package com.pocketds.hub.ui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.Direction
import com.pocketds.hub.state.FloatingWindow

/**
 * A trailer, playing in a window you can move around while you keep browsing.
 *
 * **YouTube's own embed, in a WebView.** Not a stream pulled apart and fed to a
 * media player: the official IFrame embed is the only sanctioned way to play
 * YouTube content in an app, and it also means playback controls, captions and
 * quality selection all arrive for free. `youtube-nocookie.com` is the same
 * player without the tracking cookies.
 *
 * **Not Android's system PiP.** That would shrink *this whole app* into a corner
 * of the launcher, which is the opposite of what is wanted — the trailer should
 * float over the app while the app stays usable underneath. So the window is
 * ours, inside our own layout.
 *
 * Position and size live in [FloatingWindow], which is pure and tested; this
 * class only draws the result and translates gestures.
 */
@SuppressLint("SetJavaScriptEnabled")
class FloatingPlayerView(
    context: Context,
    private val colors: PocketColors,
    private val onClose: () -> Unit,
    private val onOpenExternally: (String) -> Unit,
    /** Bounds of the content layer, relative to this view's parent. */
    private val safeArea: () -> Rect,
    /** Called whenever position or size changed, so the hint bar can relabel. */
    private val onChanged: () -> Unit
) : FrameLayout(context) {

    val window = FloatingWindow()

    private val web: WebView
    private val shell: LinearLayout
    private val shellBackground: android.graphics.drawable.GradientDrawable
    private val titleView: TextView
    private val loading: TextView
    private var videoUrl: String = ""
    private var videoKey: String = ""
    private var usedFallback = false

    /** Drag state, in parent coordinates. */
    private var dragging = false
    private var dragCandidate = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var draggedLeft = 0
    private var draggedTop = 0
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var boundsAnimator: ValueAnimator? = null

    init {
        visibility = View.GONE
        // Above every screen; floating geometry keeps it inside the content.
        elevation = Styler.dp(context, 18f)

        shellBackground = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 12f)
            setColor(Color.BLACK)
            setStroke(Styler.dpInt(context, 1f), Color.TRANSPARENT)
        }
        shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = shellBackground
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            elevation = Styler.dp(context, 12f)
        }
        addView(shell, LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.stripBackground)
            setPadding(Styler.dpInt(context, 10f), 0, Styler.dpInt(context, 2f), 0)
        }
        shell.addView(bar, LinearLayout.LayoutParams(MATCH, toolbarHeight()))

        titleView = TextView(context).apply {
            textSize = 11f
            setTextColor(colors.primaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))

        // Real controls for a pointer, labelled for everyone. The gamepad drives
        // the same actions through PadAction, so there is one implementation of
        // each and no touch-only parallel UI.
        bar.addView(chip("⛶", "Toggle fullscreen") { toggleFullscreen() })
        bar.addView(chip("↗", "Open in YouTube") { if (videoUrl.isNotEmpty()) onOpenExternally(videoUrl) })
        bar.addView(chip("×", "Close trailer") { onClose() })

        web = WebView(context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Required for autoplay=1 to actually start; without it the embed
            // waits for a tap that a gamepad user has no way to give.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // WebView advertises itself with "; wv" in the user agent, and
            // YouTube treats that as an unsupported embedded browser. Removing
            // the marker leaves an otherwise honest Chrome string.
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            isFocusable = false
            addJavascriptInterface(Bridge(), "Bridge")
            // The embed's own fullscreen button is disabled (fs=0) and this
            // stays as a guard: a WebView asking to swap in a custom fullscreen
            // view would escape our window entirely.
            webChromeClient = object : WebChromeClient() {}
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    // The player's own links -- "Watch on YouTube", a channel
                    // page -- would otherwise navigate inside a 40%-wide window
                    // with no address bar and no way back. Hand them out.
                    val url = request.url.toString()
                    if (url.startsWith("https://www.youtube.com/embed/") ||
                        url.startsWith("https://www.youtube.com/iframe_api")
                    ) {
                        return false
                    }
                    onOpenExternally(url)
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // The fallback is an ordinary page load, so this is when it
                    // has something to show.
                    if (usedFallback) revealPlayer()
                }
            }
        }
        // The WebView and a plain "loading" panel share the same slot. The
        // panel covers the embed's first moments because YouTube's refusal
        // renders as its own error card, and flashing that up for the ~700ms
        // before the fallback loads looks like a bug rather than a retry.
        val stack = FrameLayout(context)
        stack.addView(web, LayoutParams(MATCH, MATCH))
        loading = TextView(context).apply {
            text = "Loading trailer…"
            textSize = 12f
            setTextColor(colors.mutedText)
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        stack.addView(loading, LayoutParams(MATCH, MATCH))
        shell.addView(stack, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    val isOpen: Boolean get() = visibility == View.VISIBLE

    /**
     * @param key the YouTube video id, not a watch URL. The embed path needs the
     *   bare id, and having the hub send it means no URL parsing here.
     */
    fun open(key: String, watchUrl: String, title: String) {
        videoUrl = watchUrl
        videoKey = key
        usedFallback = false
        titleView.text = title
        visibility = View.VISIBLE
        bringToFront()
        DebugLog.log("nav", "trailer $key")
        loading.visibility = View.VISIBLE
        // Whatever happens, stop covering the window. A wedged load should end
        // up showing YouTube's own message rather than our placeholder forever.
        postDelayed({ revealPlayer() }, 8_000L)
        web.loadDataWithBaseURL(
            "https://www.youtube.com",
            playerHtml(key),
            "text/html",
            "utf-8",
            null
        )
        applyBounds(animate = false)
    }

    /**
     * Told by the player when it refuses to play.
     *
     * 101 and 150 are "the owner disallowed embedding"; 152 turns up when the
     * embed's origin is not accepted at all. Whatever the reason, the useful
     * response is the same -- fall back to the ordinary mobile watch page,
     * which is a plain website visit and always works.
     */
    private inner class Bridge {
        @android.webkit.JavascriptInterface
        fun onPlayerReady() {
            post { revealPlayer() }
        }

        @android.webkit.JavascriptInterface
        fun onPlayerError(code: String) {
            post {
                DebugLog.log("nav", "trailer embed refused ($code), using the watch page")
                fallbackToWatchPage()
            }
        }
    }

    private fun fallbackToWatchPage() {
        val key = videoKey
        if (key.isEmpty() || usedFallback) return
        usedFallback = true
        web.loadUrl("https://m.youtube.com/watch?v=" + key)
    }

    private fun revealPlayer() {
        loading.visibility = View.GONE
    }

    /**
     * The IFrame Player API, not a bare iframe.
     *
     * Both of the simpler routes are refused by YouTube and it is worth
     * recording which and why, because the errors are not self-explanatory:
     *
     *  * `loadUrl` straight at `/embed/KEY` answers **"Video player
     *    configuration error, Error 153"** -- no page origin at all.
     *  * A hand-written `<iframe src=...>` inside `loadDataWithBaseURL` answers
     *    **"This video is unavailable, Error 152"** for *every* video, official
     *    studio uploads included. The base URL sets the page's origin, but
     *    WebView sends no Referer for the iframe's own request, so the embed
     *    still looks anonymous.
     *
     * Loading `iframe_api` from youtube.com and letting *it* create the player
     * is the sanctioned path, and the one every in-app YouTube library takes:
     * the script runs as youtube.com, so the player it builds is properly
     * attributed.
     */
    private fun playerHtml(key: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}
          #player{width:100%;height:100%}
        </style></head><body>
        <div id="player"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          function onYouTubeIframeAPIReady() {
            new YT.Player('player', {
              width: '100%', height: '100%', videoId: '$key',
              playerVars: {
                autoplay: 1, playsinline: 1, rel: 0, fs: 0,
                modestbranding: 1, origin: 'https://www.youtube.com'
              },
              events: {
                onReady: function (e) { Bridge.onPlayerReady(); e.target.playVideo(); },
                onError: function (e) { Bridge.onPlayerError(e.data); }
              }
            });
          }
        </script></body></html>
    """.trimIndent()

    /**
     * Stop and tear down.
     *
     * Loading a blank page first is not belt-and-braces: destroy() alone has
     * been known to leave audio playing until the process is collected, and a
     * trailer you cannot hear the end of is a bug people remember.
     */
    fun close() {
        if (!isOpen) return
        visibility = View.GONE
        web.loadUrl("about:blank")
        web.onPause()
        videoUrl = ""
    }

    fun destroy() {
        web.loadUrl("about:blank")
        web.destroy()
    }

    fun pausePlayback() {
        if (isOpen) web.onPause()
    }

    fun resumePlayback() {
        if (isOpen) web.onResume()
    }

    /** @return true when the press was used. */
    fun onPad(action: com.pocketds.hub.input.PadAction): Boolean {
        if (!isOpen) return false
        return when (action) {
            is com.pocketds.hub.input.PadAction.Step -> {
                val moved = window.nudge(action.direction)
                if (moved) {
                    applyBounds(animate = true)
                    onChanged()
                }
                // Consumed either way: while the trailer has control, a
                // directional press must not also move the selection on the
                // screen behind it.
                true
            }
            com.pocketds.hub.input.PadAction.Activate -> {
                toggleFullscreen()
                true
            }
            com.pocketds.hub.input.PadAction.Back -> {
                onClose()
                true
            }
            com.pocketds.hub.input.PadAction.Secondary -> {
                // Grows, and wraps back to the smallest once it cannot grow, so
                // one button cycles all three sizes instead of needing a second
                // one to shrink.
                if (!window.cycleSize(1)) {
                    while (window.cycleSize(-1)) Unit
                }
                applyBounds(animate = true)
                onChanged()
                true
            }
            else -> false
        }
    }

    fun toggleFullscreen() {
        window.toggleFullscreen()
        applyBounds(animate = true)
        onChanged()
    }

    fun setControlMode(active: Boolean) {
        shellBackground.setStroke(
            Styler.dpInt(context, if (active) 2f else 1f),
            if (active) colors.focusRing else Color.TRANSPARENT
        )
        shell.background = shellBackground
    }

    /** Re-read the geometry. Call after the parent resizes. */
    fun applyBounds(animate: Boolean = false) {
        val parent = parent as? ViewGroup ?: return
        if (parent.width == 0) {
            post { applyBounds(animate) }
            return
        }
        val margin = Styler.dpInt(context, 12f)
        val safe = safeArea().takeIf { it.width() > 0 && it.height() > 0 }
            ?: Rect(0, 0, parent.width, parent.height)
        val target = window.bounds(
            parent.width, parent.height, margin, toolbarHeight(),
            safeLeftPx = safe.left,
            safeTopPx = safe.top,
            safeRightPx = parent.width - safe.right,
            safeBottomPx = parent.height - safe.bottom
        )
        val current = com.pocketds.hub.state.WindowBounds(left, top, width, height)
        boundsAnimator?.cancel()
        if (!animate || current.width <= 0 || current.height <= 0) {
            setBounds(target)
            return
        }
        boundsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val f = animation.animatedFraction
                setBounds(com.pocketds.hub.state.WindowBounds(
                    lerp(current.left, target.left, f),
                    lerp(current.top, target.top, f),
                    lerp(current.width, target.width, f),
                    lerp(current.height, target.height, f)
                ))
            }
            start()
        }
    }

    private fun setBounds(bounds: com.pocketds.hub.state.WindowBounds) {
        layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(0, 0)).apply {
            width = bounds.width; height = bounds.height
            gravity = Gravity.TOP or Gravity.START
            leftMargin = bounds.left; topMargin = bounds.top
        }
        requestLayout()
    }

    /**
     * Free dragging for a pointer, snapping to the nearest corner on release.
     *
     * The shell claims a gesture only in the non-button part of the title bar.
     * It owns that gesture from DOWN so Android keeps delivering events, but no
     * movement begins until the pointer crosses touch slop.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (window.fullscreen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Leave the three toolbar controls to receive ordinary taps.
                dragCandidate = event.y <= toolbarHeight() && event.x < width - controlWidth() * 3
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragOffsetX = 0f
                dragOffsetY = 0f
                draggedLeft = left
                draggedTop = top
                dragging = false
                // The title has no click action. Claiming its DOWN is required
                // to receive the later MOVE; returning false here ends delivery
                // before touch slop can ever be crossed.
                return dragCandidate
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (window.fullscreen || !dragCandidate) return super.onTouchEvent(event)
        val parent = parent as? ViewGroup ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    kotlin.math.abs(event.rawX - dragStartX) <= touchSlop &&
                    kotlin.math.abs(event.rawY - dragStartY) <= touchSlop
                ) return true
                if (!dragging) {
                    dragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
                dragOffsetX = event.rawX - dragStartX
                dragOffsetY = event.rawY - dragStartY
                val safe = safeArea().takeIf { it.width() > 0 && it.height() > 0 }
                    ?: Rect(0, 0, parent.width, parent.height)
                val margin = Styler.dpInt(context, 12f)
                val wantedLeft = (draggedLeft + dragOffsetX).toInt()
                    .coerceIn(safe.left + margin, (safe.right - width - margin).coerceAtLeast(safe.left + margin))
                val wantedTop = (draggedTop + dragOffsetY).toInt()
                    .coerceIn(safe.top + margin, (safe.bottom - height - margin).coerceAtLeast(safe.top + margin))
                translationX = (wantedLeft - draggedLeft).toFloat()
                translationY = (wantedTop - draggedTop).toFloat()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val moved = dragging
                dragging = false
                dragCandidate = false
                if (!moved) return true
                val centerX = (draggedLeft + translationX + width / 2).toInt()
                val centerY = (draggedTop + translationY + height / 2).toInt()
                translationX = 0f
                translationY = 0f
                val safe = safeArea().takeIf { it.width() > 0 && it.height() > 0 }
                    ?: Rect(0, 0, parent.width, parent.height)
                window.snapTo(
                    centerX, centerY, parent.width, parent.height,
                    safe.left, safe.top, parent.width - safe.right, parent.height - safe.bottom
                )
                applyBounds(animate = true)
                onChanged()
                return true
            }
        }
        return true
    }

    private fun chip(glyph: String, description: String, onTap: () -> Unit): View = TextView(context).apply {
        text = glyph
        textSize = 17f
        setTextColor(colors.primaryText)
        gravity = Gravity.CENTER
        contentDescription = description
        minWidth = controlWidth()
        minHeight = toolbarHeight()
        isClickable = true
        // Not focusable, like the hint-bar chips: the physical buttons are the
        // gamepad path, and a focus stop on a picture of a button is a maze.
        isFocusable = false
        setOnClickListener { onTap() }
    }

    private fun toolbarHeight() = Styler.dpInt(context, 48f)
    private fun controlWidth() = Styler.dpInt(context, 48f)
    private fun lerp(from: Int, to: Int, fraction: Float): Int =
        (from + (to - from) * fraction).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
