package com.pocketds.hub.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator

/**
 * Puts [ReadAlongWordHighlight]'s marks on the page (#52, #56, #66). The sentence is one Readium decoration in the
 * [ReadAlongGlow.GROUP] group, laid down only when the sentence changes; a new word of the same sentence is only the fit
 * script run again ([ReadAlongGlow.fitScript]), which moves the trail's end and draws the word over it: one small script a
 * word, three to five a second, and nothing for Readium to work out. Draws run one after another, each with the mark as
 * it is when it runs, so a run of words while a sentence is being laid down ends on the latest.
 *
 * [locator] is a fragment of a document as a locator Readium resolves; [tints] the colours of the page as it is now.
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
class ReadAlongHighlighter(
    private val scope: CoroutineScope,
    private val navigator: () -> EpubNavigatorFragment?,
    private val locator: (href: String, fragment: String) -> Locator?,
    private val tints: () -> HighlightTints
) {
    private val lock = Mutex()
    private var shown: ReadAlongWordHighlight.Mark? = null
    private var sentenceJob: Job? = null
    /** How many draws the page has been given, by kind: what a test of the cost reads. */
    var sentenceDraws = 0; private set
    var wordDraws = 0; private set

    /** What is drawn now. */
    val mark: ReadAlongWordHighlight.Mark? get() = shown

    /** [segment] being read (null: nothing is); drawn when it is another sentence or another word than the page has. */
    fun show(segment: ReadAlongSegment?) = draw(ReadAlongWordHighlight.mark(segment), redraw = false)

    /** The page's look changed (a theme, the setting, a page that was not there): what is shown, drawn again in full. */
    fun redraw(segment: ReadAlongSegment?) = draw(ReadAlongWordHighlight.mark(segment), redraw = true)

    private fun draw(next: ReadAlongWordHighlight.Mark?, redraw: Boolean) {
        val change = if (redraw) next?.let { ReadAlongWordHighlight.Change.Sentence(it) } ?: ReadAlongWordHighlight.Change.Clear
            else ReadAlongWordHighlight.change(shown, next)
        shown = next
        when (change) {
            ReadAlongWordHighlight.Change.Same -> Unit
            ReadAlongWordHighlight.Change.Clear -> {
                sentenceJob?.cancel()
                sentenceJob = scope.launch { lock.withLock { navigator()?.applyDecorations(emptyList(), ReadAlongGlow.GROUP) } }
            }
            is ReadAlongWordHighlight.Change.Sentence -> {
                sentenceJob?.cancel()
                sentenceDraws++
                sentenceJob = scope.launch { lock.withLock { lay(withDecoration = true) } }
            }
            is ReadAlongWordHighlight.Change.Word -> {
                wordDraws++
                scope.launch { lock.withLock { lay(withDecoration = false) } }
            }
        }
    }

    /** The mark as it is now: its sentence laid down when asked, then the script that fits it and draws its word. */
    private suspend fun lay(withDecoration: Boolean) {
        val reader = navigator() ?: return
        val mark = shown ?: return
        val paint = ReadAlongWordHighlight.paint(mark, tints())
        if (withDecoration) {
            val place = locator(mark.textHref, mark.sentence) ?: return
            reader.applyDecorations(listOf(Decoration("narration", place, Decoration.Style.Highlight(paint.decorationTint, isActive = true))), ReadAlongGlow.GROUP)
        }
        // Each box made its line's line box, once the boxes are on the page (and again when it reflows): no gaps, nothing over the lines round it.
        runCatching { reader.evaluateJavascript(ReadAlongGlow.fitScript(paint.sentence, paint.word, paint.wordTint, paint.trail)) }
    }
}
