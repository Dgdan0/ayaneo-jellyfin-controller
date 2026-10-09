package com.pocketds.hub.reader

/**
 * Going to a place inside another file of the book (#59): a Contents entry that points into the middle of a chapter
 * (`chapter.xhtml#part2`), a bookmark, a search result, a slider. Readium's own `go(locator)` is unreliable across files
 * when the locator names a place in the file. Measured on the Pocket's emulator with a fixture book, from the same
 * Contents row: from an earlier file it landed on the anchor one time and on the top of the file the next; from a later
 * file it landed at the very end of the file, every time (page 261 for an entry on page 94). A whole file, from anywhere,
 * and a place in the file already on screen land exactly.
 *
 * So a jump to a place in another file is made in two steps that are each known to land: the file first, from its top,
 * waited for until it has loaded and its columns have stopped moving, then the place in it, which is a jump within the
 * file on screen. This is the plan and the waiting, plain Kotlin so a JVM test pins it; the reader hides the page between
 * the two steps, so what shows is the page it arrives on.
 */
object AnchorJump {
    /** How often the file is asked whether it is laid out, and how long it is given before the place is gone to anyway. */
    const val POLL_MS = 50L
    const val TIMEOUT_MS = 3_000L

    /**
     * Whether a locator names a place inside its file and not the file's start: a fragment (an element's id), how far through
     * the file, a CSS selector or other place, or the words (a search result's, a bookmark's).
     */
    fun namesPlace(fragments: List<String>, progression: Double?, otherLocations: Boolean, text: Boolean): Boolean =
        fragments.any { it.isNotBlank() } || (progression ?: 0.0) > 0.0 || otherLocations || text

    /**
     * Whether to go to the file first. Only a place in a file other than the one in front: the same file is a jump within
     * it, and a whole file lands exactly in one step. With no file in front yet (nothing opened) there is nothing to wait for.
     */
    fun resourceFirst(currentDocument: String?, targetDocument: String, namesPlace: Boolean): Boolean =
        namesPlace && currentDocument != null && currentDocument != targetDocument

    /**
     * What the file in front says, once it is the one asked for and has stopped changing: is it [document] (the page's URL ends
     * with its path), has it finished loading, are its fonts in, and how wide its columns run (a book's pages are columns, and
     * the width is what moves while they are laid out).
     */
    data class State(val isTarget: Boolean, val loaded: Boolean, val fontsReady: Boolean, val width: Int)

    /** The script that asks, for the page in front: "1|1|1|48800". [document] is in [DocumentPath]'s spelling. */
    fun script(document: String): String = """(function () {
  var want = ${ReadAlongGlow.jsString(document)};
  var path = location.pathname;
  try { path = decodeURIComponent(path); } catch (e) {}
  if (path.normalize) path = path.normalize('NFC');
  var here = path === want || path.slice(-(want.length + 1)) === '/' + want ? 1 : 0;
  var scroller = document.scrollingElement || document.documentElement;
  return [here, document.readyState === 'complete' ? 1 : 0, !document.fonts || document.fonts.status === 'loaded' ? 1 : 0,
    scroller ? scroller.scrollWidth : 0].join('|');
})()"""

    /** The script's answer, which the navigator hands back as a JSON string ("\"1|1|1|48800\""); null for anything else. */
    fun parse(raw: String?): State? {
        val text = raw?.trim()?.removeSurrounding("\"") ?: return null
        val parts = text.split('|')
        if (parts.size != 4) return null
        val width = parts[3].toDoubleOrNull()?.toInt() ?: return null
        if (parts.take(3).any { it != "0" && it != "1" }) return null
        return State(parts[0] == "1", parts[1] == "1", parts[2] == "1", width)
    }

    /** How many more times the place is gone to when the element it names is not on the page afterwards, and how long to wait before looking. */
    const val RETRIES = 8
    const val LOOK_AFTER_MS = 100L

    /** How many looks in a row must find the place on the page before the page is shown. */
    const val RIGHT_LOOKS = 2

    /**
     * Asks the page in front where the element [fragment] is: "in" when it is on the page (its left edge within the window), "out"
     * when it is in the file but on another page, "none" when there is no such element (a locator that names it by its position,
     * not its id, or a book whose anchor is gone). A jump that left it "out" is made again: under load the first came before the
     * page was ready for it (one in ten, with the machine busy) and left the file's top.
     */
    fun landedScript(fragment: String): String = """(function () {
  var e = document.getElementById(${ReadAlongGlow.jsString(fragment)});
  if (!e) return 'none';
  var r = e.getBoundingClientRect();
  return r.left >= -2 && r.left < window.innerWidth ? 'in' : 'out';
})()"""

    /** The answer to [landedScript]: true when the place is on the page, false when it is not, null when it cannot be told. */
    fun landed(raw: String?): Boolean? = when (raw?.trim()?.removeSurrounding("\"")) {
        "in" -> true
        "out" -> false
        else -> null
    }

    /**
     * For a place given by how far through the file it is (a search result's, a bookmark's) and not by an id: whether the page is
     * somewhere near it. It is not when the page still reports the file's top, as under load it does when the jump came before the
     * page was ready; null when the place is at the top itself (nothing to tell it from) or the page has said nothing.
     */
    fun landedByProgress(wanted: Double?, now: Double?): Boolean? {
        if (wanted == null || !wanted.isFinite() || wanted <= 0.05) return null
        return now?.takeIf { it.isFinite() }?.let { it >= wanted * 0.5 }
    }

    /** The file is laid out: it is the one asked for, loaded, its fonts in, and as wide as it was a moment ago. */
    fun settled(before: State?, now: State?): Boolean =
        before != null && now != null && now.isTarget && now.loaded && now.fontsReady && now.width > 0 && before.isTarget && before.width == now.width
}
