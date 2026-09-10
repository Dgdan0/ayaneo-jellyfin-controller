package com.pocketds.hub.screens.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.screens.system.PadTestScreen
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.settings.ThemeSettings
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

class SettingsScreen(private val ringVisible: () -> Boolean) : Screen {
    override val title = "Settings"

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var overlay: ChoiceOverlay
    private val rows = linkedMapOf<String, SettingRow>()
    private var selected = "appearance"

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            // The host's content frame already ends above the hint bar.
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(TextView(context).apply {
                text = "Settings"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colors.primaryText)
            })
            addView(TextView(context).apply {
                text = "Appearance, notification history and controller preferences"
                textSize = 12f
                setTextColor(colors.mutedText)
                setPadding(0, dp(3), 0, dp(10))
            })
            addSetting("appearance", "Appearance") { showThemeChoices() }
            addSetting("notifications", "Notifications") {
                host.push(NotificationSettingsScreen(ringVisible))
            }
            addSetting("playback", "Playback") { showSeekChoices() }
            addSetting("offline", "Offline downloads", "Wi-Fi, charging and storage rules") {
                host.push(OfflineSettingsScreen(ringVisible))
            }
            addSetting("controller", "Controller test", "Inspect buttons, sticks and triggers") {
                host.push(PadTestScreen())
            }
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        updateDetails()
        return root
    }

    override fun onShow() = updateDetails()
    override fun onHide() {
        if (::overlay.isInitialized) overlay.dismiss()
    }
    override fun onDestroyView() {
        rows.clear()
    }

    override fun hints(): List<ButtonHint> = if (::overlay.isInitialized && overlay.isOpen) {
        listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
    } else {
        listOf(ButtonHint.activate(if (selected in setOf("controller", "notifications", "offline")) "Open" else "Change"))
    }

    override fun onPad(action: PadAction): Boolean = overlay.onPad(action)

    override fun requestInitialFocus(): Boolean = rows[selected]?.requestFocus() == true ||
        rows.values.firstOrNull()?.requestFocus() == true

    private fun LinearLayout.addSetting(
        id: String,
        label: String,
        initialDetail: String = "",
        activate: () -> Unit
    ) {
        val row = SettingRow(label, initialDetail).apply {
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, ringVisible())
                if (focused) {
                    selected = id
                    host.refreshHints()
                }
            }
            activateOnTap(activate)
        }
        FocusDecorator.attach(row, ringVisible, scale = false)
        rows[id] = row
        addView(row, LinearLayout.LayoutParams(MATCH, dp(64)).apply { bottomMargin = dp(8) })
    }

    private fun updateDetails() {
        if (!::host.isInitialized) return
        rows["appearance"]?.detail = when (ThemeSettings.getMode(host.viewContext)) {
            ThemeSettings.Mode.SYSTEM -> "Follow system"
            ThemeSettings.Mode.LIGHT -> "Light"
            ThemeSettings.Mode.DARK -> "Dark"
        }
        NotificationSettings.limits(host.viewContext).let {
            rows["notifications"]?.detail = "Sonarr ${it.sonarr} · Radarr ${it.radarr} · Bazarr ${it.bazarr}"
        }
        rows["playback"]?.detail = "Seek ${PlaybackSettings.seekSeconds(host.viewContext)} seconds"
        rows["offline"]?.detail = buildList {
            add(if (com.pocketds.hub.settings.OfflineSettings.wifiOnly(host.viewContext)) "Wi-Fi only" else "Any network")
            if (com.pocketds.hub.settings.OfflineSettings.chargingOnly(host.viewContext)) add("while charging")
            add("keep ${com.pocketds.hub.settings.OfflineSettings.minimumFreeMb(host.viewContext)} MB free")
        }.joinToString(" · ")
    }

    private fun showThemeChoices() {
        val current = ThemeSettings.getMode(host.viewContext)
        val values = listOf(
            ThemeSettings.Mode.SYSTEM to "Follow system",
            ThemeSettings.Mode.LIGHT to "Light",
            ThemeSettings.Mode.DARK to "Dark"
        )
        overlay.show(
            title = "Appearance",
            subtitle = "Choose how the whole app and service logos are displayed.",
            choices = values.map { ChoiceOverlay.Choice(it.first.name, it.second) },
            startIndex = values.indexOfFirst { it.first == current }.coerceAtLeast(0),
            onCancel = host::refreshHints
        ) { picked ->
            val mode = ThemeSettings.Mode.valueOf(picked)
            ThemeSettings.setMode(host.viewContext, mode)
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeSettings.Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeSettings.Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeSettings.Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                }
            )
            // Switching Dark -> Follow system may not recreate when Android is
            // already dark, so refresh the row and hints in the current view too.
            updateDetails()
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun showSeekChoices() {
        val current = PlaybackSettings.seekSeconds(host.viewContext)
        val values = listOf(5, 10, 15, 30)
        overlay.show(
            title = "Seek distance",
            subtitle = "Used by double-tap and the skip buttons in the player.",
            choices = values.map { ChoiceOverlay.Choice(it.toString(), "$it seconds") },
            startIndex = values.indexOf(current).coerceAtLeast(0),
            onCancel = host::refreshHints
        ) { picked ->
            PlaybackSettings.setSeekSeconds(host.viewContext, picked.toInt())
            updateDetails()
            host.refreshHints()
        }
        host.refreshHints()
    }

    private inner class SettingRow(private val label: String, initialDetail: String) : LinearLayout(host.viewContext) {
        private val detailView: TextView
        var detail: String
            get() = detailView.text.toString()
            set(value) {
                detailView.text = value
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
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(TextView(context).apply {
                    text = label
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.primaryText)
                })
                detailView = TextView(context).apply {
                    text = initialDetail
                    textSize = 11f
                    setTextColor(colors.mutedText)
                }
                addView(detailView)
            }, LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = "›"
                textSize = 25f
                setTextColor(colors.mutedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            contentDescription = "$label, $initialDetail"
        }
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
