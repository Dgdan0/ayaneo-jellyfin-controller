package com.pocketds.hub.screens.downloads

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.BandwidthChange
import com.pocketds.hub.model.BandwidthState
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.JobSlot
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.ChoiceOverlay
import com.pocketds.hub.ui.DashboardParts
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.SettingsCard
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.typeRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import java.util.Locale
import kotlin.math.roundToLong

/**
 * qBittorrent's two sets of limits, by the names Activity uses: Normal speed
 * and Quiet (its "alternative" limits). The switch at the top picks which is
 * in use; each card shows its download and upload caps and edits them.
 */
class BandwidthScreen(private val api: HubApi, private val ringVisible: () -> Boolean) : Screen {
    override val title = "Speed limits"
    override val contentDomain = ContentMode.MEDIA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ScreenHost? = null
    private lateinit var colors: PocketColors
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var panel: ChoiceOverlay
    private val work = JobSlot()
    private val busy: Boolean get() = work.isBusy
    private var state: BandwidthState? = null

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(2), dp(24), 0) }
            status = DashboardParts.text(context, "Asking qBittorrent…", 12f, colors.mutedText)
            column.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, 0, 0, dp(16)) }
            column.addView(FocusScrollView(context).apply { clipToPadding = false; addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            panel = ChoiceOverlay(context, colors, ringVisible, sidePanel = true)
            addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onShow() = load()
    override fun onHide() { scope.coroutineContext.cancelChildren(); panel.dismiss() }
    override fun onDestroyView() { scope.cancel(); host = null }
    override fun hints() = listOf(ButtonHint.activate(if (busy) "Saving…" else "Choose"), ButtonHint.back(), ButtonHint.refresh())
    override fun requestInitialFocus(): Boolean = body.getFocusables(View.FOCUS_FORWARD).firstOrNull()?.requestFocus() ?: false
    override fun onPad(action: PadAction): Boolean {
        if (panel.onPad(action)) return true
        if (action == PadAction.Refresh) { if (!busy) load(); return true }
        return false
    }

    private fun load() {
        if (busy) return
        work.launch(scope) {
            when (val response = api.bandwidth()) {
                is HubResult.Ok -> render(response.value)
                is HubResult.Failed -> {
                    status.text = response.message
                    status.setTextColor(colors.dangerText)
                    body.removeAllViews()
                    body.addView(PillButton.create(checkNotNull(host).viewContext, colors, "Try again", AppIcon.REFRESH, glass = true).apply {
                        FocusDecorator.attach(this, ringVisible, scale = false)
                        activateOnTap { load() }
                    })
                }
            }
        }
    }

    private fun render(value: BandwidthState) {
        val context = checkNotNull(host).viewContext
        val focused = body.findFocus()?.tag
        state = value
        body.removeAllViews()
        val quiet = value.mode == "alternative"
        status.setTextColor(colors.mutedText)
        status.text = "qBittorrent · these limits apply to every transfer"

        if (value.canControl && value.modeSwitchSupported) {
            body.addView(card("In use", "").apply {
                body(BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.ACCENT).apply {
                    tag = "mode"
                    heightDp = 38f; textSp = 13f; padXDp = 16f
                    setOptions(listOf(BlobSegmentedView.Option(NORMAL, "Normal speed"), BlobSegmentedView.Option(QUIET, "Quiet")),
                        if (quiet) QUIET else NORMAL)
                    onPick = { id -> if ((id == QUIET) != quiet) save(BandwidthChange(mode = if (id == QUIET) "alternative" else "normal")) }
                }, 10f)
                hint("Quiet uses qBittorrent's alternative limits: slower downloads that leave room for everything else.")
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }

        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            addView(limits("normal", "Normal speed", value.downloadBps, value.uploadBps, inUse = !quiet, value), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(limits("alternative", "Quiet", value.alternativeDownloadBps, value.alternativeUploadBps, inUse = quiet, value),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
        })

        val notes = listOfNotNull(
            "qBittorrent's own schedule is on, so it may switch between these by itself.".takeIf { value.schedulerEnabled },
            if (value.queueingEnabled) "Each transfer's actions can move it up or down the queue."
            else "Queueing is off in qBittorrent, so transfers have no order to change.",
            "This token can read these limits but not change them.".takeIf { !value.canControl },
            "Switching between them here needs qBittorrent 5 or later.".takeIf { value.canControl && !value.modeSwitchSupported }
        )
        notes.forEach { note ->
            body.addView(DashboardParts.text(context, note, 11.5f, colors.mutedText).apply { setPadding(dp(4), 0, dp(4), 0) },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        body.post {
            if (focused?.let { body.findViewWithTag<View>(it) }?.requestFocus() != true) requestInitialFocus()
            host?.refreshHints()
        }
    }

    /** One set of limits: its name, "In use" when it is, the two caps, and Edit. */
    private fun limits(mode: String, name: String, down: Long, up: Long, inUse: Boolean, value: BandwidthState): SettingsCard =
        card(name, if (inUse) "In use" else "").apply {
            if (inUse) trailing("In use", colors.accent)
            body(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(cap("Download", down), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(cap("Upload", up), LinearLayout.LayoutParams(0, WRAP, 1f))
            }, 10f, fill = true)
            if (value.canControl) {
                val editButton = PillButton.create(context, colors, "Edit limits", heightDp = 34f, glass = true).apply {
                    tag = "edit:$mode"
                    FocusDecorator.attach(this, ringVisible, scale = false)
                    activateOnTap { if (!busy) edit(mode, name, down, up) }
                }
                body(editButton, 10f)
                // The pill, not its ring, lines up with the words above.
                editButton.layoutParams = (editButton.layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = -dp(PillButton.RING_DP.toInt())
                }
            }
        }

    private fun cap(label: String, bps: Long): LinearLayout = LinearLayout(checkNotNull(host).viewContext).apply {
        orientation = LinearLayout.VERTICAL
        addView(DashboardParts.text(context, label.uppercase(), 10.5f, colors.mutedText, 700).apply { letterSpacing = 0.1f })
        addView(TextView(context).apply {
            text = (if (label == "Download") "↓ " else "↑ ") + rate(bps)
            typeRole(Type.Role.HEADING, 18f)
            setTextColor(colors.primaryText)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    private fun edit(mode: String, name: String, down: Long, up: Long) {
        if (busy) return
        panel.resetBody()
        panel.open(name, "In KiB/s, for every transfer. 0 means no limit.") { host?.refreshHints() }
        fun field(title: String, initial: Long): EditText {
            panel.section(title)
            return EditText(checkNotNull(host).viewContext).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(String.format(Locale.US, "%.6f", initial / 1024.0).trimEnd('0').trimEnd('.'))
                setTextColor(colors.primaryText)
                textSize = 16f
                minHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                background = ThemeGradientDrawable.rounded(dp(12).toFloat(), colors.cardSurface)
                contentDescription = "$title limit in KiB per second"
                Styler.makeFocusable(this)
                panel.body.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(6) })
            }
        }
        val download = field("Download", down)
        val upload = field("Upload", up)
        val error = DashboardParts.text(checkNotNull(host).viewContext, "", 12f, colors.dangerText)
        panel.body.addView(error)
        panel.choice("Apply", "Keeps ${if (state?.mode == "alternative") "Quiet" else "Normal speed"} in use") {
            fun parse(field: EditText): Long? = field.text.toString().toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0 && it <= 1_048_576 }?.let { (it * 1024).roundToLong() }
            val d = parse(download)
            val u = parse(upload)
            if (d == null || u == null) error.text = "Enter a number from 0 to 1,048,576."
            else { panel.dismiss(); save(BandwidthChange(limitsFor = mode, downloadBps = d, uploadBps = u)) }
        }
        panel.focusBody(download)
        host?.refreshHints()
    }

    private fun save(change: BandwidthChange) {
        if (busy) return
        status.text = "Saving and checking with qBittorrent…"
        status.setTextColor(colors.mutedText)
        work.launch(scope, onIdle = { host?.refreshHints() }) {
            host?.refreshHints()
            when (val result = api.setBandwidth(change)) {
                is HubResult.Ok -> { render(result.value); host?.notify("Speed limits saved") }
                is HubResult.Failed -> { status.text = result.message; status.setTextColor(colors.dangerText); host?.notify(result.message) }
            }
        }
    }

    private fun card(title: String, trailing: String): SettingsCard = SettingsCard(checkNotNull(host).viewContext, colors).apply {
        title(title, trailing)
        titleView?.typeRole(Type.Role.HEADING, 15f)
    }

    /** "Unlimited", "10 KiB/s", "1,536 KiB/s". */
    private fun rate(value: Long) = if (value <= 0) "Unlimited"
        else String.format(Locale.US, "%,.1f", value / 1024.0).removeSuffix(".0") + " KiB/s"

    private fun dp(n: Int) = Styler.dpInt(checkNotNull(host).viewContext, n.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val NORMAL = "normal"
        const val QUIET = "quiet"
    }
}
