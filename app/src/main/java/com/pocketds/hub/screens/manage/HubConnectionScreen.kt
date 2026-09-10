package com.pocketds.hub.screens.manage

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubEndpoints
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lets the owner change the public hub address without ADB or retyping its token. */
class HubConnectionScreen(
    private val api: HubApi,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title = "Hub connection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private lateinit var address: EditText
    private lateinit var save: TextView
    private lateinit var status: TextView
    private var testJob: Job? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)

        val page = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(colors.background)
        }

        val card = LinearLayout(host.viewContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = Styler.cardBackground(context, colors, cornerDp = 16f)
        }
        page.addView(card, LinearLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(host.viewContext).apply {
            text = "Ayaneo Hub address"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.primaryText)
        })

        card.addView(TextView(host.viewContext).apply {
            text = "Use the complete HTTPS address, including a custom port when required. " +
                "The existing access token is kept."
            textSize = 13f
            setTextColor(colors.mutedText)
            setPadding(0, dp(5), 0, dp(16))
        })

        address = EditText(host.viewContext).apply {
            setText(HubSettings.baseUrl(context))
            textSize = 16f
            setTextColor(colors.primaryText)
            setHintTextColor(colors.mutedText)
            hint = "https://example.duckdns.org:55886"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setSelectAllOnFocus(false)
            setPadding(dp(15), dp(13), dp(15), dp(13))
            background = fieldBackground()
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    testAndSave()
                    true
                } else {
                    false
                }
            }
        }
        card.addView(address, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        save = TextView(host.viewContext).apply {
            text = "Save and test"
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.primaryText)
            background = Styler.chipBackground(context, colors)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            minimumHeight = dp(52)
            contentDescription = "Save Hub address and test connection"
            activateOnTap(::testAndSave)
            FocusDecorator.attach(this, ringVisible, scale = false)
        }
        card.addView(save, LinearLayout.LayoutParams(dp(190), dp(52)).apply {
            gravity = Gravity.END
            topMargin = dp(14)
        })

        status = TextView(host.viewContext).apply {
            text = ""
            textSize = 13f
            setTextColor(colors.mutedText)
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(status)

        return page
    }

    override fun onShow() {
        address.setText(HubSettings.baseUrl(host.viewContext))
    }

    override fun onHide() {
        testJob?.cancel()
        testJob = null
    }

    override fun onDestroyView() {
        testJob?.cancel()
        scope.cancel()
    }

    override fun requestInitialFocus(): Boolean = address.requestFocus()

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate("Edit / save"),
        ButtonHint.back()
    )

    private fun testAndSave() {
        if (testJob?.isActive == true) return
        val normalized = HubEndpoints.normaliseBase(address.text.toString())
        if (!isAllowedAddress(normalized)) {
            status.setTextColor(colors.dangerText)
            status.text = "Enter a complete HTTPS address"
            address.requestFocus()
            return
        }

        HubSettings.save(host.viewContext, normalized, HubSettings.token(host.viewContext))
        address.setText(normalized)
        address.setSelection(normalized.length)
        status.setTextColor(colors.mutedText)
        status.text = "Testing connection…"
        save.isEnabled = false

        testJob = scope.launch {
            when (val result = api.health()) {
                is HubResult.Ok -> {
                    status.setTextColor(colors.badgeAvailable)
                    status.text = "Connected. This address is now saved."
                    host.notify("Hub address saved · connected")
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = "Saved, but the test failed: ${result.message}"
                }
            }
            save.isEnabled = true
            testJob = null
        }
    }

    private fun isAllowedAddress(value: String): Boolean {
        if (value.startsWith("https://") && value.length > "https://".length) return true
        return value.startsWith("http://127.0.0.1") || value.startsWith("http://localhost")
    }

    private fun fieldBackground() = GradientDrawable().apply {
        cornerRadius = Styler.dp(host.viewContext, 10f)
        setColor(this@HubConnectionScreen.colors.stripBackground)
        setStroke(dp(2), this@HubConnectionScreen.colors.focusRing)
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())
}
