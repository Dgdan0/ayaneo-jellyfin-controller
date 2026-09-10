package com.pocketds.hub.screens.settings

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
import com.pocketds.hub.settings.OfflineSettings
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

class OfflineSettingsScreen(private val ringVisible: () -> Boolean) : Screen {
    override val title = "Offline settings"
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var overlay: ChoiceOverlay
    private lateinit var wifi: TextView
    private lateinit var charging: TextView
    private lateinit var storage: TextView
    private lateinit var reserve: TextView
    private lateinit var retries: TextView

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host; colors = Theme.colors(host.viewContext)
        val root = FrameLayout(host.viewContext).apply { setBackgroundColor(colors.background) }
        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(TextView(context).apply { text = "Offline downloads"; textSize = 22f; setTextColor(colors.primaryText) })
            addView(TextView(context).apply {
                text = "Control when original media files may be transferred to this device."
                textSize = 12f; setTextColor(colors.mutedText); setPadding(0, dp(3), 0, dp(10))
            })
            wifi = row("Wi-Fi only") { chooseBoolean("Wi-Fi only", OfflineSettings.wifiOnly(context), OfflineSettings::setWifiOnly) }
            charging = row("Only while charging") { chooseBoolean("Only while charging", OfflineSettings.chargingOnly(context), OfflineSettings::setChargingOnly) }
            storage = row("Download location") { chooseStorage() }
            reserve = row("Keep free space") { chooseReserve() }
            retries = row("Automatic retries") { chooseRetries() }
            addView(wifi); addView(charging); addView(storage); addView(reserve); addView(retries)
            addView(TextView(context).apply {
                text = "New downloads use the selected location. Existing files stay where they are."
                textSize = 11f; setTextColor(colors.mutedText); setPadding(dp(4), dp(6), 0, 0)
            })
        }
        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = ChoiceOverlay(host.viewContext, colors, ringVisible)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        update(); return root
    }

    override fun onShow() = update()
    override fun onHide() { if (::overlay.isInitialized) overlay.dismiss() }
    override fun onDestroyView() = Unit
    override fun requestInitialFocus() = wifi.requestFocus()
    override fun hints() = if (overlay.isOpen) listOf(ButtonHint.activate("Choose"), ButtonHint.back("Cancel"))
        else listOf(ButtonHint.activate("Change"), ButtonHint.back())
    override fun onPad(action: PadAction) = overlay.onPad(action)

    private fun row(label: String, action: () -> Unit) = TextView(host.viewContext).apply {
        textSize = 14f; gravity = Gravity.CENTER_VERTICAL; setTextColor(colors.primaryText)
        background = Styler.cardBackground(context, colors); setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(50)).apply { bottomMargin = dp(6) }
        Styler.makeFocusable(this); FocusDecorator.attach(this, ringVisible, scale = false)
        setOnFocusChangeListener { view, _ -> FocusDecorator.refresh(view, ringVisible()); host.refreshHints() }
        activateOnTap(action); tag = label
    }

    private fun update() {
        if (!::wifi.isInitialized) return
        wifi.text = "Wi-Fi only                                      ${yesNo(OfflineSettings.wifiOnly(host.viewContext))}"
        charging.text = "Only while charging                        ${yesNo(OfflineSettings.chargingOnly(host.viewContext))}"
        val selectedStorage = OfflineSettings.selectedStorage(host.viewContext)
        storage.text = "Download location                          " +
            (selectedStorage?.let { "${it.label} · ${fileSize(it.availableBytes)} free" } ?: "Unavailable")
        reserve.text = "Keep free space                                ${OfflineSettings.minimumFreeMb(host.viewContext)} MB"
        retries.text = "Automatic retries                            ${OfflineSettings.maxRetries(host.viewContext)}"
    }

    private fun yesNo(value: Boolean) = if (value) "On" else "Off"
    private fun chooseBoolean(title: String, current: Boolean, setter: (android.content.Context, Boolean) -> Unit) {
        overlay.show(title, "Queued downloads wait automatically until this condition is met.",
            listOf(ChoiceOverlay.Choice("on", "On"), ChoiceOverlay.Choice("off", "Off")),
            startIndex = if (current) 0 else 1, onCancel = host::refreshHints
        ) { setter(host.viewContext, it == "on"); update(); host.refreshHints() }
        host.refreshHints()
    }

    private fun chooseStorage() {
        val locations = OfflineSettings.storageLocations(host.viewContext)
        if (locations.isEmpty()) {
            host.notify("No writable download location is available")
            return
        }
        val selected = OfflineSettings.selectedStorage(host.viewContext)?.key
        overlay.show(
            "Download location",
            "This applies to new downloads. Existing files are not moved.",
            locations.map { location ->
                ChoiceOverlay.Choice(
                    location.key,
                    location.label,
                    "${fileSize(location.availableBytes)} free of ${fileSize(location.totalBytes)}" +
                        if (location.removable) " · removable" else ""
                )
            },
            startIndex = locations.indexOfFirst { it.key == selected }.coerceAtLeast(0),
            onCancel = host::refreshHints
        ) { key ->
            OfflineSettings.setSelectedStorage(host.viewContext, key)
            update()
            host.refreshHints()
        }
        host.refreshHints()
    }

    private fun chooseReserve() {
        val values = listOf(256, 512, 1024, 2048, 4096)
        val current = OfflineSettings.minimumFreeMb(host.viewContext)
        overlay.show("Reserved free space", "A download waits before consuming this final amount of storage.",
            values.map { ChoiceOverlay.Choice(it.toString(), if (it >= 1024) "${it / 1024} GB" else "$it MB") },
            startIndex = values.indexOf(current).coerceAtLeast(0), onCancel = host::refreshHints
        ) { OfflineSettings.setMinimumFreeMb(host.viewContext, it.toInt()); update(); host.refreshHints() }
        host.refreshHints()
    }

    private fun chooseRetries() {
        val values = listOf(0, 1, 3, 5, 10, 20)
        val current = OfflineSettings.maxRetries(host.viewContext)
        overlay.show("Automatic retries", "Network interruptions resume from the last stored byte.",
            values.map { ChoiceOverlay.Choice(it.toString(), if (it == 0) "Do not retry automatically" else "$it retries") },
            startIndex = values.indexOf(current).coerceAtLeast(0), onCancel = host::refreshHints
        ) { OfflineSettings.setMaxRetries(host.viewContext, it.toInt()); update(); host.refreshHints() }
        host.refreshHints()
    }

    private fun fileSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
    private companion object { const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT }
}
