package com.pocketds.hub.screens.discover

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.PosterCardView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Everything one performer has been in.
 *
 * Sorted newest-first rather than by popularity, and that is a considered
 * choice. TMDB's combined credits include every talk-show appearance, and those
 * inherit the *show's* popularity — so sorting by it puts Saturday Night Live
 * and The Tonight Show above the films the person is actually known for. The hub
 * also drops roles credited as "Self", which removed 27 of 67 credits on the
 * first real actor tested.
 *
 * Availability badges are deliberately absent here: TMDB's credits carry no
 * library information, and a wrong green badge is worse than no badge.
 */
class PersonScreen(
    private val api: HubApi,
    private val personId: Int,
    private val personName: String,
    private val ringVisible: () -> Boolean
) : Screen {

    override val title: String = personName

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var colors: PocketColors
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private val adapter = CreditAdapter()

    private var host: ScreenHost? = null
    private var sort = "release"

    override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
        this.host = host
        val context = host.viewContext
        colors = Theme.colors(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.background)
        }

        heading = TextView(context).apply {
            textSize = 20f
            setTextColor(colors.primaryText)
            text = personName
            setPadding(
                Styler.dpInt(context, 14f), Styler.dpInt(context, 12f),
                Styler.dpInt(context, 14f), 0
            )
        }
        root.addView(heading)

        status = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            setPadding(Styler.dpInt(context, 14f), 0, 0, Styler.dpInt(context, 6f))
            text = "Loading…"
        }
        root.addView(status)

        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@PersonScreen.adapter
            setHasFixedSize(true)
            setItemViewCacheSize(COLUMNS * 3)
            clipToPadding = false
            clipChildren = false
            setPadding(
                Styler.dpInt(context, 8f), 0,
                Styler.dpInt(context, 8f), Styler.dpInt(context, 90f)
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        root.addView(grid)

        return root
    }

    override fun onShow() {
        if (adapter.itemCount == 0) load()
    }

    override fun onHide() {
        scope.coroutineContext.cancelChildren()
    }

    override fun onDestroyView() {
        scope.cancel()
        host = null
    }

    override fun hints(): List<ButtonHint> = listOf(
        ButtonHint.activate("Open"),
        ButtonHint(
            "Ⓧ",
            if (sort == "release") "By popularity" else "Newest first",
            PadAction.Primary
        ),
        ButtonHint.back()
    )

    override fun onPad(action: PadAction): Boolean {
        if (action == PadAction.Primary) {
            sort = if (sort == "release") "popularity" else "release"
            host?.refreshHints()
            load()
            return true
        }
        return false
    }

    private fun load() {
        status.setTextColor(colors.mutedText)
        status.text = "Loading…"
        scope.coroutineContext.cancelChildren()
        scope.launch {
            when (val result = api.person(personId, sort)) {
                is HubResult.Ok -> {
                    val person = result.value
                    heading.text = person.name.ifEmpty { personName }
                    adapter.submit(person.credits)
                    status.text = buildString {
                        append(person.credits.size).append(" credits")
                        if (person.knownFor.isNotEmpty()) append(" · ").append(person.knownFor)
                        append(if (sort == "release") " · newest first" else " · by popularity")
                        if (person.cache.hit) append(" · cached")
                    }
                    grid.post { grid.getChildAt(0)?.requestFocus() }
                }
                is HubResult.Failed -> {
                    status.setTextColor(colors.dangerText)
                    status.text = result.message
                }
            }
        }
    }

    private inner class CreditAdapter : RecyclerView.Adapter<CardHolder>() {
        private val items = mutableListOf<SearchHit>()

        fun submit(next: List<SearchHit>) {
            items.clear()
            items.addAll(next)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = PosterCardView(parent.context, colors).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply {
                    val m = Styler.dpInt(parent.context, 5f)
                    setMargins(m, m, m, m)
                }
                FocusDecorator.attach(this, ringVisible)
            }
            return CardHolder(card)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            val hit = items[position]
            val loader = (api as? HubClient)?.imageLoader
                ?: coil.ImageLoader(holder.itemView.context)
            (holder.itemView as PosterCardView).bind(hit, loader) { api.imageUrl(it) }
            holder.itemView.setOnClickListener {
                host?.push(MediaDetailScreen(api, hit.media.key, hit.media.title, ringVisible))
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class CardHolder(view: View) : RecyclerView.ViewHolder(view)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        const val COLUMNS = 4
    }
}
