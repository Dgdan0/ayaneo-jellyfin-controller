package com.pocketds.hub.screens.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

class NotificationSettingsScreen(private val ringVisible: () -> Boolean) : Screen {
    override val title = "Notification settings"
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var overlay: ChoiceOverlay
    private val rows = linkedMapOf<String, LimitRow>()
    private var selected = "sonarr"

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(TextView(context).apply {
                text = "Notifications"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.primaryText)
            })
            addView(TextView(context).apply {
                text = "Choose how many recent entries each service loads. Seen state is kept separately."
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(0, dp(3), 0, dp(10))
            })
            listOf("sonarr" to "Sonarr", "radarr" to "Radarr", "bazarr" to "Bazarr").forEach { (id, label) ->
                val row = LimitRow(label).apply {
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    setOnFocusChangeListener { view, focused ->
                        FocusDecorator.refresh(view, ringVisible())
                        if (focused) {
                            selected = id
                            host.refreshHints()
                        }
                    }
                    activateOnTap { showChoices(id, label) }
                }
                rows[id] = row
                addView(row, LinearLayout.LayoutParams(MATCH, dp(64)).apply { bottomMargin = dp(8) })
            }
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        refreshValues()
        return root
    }

    override fun onShow() = refreshValues()
    override fun onHide() {
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() {
        rows.clear()
    }
    override fun hints(): List<ButtonHint> = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else listOf(ButtonHint.activate("Change"), ButtonHint.back())
    override fun onPad(action: PadAction): Boolean = overlay.onPad(action)
    override fun requestInitialFocus(): Boolean = rows[selected]?.requestFocus() == true

    private fun showChoices(service: String, label: String) {
        val current = currentValue(service)
        overlay.show(
            title = "$label history",
            subtitle = "More entries use a little more data when Notifications refreshes.",
            choices = NotificationSettings.choices.map { ChoiceOverlay.Choice(it.toString(), "$it entries") },
            startIndex = NotificationSettings.choices.indexOf(current).coerceAtLeast(0),
            onCancel = host::refreshHints
        ) { picked ->
            NotificationSettings.setLimit(host.viewContext, service, picked.toInt())
            refreshValues()
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun refreshValues() {
        if (!::host.isInitialized) return
        val limits = NotificationSettings.limits(host.viewContext)
        rows["sonarr"]?.value = "${limits.sonarr} entries"
        rows["radarr"]?.value = "${limits.radarr} entries"
        rows["bazarr"]?.value = "${limits.bazarr} entries"
    }

    private fun currentValue(service: String): Int = NotificationSettings.limits(host.viewContext).let {
        when (service) { "sonarr" -> it.sonarr; "radarr" -> it.radarr; else -> it.bazarr }
    }

    private inner class LimitRow(private val label: String) : LinearLayout(host.viewContext) {
        private val valueView: TextView
        var value: String
            get() = valueView.text.toString()
            set(value) {
                valueView.text = value
                contentDescription = "$label, $value"
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = Styler.cardBackground(context, colors, 11f)
            setPadding(dp(16), dp(8), dp(14), dp(8))
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.primaryText)
            }, LayoutParams(0, WRAP, 1f))
            valueView = TextView(context).apply {
                textSize = 12f
                setTextColor(colors.mutedText)
            }
            addView(valueView)
            addView(TextView(context).apply {
                text = "  ›"
                textSize = 24f
                setTextColor(colors.mutedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
