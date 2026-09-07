package com.pocketds.hub.screens

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme

/**
 * A grid of nothing, so the shell can be driven before there is anything behind
 * it.
 *
 * Phase A1 deliberately ships no network code at all: every hard input problem
 * -- focus that survives recycling, repeat that does not run away, a D-pad with
 * no auto-repeat, a trackpad and a gamepad in the same sitting -- is solved and
 * *felt* here, against data that cannot be slow or wrong. Phase A3 then swaps
 * the adapter for real items and inherits all of it.
 */
class PlaceholderScreen(
    override val title: String,
    private val itemCount: Int,
    private val columns: Int,
    private val ringVisible: () -> Boolean
) : Screen {

    private var recycler: RecyclerView? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        val context = host.viewContext
        val colors = Theme.colors(context)

        val list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, columns)
            adapter = CardAdapter(colors, itemCount, columns, ringVisible) { index ->
                host.notify("$title item $index")
            }
            setHasFixedSize(true)
            // Recycling the focused view loses focus to the void, and the next
            // D-pad press then does nothing. Holding a few extra rows either side
            // is the cheap half of the fix.
            setItemViewCacheSize(columns * 3)
            // onFocusSearchFailed scrolls the minimum needed, which parks the
            // selection flush against the bottom edge with no context below it.
            clipToPadding = false
            setPadding(
                Styler.dpInt(context, 12f), Styler.dpInt(context, 12f),
                Styler.dpInt(context, 12f), Styler.dpInt(context, 96f)
            )
            // Or the focus ring and the 1.08 scale get clipped by the neighbours.
            clipChildren = false
            setBackgroundColor(colors.background)
        }
        recycler = list
        return list
    }

    override fun onShow() {
        DebugLog.log("nav", "show $title")
        recycler?.post { recycler?.getChildAt(0)?.requestFocus() }
    }

    override fun onHide() {
        DebugLog.log("nav", "hide $title")
    }

    override fun onDestroyView() {
        recycler = null
    }

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate("Open"),
        ButtonHint.primary("Request"),
        ButtonHint.secondary("Details"),
        ButtonHint.back()
    )

    override fun onPad(action: PadAction): Boolean = false

    private class CardAdapter(
        private val colors: PocketColors,
        private val count: Int,
        private val columns: Int,
        private val ringVisible: () -> Boolean,
        private val onActivate: (Int) -> Unit
    ) : RecyclerView.Adapter<CardHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val context = parent.context
            val card = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                setTextColor(colors.primaryText)
                background = Styler.cardBackground(context, colors)
                Styler.makeFocusable(this)
                isClickable = true
                minHeight = Styler.dpInt(context, 120f)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = Styler.dpInt(context, 6f)
                    setMargins(m, m, m, m)
                }
                // A single-column list is full-width already: see FocusDecorator.
                FocusDecorator.attach(this, ringVisible, scale = columns > 1)
            }
            return CardHolder(card)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            (holder.itemView as TextView).text = "${position + 1}"
            holder.itemView.setOnClickListener { onActivate(position) }
        }

        override fun getItemCount(): Int = count
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)
}
