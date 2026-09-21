package com.pocketds.hub.reader

import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.activateOnTap

/** Device acceptance entry point for the R1 shell and deterministic engines. */
class ReaderLabScreen(private val ringVisible: () -> Boolean) : Screen {
    override val title = "Reader lab"

    private lateinit var host: ScreenHost
    private lateinit var colors: PocketColors
    private val rows = linkedMapOf<ReaderProfile, TextView>()
    private var selected = ReaderProfile.BOOK

    override fun onCreateView(host: ScreenHost, container: ViewGroup): android.view.View {
        this.host = host
        colors = Theme.colors(host.viewContext)
        return FrameLayout(host.viewContext).apply {
            setBackgroundColor(colors.background)
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(16), dp(22), dp(16))
                addView(TextView(context).apply {
                    text = "Reader lab"
                    textSize = 23f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(colors.primaryText)
                })
                addView(TextView(context).apply {
                    text = "R1 uses deterministic fixtures to validate the shared shell, controller, previews and local resume before real publication parsers are added."
                    textSize = 12f
                    setTextColor(colors.mutedText)
                    setPadding(0, dp(4), 0, dp(14))
                })
                addFixture(
                    ReaderProfile.COMIC,
                    "Comic",
                    "Paged artwork · fit, thumbnails and physical left/right navigation"
                )
                addFixture(
                    ReaderProfile.MANGA,
                    "Manga",
                    "Right-to-left publication profile with the same predictable controls"
                )
                addFixture(
                    ReaderProfile.BOOK,
                    "Book",
                    "Reflowable landscape fixture · columns, themes and non-committing preview"
                )
                addFixture(
                    ReaderProfile.READ_ALONG,
                    "Read along",
                    "Text and audio shell · timeline, highlight and audio controls"
                )
            }
            addView(ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                addView(page, FrameLayout.LayoutParams(MATCH, WRAP))
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    override fun onShow() = Unit
    override fun onHide() = Unit
    override fun onDestroyView() = rows.clear()

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate("Open fixture"),
        ButtonHint.back()
    )

    override fun requestInitialFocus(): Boolean =
        rows[selected]?.requestFocus() == true || rows.values.firstOrNull()?.requestFocus() == true

    private fun LinearLayout.addFixture(profile: ReaderProfile, label: String, detail: String) {
        val row = TextView(context).apply {
            text = "$label\n$detail"
            textSize = 15f
            setTextColor(colors.primaryText)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = Styler.cardBackground(context, colors, 11f)
            contentDescription = "$label reader fixture. $detail"
            Styler.makeFocusable(this)
            FocusDecorator.attach(this, ringVisible, scale = false)
            setOnFocusChangeListener { view, focused ->
                FocusDecorator.refresh(view, focused && ringVisible())
                if (focused) selected = profile
            }
            activateOnTap { open(profile, label) }
        }
        rows[profile] = row
        addView(row, LinearLayout.LayoutParams(MATCH, dp(78)).apply { bottomMargin = dp(9) })
    }

    private fun open(profile: ReaderProfile, label: String) {
        val userId = HubSettings.userId(host.viewContext)
        val progress = ReaderLabProgressStore.load(host.viewContext, userId, profile)
        val engine = FakeReaderEngine(
            profile = profile,
            publicationId = "reader-lab-${profile.name.lowercase()}",
            title = when (profile) {
                ReaderProfile.COMIC -> "The Pocket Reader #1"
                ReaderProfile.MANGA -> "Pocket Journey · Chapter 1"
                ReaderProfile.BOOK -> "The Readable Machine"
                ReaderProfile.READ_ALONG -> "The Readaloud Journey"
            },
            startProgression = progress
        )
        host.push(ReaderScreen(engine, ringVisible))
        host.notify("Opened $label fixture")
    }

    private fun dp(value: Int) = Styler.dpInt(host.viewContext, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
