package com.pocketds.hub.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.model.HostDisk
import com.pocketds.hub.screens.downloads.ActivityDashboard
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.ProgressLine.showFraction

/**
 * What the dashboards are made of -- Activity, Server monitor, Services and
 * Notifications: a status dot, a disk with its bar, a figure on a card, and a
 * focusable row. Activity drew these first; the other screens were built
 * before the redesign and each had its own boxes and colours.
 */
object DashboardParts {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /** The colour for a service or container state: up, needs a look, or down. */
    fun stateColor(colors: PocketColors, state: String): Int = when (state.lowercase()) {
        "up", "running", "healthy" -> colors.badgeAvailable
        "misconfigured", "restarting", "paused", "created", "degraded" -> colors.badgePending
        "disabled", "exited" -> colors.mutedText
        else -> colors.dangerText
    }

    /** A small round status mark before a name. */
    fun dot(context: Context, color: Int): View = View(context).apply {
        background = ThemeGradientDrawable.oval(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(Styler.dpInt(context, 7f), Styler.dpInt(context, 7f)).apply {
            marginEnd = Styler.dpInt(context, 9f)
        }
    }

    /** "C:", how much is free, and how full it is; red once it is nearly full. */
    fun disk(context: Context, colors: PocketColors, disk: HostDisk): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val low = ActivityDashboard.lowSpace(disk)
        val used = (disk.totalBytes - disk.availableBytes).coerceAtLeast(0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Styler.dpInt(context, 6f), 0, Styler.dpInt(context, 4f))
            addView(text(context, disk.name.trimEnd('\\', '/'), 12f, colors.primaryText, 600), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(text(context, "${Fmt.bytes(disk.availableBytes)} free of ${Fmt.bytes(disk.totalBytes)}", 11f,
                if (low) colors.dangerText else colors.mutedText))
        })
        addView(ProgressLine.create(context, colors, if (low) colors.dangerText else colors.primaryText).apply {
            showFraction(if (disk.totalBytes > 0) used.toDouble() / disk.totalBytes else 0.0)
        }, LinearLayout.LayoutParams(MATCH, Styler.dpInt(context, 7f)))
        contentDescription = "${disk.name}, ${Fmt.bytes(disk.availableBytes)} free of ${Fmt.bytes(disk.totalBytes)}"
    }

    /**
     * A figure on its own card: a quiet label, the value large, then a line or
     * a bar. CPU, memory, uptime.
     */
    fun stat(
        context: Context,
        colors: PocketColors,
        label: String,
        value: String,
        detail: String = "",
        fraction: Double? = null,
        warning: Boolean = false
    ): SettingsCard = SettingsCard(context, colors).apply {
        addView(text(context, label.uppercase(), 10.5f, colors.mutedText, 700).apply { letterSpacing = 0.1f })
        addView(TextView(context).apply {
            text = value
            typeRole(Type.Role.HEADING, 20f)
            setTextColor(if (warning) colors.dangerText else colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Styler.dpInt(context, 6f) })
        if (detail.isNotBlank()) addView(text(context, detail, 11f, colors.mutedText),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Styler.dpInt(context, 3f) })
        fraction?.let {
            addView(ProgressLine.create(context, colors, if (warning) colors.dangerText else colors.accent).apply { showFraction(it) },
                LinearLayout.LayoutParams(MATCH, Styler.dpInt(context, 6f)).apply { topMargin = Styler.dpInt(context, 9f) })
        }
        contentDescription = listOf(label, value, detail).filter(String::isNotBlank).joinToString(", ")
    }

    /**
     * A row inside a card that the pad can land on: a quiet fill and the ring
     * while focused. [onActivate] null makes a row you can read and scroll
     * past but that does nothing on A.
     */
    fun row(context: Context, colors: PocketColors, ringVisible: () -> Boolean, id: String, description: String,
            onActivate: (() -> Unit)? = null): LinearLayout = LinearLayout(context).apply {
        tag = id
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Styler.dpInt(context, 6f), Styler.dpInt(context, 5f), Styler.dpInt(context, 6f), Styler.dpInt(context, 6f))
        background = Styler.selectionBackground(context, colors, selected = false, cornerDp = 8f)
        contentDescription = description
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        onActivate?.let { activateOnTap(it) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = Styler.dpInt(context, 2f) }
    }

    fun text(context: Context, value: String, size: Float, color: Int, weight: Int = 400): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (weight != 400) textWeight(weight)
    }
}
