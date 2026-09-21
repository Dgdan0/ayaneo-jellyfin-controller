package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingSeriesPreview
import com.pocketds.hub.model.ReadingSeriesPreviewBook
import com.pocketds.hub.screens.discover.ReadingSeriesSelectionModel

/** Poster-backed, controller-first chooser for a verified ebook-series roster. */
class ReadingSeriesSelectionOverlay(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val imageLoader: ImageLoader,
    private val imageUrl: (String) -> String
) : FrameLayout(context) {
    private val card: LinearLayout
    private val authorImage: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val countView: TextView
    private val scroller: HorizontalScrollView
    private val bookRow: LinearLayout
    private val actionRow: LinearLayout

    private var preview: ReadingSeriesPreview? = null
    private var model: ReadingSeriesSelectionModel? = null
    private var onCancel: (() -> Unit)? = null
    private var onSubmit: ((List<String>) -> Unit)? = null

    val isOpen: Boolean get() = visibility == View.VISIBLE

    init {
        setBackgroundColor(Color.argb(205, 0, 0, 0))
        isClickable = true
        visibility = View.GONE
        setOnClickListener { cancel() }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Styler.cardBackground(context, colors, cornerDp = 14f)
            val padding = dp(16)
            setPadding(padding, padding, padding, padding)
            isClickable = true
        }
        addView(card, LayoutParams(MATCH, WRAP, Gravity.CENTER).apply {
            leftMargin = dp(26)
            rightMargin = dp(26)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        authorImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        }
        header.addView(authorImage, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            marginEnd = dp(12)
        })
        val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleView = TextView(context).apply {
            textSize = 18f
            setTextColor(colors.primaryText)
            maxLines = 1
        }
        subtitleView = TextView(context).apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            maxLines = 2
        }
        labels.addView(titleView)
        labels.addView(subtitleView)
        header.addView(labels, LinearLayout.LayoutParams(0, WRAP, 1f))
        countView = TextView(context).apply {
            textSize = 13f
            setTextColor(colors.accent)
            gravity = Gravity.END
        }
        header.addView(countView, LinearLayout.LayoutParams(dp(120), WRAP))
        card.addView(header)

        bookRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        scroller = HorizontalScrollView(context).apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            addView(bookRow)
        }
        card.addView(scroller, LinearLayout.LayoutParams(MATCH, dp(205)).apply {
            topMargin = dp(12)
        })

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        card.addView(actionRow, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(10)
        })
    }

    fun show(
        preview: ReadingSeriesPreview,
        onCancel: () -> Unit = {},
        onSubmit: (List<String>) -> Unit
    ) {
        this.preview = preview
        this.model = ReadingSeriesSelectionModel(preview.books)
        this.onCancel = onCancel
        this.onSubmit = onSubmit
        titleView.text = preview.name
        subtitleView.text = buildString {
            append(preview.author)
            if (preview.ordering.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("publication order")
            }
        }
        authorImage.visibility = if (preview.authorImage.isBlank()) View.GONE else View.VISIBLE
        load(preview.authorImage, authorImage)
        visibility = View.VISIBLE
        bringToFront()
        rebuild()
    }

    fun dismiss() {
        visibility = View.GONE
        preview = null
        model = null
        onCancel = null
        onSubmit = null
        bookRow.removeAllViews()
        actionRow.removeAllViews()
    }

    fun onPad(action: PadAction): Boolean {
        val state = model ?: return false
        if (!isOpen) return false
        when (action) {
            is PadAction.Step -> {
                when (action.direction) {
                    Direction.LEFT -> state.moveHorizontal(-1)
                    Direction.RIGHT -> state.moveHorizontal(1)
                    Direction.UP -> state.moveVertical(-1)
                    Direction.DOWN -> state.moveVertical(1)
                }
                rebuild()
            }
            PadAction.Activate -> activate(state)
            PadAction.Secondary -> {
                state.toggleAllMissing()
                rebuild()
            }
            PadAction.Back -> cancel()
            else -> Unit
        }
        return true
    }

    private fun activate(state: ReadingSeriesSelectionModel) {
        if (!state.actionsFocused) {
            state.toggleFocused()
            rebuild()
            return
        }
        when (state.action) {
            ReadingSeriesSelectionModel.Action.SELECT_ALL -> {
                state.toggleAllMissing()
                rebuild()
            }
            ReadingSeriesSelectionModel.Action.CONFIRM -> {
                val ids = state.selectedIds()
                if (ids.isNotEmpty()) onSubmit?.invoke(ids)
            }
        }
    }

    private fun cancel() {
        val callback = onCancel
        dismiss()
        callback?.invoke()
    }

    private fun rebuild() {
        val state = model ?: return
        bookRow.removeAllViews()
        state.books.forEachIndexed { index, book ->
            bookRow.addView(bookCard(book, index, state), LinearLayout.LayoutParams(dp(116), MATCH).apply {
                marginEnd = dp(8)
            })
        }
        actionRow.removeAllViews()
        actionRow.addView(actionButton(
            "Select missing",
            state.actionsFocused && state.action == ReadingSeriesSelectionModel.Action.SELECT_ALL
        ) {
            state.focusAction(ReadingSeriesSelectionModel.Action.SELECT_ALL)
            state.toggleAllMissing()
            rebuild()
        })
        actionRow.addView(actionButton(
            "Download ${state.selectedIds().size}",
            state.actionsFocused && state.action == ReadingSeriesSelectionModel.Action.CONFIRM,
            enabled = state.selectedIds().isNotEmpty()
        ) {
            state.focusAction(ReadingSeriesSelectionModel.Action.CONFIRM)
            if (state.selectedIds().isNotEmpty()) onSubmit?.invoke(state.selectedIds())
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        val missing = state.books.count { !it.inLibrary }
        countView.text = "${state.selectedIds().size} selected\n$missing missing"
        if (!state.actionsFocused) {
            val selected = bookRow.getChildAt(state.bookIndex)
            scroller.post {
                selected?.let {
                    scroller.smoothScrollTo((it.left - (scroller.width - it.width) / 2).coerceAtLeast(0), 0)
                }
            }
        }
    }

    private fun bookCard(
        book: ReadingSeriesPreviewBook,
        index: Int,
        state: ReadingSeriesSelectionModel
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val focused = !state.actionsFocused && state.bookIndex == index
        background = face(focused)
        alpha = if (book.inLibrary) 0.56f else 1f
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isClickable = true
        contentDescription = buildString {
            append("Book ${book.position}, ${book.title}")
            append(if (book.inLibrary) ", in library" else if (state.isSelected(index)) ", selected" else ", not selected")
        }
        setOnClickListener {
            state.focusBook(index)
            state.toggleFocused()
            rebuild()
        }
        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        }
        addView(poster, LinearLayout.LayoutParams(dp(84), dp(126)))
        load(book.cover, poster)
        addView(TextView(context).apply {
            text = when {
                book.inLibrary -> "✓ In library"
                state.isSelected(index) -> "☑ Book ${book.position}"
                else -> "☐ Book ${book.position}"
            }
            textSize = 11f
            setTextColor(if (state.isSelected(index)) colors.accent else colors.mutedText)
            maxLines = 1
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(TextView(context).apply {
            text = book.title
            textSize = 11f
            setTextColor(colors.primaryText)
            maxLines = 2
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun actionButton(
        label: String,
        focused: Boolean,
        enabled: Boolean = true,
        click: () -> Unit
    ) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(if (enabled) colors.primaryText else colors.mutedText)
        background = face(focused)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isEnabled = enabled
        isClickable = enabled
        setOnClickListener { click() }
    }

    private fun face(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = Styler.dp(context, 10f)
        setColor(
            if (focused && ringVisible()) this@ReadingSeriesSelectionOverlay.colors.focusFill
            else this@ReadingSeriesSelectionOverlay.colors.stripBackground
        )
        if (focused && ringVisible()) {
            setStroke(dp(3), this@ReadingSeriesSelectionOverlay.colors.focusRing)
        }
    }

    private fun load(path: String, target: ImageView) {
        if (path.isBlank()) return
        val url = imageUrl(path)
        if (url.isBlank()) return
        imageLoader.enqueue(
            ImageRequest.Builder(context).data(url).target(target)
                .bitmapConfig(Bitmap.Config.RGB_565).build()
        )
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
