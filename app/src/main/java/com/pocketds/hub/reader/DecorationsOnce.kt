package com.pocketds.hub.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Each decoration once on the page (#62, found on a word edition in #66). Readium's page script adds a decoration without looking
 * for one with the same id (`readium.getDecorations(group).add`), and Readium adds every decoration it holds again when a page
 * finishes loading. So a highlight applied while its page was still loading (the hub's highlights arriving as the book opens, the
 * hub's sync landing then) was drawn twice, one box over the other, and "Remove the highlight" took away one of the two: the
 * highlight stayed on the page. Measured: three highlights from another device, six items on the page; one removed, still drawn.
 *
 * [script] leaves one item for each id the reader holds in a group of [groups] (the newest), takes away any other, and in a group of
 * `duplicatesOnly` only the extra copies of an id; and from then on in that page an add of an id the group already has replaces it.
 * It is run over every page Readium has loaded ([run]) after the reader draws and when a page settles, by when Readium's own adds on
 * the page have been made. A sweep alone was not enough: a page could still be given its decorations again after the last one.
 */
object DecorationsOnce {
    /** After a draw or a page change, the passes: at once, and again once a page that was still loading has had its decorations put back. */
    val PASSES_MS = listOf(0L, 400L, 1_500L)

    fun script(groups: Map<String, Collection<String>>, duplicatesOnly: Collection<String> = emptyList()): String {
        val wanted = groups.entries.joinToString(",", "{", "}") { (group, ids) ->
            ReadAlongGlow.jsString(group) + ":" + ids.joinToString(",", "[", "]") { ReadAlongGlow.jsString(it) }
        }
        val loose = duplicatesOnly.joinToString(",", "[", "]") { ReadAlongGlow.jsString(it) }
        return """(function (wanted, loose) {
  if (!window.readium || !readium.getDecorations) return 0;
  var removed = 0;
  function sweep(name, ids) {
    var g = readium.getDecorations(name), seen = {};
    if (!g || !g.items) return;
    // From now on in this page, an add of an id the group has replaces it (Readium's own add appends another).
    if (!g.__pocketOnce && g.add && g.remove) {
      var add = g.add, remove = g.remove;
      g.add = function (d) {
        while (g.items.some(function (i) { return i.decoration && i.decoration.id === d.id; })) remove(d.id);
        return add(d);
      };
      g.__pocketOnce = true;
    }
    for (var i = g.items.length - 1; i >= 0; i--) {
      var item = g.items[i], id = item.decoration && item.decoration.id;
      if ((ids && !ids[id]) || seen[id]) {
        g.items.splice(i, 1);
        item.clickableElements = null;
        if (item.container) { item.container.remove(); item.container = null; }
        removed++;
      } else seen[id] = true;
    }
  }
  Object.keys(wanted).forEach(function (name) {
    var ids = {};
    wanted[name].forEach(function (id) { ids[id] = true; });
    sweep(name, ids);
  });
  loose.forEach(function (name) { sweep(name, null); });
  return removed;
})($wanted, $loose)"""
    }

    /** Runs [script] in every page of the navigator's [view]: the one in front and those Readium keeps loaded beside it. */
    fun run(view: View?, script: String) {
        fun webViews(v: View): List<WebView> = when (v) {
            is WebView -> listOf(v)
            is ViewGroup -> (0 until v.childCount).flatMap { webViews(v.getChildAt(it)) }
            else -> emptyList()
        }
        // In the next frame, after the adds Readium has asked the page for (it makes them in one).
        view?.let(::webViews)?.forEach { it.evaluateJavascript("requestAnimationFrame(function () { $script; });", null) }
    }
}
