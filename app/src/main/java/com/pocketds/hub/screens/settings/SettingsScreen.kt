package com.pocketds.hub.screens.settings

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.reader.ReaderLabScreen
import com.pocketds.hub.screens.system.PadTestScreen
import com.pocketds.hub.settings.NotificationSettings
import com.pocketds.hub.settings.PlaybackSettings
import com.pocketds.hub.settings.ThemeSettings
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.playback.CastTransferPolicy
import com.pocketds.hub.ui.UtilityRowView
import com.pocketds.hub.ui.AppIcon
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
    private val rows = linkedMapOf<String, UtilityRowView>()
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
            addGroup("General")
            addSetting("appearance", "Appearance") { host.push(AppearanceScreen(ringVisible)) }
            addSetting("notifications", "Notifications") {
                host.push(NotificationSettingsScreen(ringVisible))
            }
            addGroup("Playback & storage")
            addSetting("playback", "Playback") { showSeekChoices() }
            addSetting("cast", "Public Hub address", "Used for TV playback and offline downloads") { editCastAddress() }
            addSetting("offline", "Offline downloads", "Wi-Fi, charging and storage rules") {
                host.push(OfflineSettingsScreen(ringVisible))
            }
            addGroup("Tools")
            addSetting("controller", "Controller test", "Inspect buttons, sticks and triggers") {
                host.push(PadTestScreen())
            }
            addSetting("reader", "Reader lab", "Test comic, manga, book and read-along controls") {
                host.push(ReaderLabScreen(ringVisible))
            }
            addSetting("dictionary", "Offline dictionary", "Open English WordNet 2025 · CC BY 4.0") {
                this@SettingsScreen.overlay.show("Offline dictionary", "Available without an internet connection", listOf(
                    ChoiceOverlay.Choice("source", "Open English WordNet 2025", "https://en-word.net/downloads/"),
                    ChoiceOverlay.Choice("license", "Creative Commons Attribution 4.0", "https://creativecommons.org/licenses/by/4.0/"),
                    ChoiceOverlay.Choice("changes", "App index", "Converted to a local headword and definition database")
                )) { }
            }
        }
        root.addView(ScrollView(host.viewContext).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible, sidePanel = true)
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
        listOf(ButtonHint.activate("Open"))
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
        val row = UtilityRowView(host.viewContext, colors, label, initialDetail,
            when(id){"appearance"->AppIcon.APPEARANCE;"playback"->AppIcon.TV;"reader"->AppIcon.BOOK;else->AppIcon.SETTINGS}).apply {
            when(id) {
                "notifications"->setIconResource(com.pocketds.hub.R.drawable.ic_nav_notifications)
                "offline"->setIconResource(com.pocketds.hub.R.drawable.ic_nav_offline)
                "controller"->setIconResource(com.pocketds.hub.R.drawable.ic_nav_pad)
            }
            FocusDecorator.attach(this, ringVisible, scale = false)
            FocusDecorator.listen(this, ringVisible) { view, focused ->
                if (focused) {
                    selected = id
                    host.refreshHints()
                }
            }
            activateOnTap(activate)
        }
        rows[id] = row
        addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) })
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
        rows["cast"]?.detail = HubSettings.publicBaseUrl(host.viewContext).ifEmpty { "Uses private Hub address" }
        rows["offline"]?.detail = buildList {
            add(if (com.pocketds.hub.settings.OfflineSettings.wifiOnly(host.viewContext)) "Wi-Fi only" else "Any network")
            if (com.pocketds.hub.settings.OfflineSettings.chargingOnly(host.viewContext)) add("while charging")
            add("keep ${com.pocketds.hub.settings.OfflineSettings.minimumFreeMb(host.viewContext)} MB free")
        }.joinToString(" · ")
    }

    private fun showSeekChoices() {
        val current = PlaybackSettings.seekSeconds(host.viewContext)
        val values = listOf(5, 10, 15, 30)
        overlay.show(
            title = "Seek distance",
            subtitle = "Used by double-tap and the skip buttons in the player.",
            choices = values.map { ChoiceOverlay.Choice(it.toString(), "$it seconds", selected = it == current) },
            startIndex = values.indexOf(current).coerceAtLeast(0),
            onCancel = host::refreshHints
        ) { picked ->
            PlaybackSettings.setSeekSeconds(host.viewContext, picked.toInt())
            updateDetails()
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun editCastAddress() {
        val field = EditText(host.viewContext).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(HubSettings.publicBaseUrl(host.viewContext))
            selectAll()
            contentDescription = "Public HTTPS Hub address for TV playback and offline downloads"
        }
        AlertDialog.Builder(host.viewContext)
            .setTitle("Public Hub address")
            .setMessage("Chromecast and offline downloads use this HTTPS address. Downloads fall back to the private Hub address if it is unavailable.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val entered = field.text.toString().trim()
                if (CastTransferPolicy.receiverUrl(entered, "/v1/cast/check/stream") == null) {
                    host.notify("Enter a public HTTPS Hub address")
                } else {
                    HubSettings.setCastBaseUrl(host.viewContext, entered)
                    updateDetails()
                    host.notify("Public Hub address saved")
                }
            }
            .show()
    }

    private fun LinearLayout.addGroup(label: String) {
        addView(TextView(context).apply {
            text=label;textSize=12f;setTextColor(colors.mutedText);setPadding(dp(12),dp(16),dp(12),dp(4))
        })
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
