package com.pocketds.hub.reader

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.pocketds.hub.ui.SidePanelView
import com.pocketds.hub.ui.Styler

/**
 * Shows and hides a reader's bars, and fits its page round them. View
 * transforms preserve EPUB pagination and the reader's measured viewport.
 *
 * The owner chose (#16, X7, 2026-10-05): a book's page [makesRoom], shrinking
 * with the menu round it, while a comic's page keeps its size and the bars
 * float over it ([makesRoom] false), its zoom and its step untouched.
 */
class ReaderPagePreviewController(
    private val root: View,
    private val page: View,
    private val topBar: View,
    private val bottomBar: View,
    private val panels: List<SidePanelView>,
    private val animate: Boolean = true,
    private val makesRoom: Boolean = true,
    /**
     * A book's page-info corners (#42): they lie over the page, so they move and shrink with it, and the
     * bars replace them (the bars show their own details). With a panel open they stay, so the sheet that
     * changes them shows the change live. A comic has none.
     */
    private val corners: View? = null
) {
    private var controlsVisible = false
    private var target: ReaderPageTransform? = null
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> update(false) }

    init {
        page.pivotX = 0f
        page.pivotY = 0f
        root.addOnLayoutChangeListener(layoutListener)
        panels.forEach { panel ->
            // The page preview is useful for checking typography and colours live.
            panel.setBackgroundColor(Color.TRANSPARENT)
            panel.onPanelGeometryChanged = { update(animate) }
        }
    }

    fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        update(animate)
    }

    fun refresh() = update(animate)

    private fun update(animated: Boolean) {
        val active = panels.filter { it.isOpen }
        val showBars = controlsVisible && active.isEmpty()
        topBar.visibility = if (showBars) View.VISIBLE else View.GONE
        bottomBar.visibility = if (showBars) View.VISIBLE else View.GONE
        corners?.visibility = if (showBars) View.GONE else View.VISIBLE
        val margin = if (controlsVisible || active.isNotEmpty()) Styler.dpInt(root.context, 12f) else 0
        val result = if (!makesRoom) ReaderPageTransform(1f, 0f, 0f) else ReaderPagePreview.fit(
            root.width, root.height,
            top = if (showBars) topBar.layoutParams.height.coerceAtLeast(0) else 0,
            bottom = if (showBars) bottomBar.layoutParams.height.coerceAtLeast(0) else 0,
            right = active.minOfOrNull { it.panelStartX }?.let { root.width - it } ?: 0,
            margin = margin
        )
        if (target == result) return
        target = result
        page.animate().cancel()
        if (animated && page.isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            page.animate().scaleX(result.scale).scaleY(result.scale)
                .translationX(result.x).translationY(result.y)
                .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        } else {
            page.scaleX = result.scale; page.scaleY = result.scale
            page.translationX = result.x; page.translationY = result.y
        }
    }

    fun dispose() {
        page.animate().cancel()
        root.removeOnLayoutChangeListener(layoutListener)
        panels.forEach { it.onPanelGeometryChanged = null }
    }
}
