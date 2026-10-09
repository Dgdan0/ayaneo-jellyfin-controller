package com.pocketds.hub.reader

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The sentence being read, as Readium draws it (#16, X7, #52): a wash of the accent behind the words. The tint is
 * the Books accent let into the page ([wash]) and handed in with each highlight; Readium's highlight template
 * ([element], [STYLESHEET]) lays one box over each line. Pure strings and maths, so a JVM test pins them; the
 * layout is checked in the pixels of a real page by ReadAlongHighlightTest.
 *
 * Four things the first versions got wrong, each seen on the real Pocket:
 *
 *  - **Behind the words.** A translucent box over the text washed the sentence's own ink out (it read lighter than
 *    its neighbours). The boxes sit at `z-index: -1`, under everything in the page, so the ink is the page's own in
 *    every theme. Behind the words the tint can be an opaque colour mixed with the page ([wash]) instead of a
 *    translucent one, so two boxes that overlap are one colour: nothing to add up, and no group opacity to make a
 *    stacking context that would bring the boxes back over the text.
 *  - **Nothing outside the sentence.** The ring, glow and a fixed reach into the line above and below tinted
 *    "shadows." over the sentence's first line and "My breath catches" under its last. There is no glow, and each
 *    box is exactly its line: [fitScript] makes it the line's own *line box*, the line-height of the text, centred
 *    on the line. Line boxes tile the page, so the boxes of a sentence's lines meet with no gap and no
 *    overlap whatever the line spacing (a gap at 1.8 was the "bands" of #52), and none reaches into the line
 *    above or below the sentence: a line box never cuts the ink of its neighbours, which Readium's own boxes (the
 *    font's content area, taller than the line at 1.3) do.
 *  - **Strong enough to read, not so strong the words do not.** [wash] is as much of the colour as keeps the page's
 *    ink at 4.5:1 on it, from the strength asked for down by 0.02 at a time ([ReadAlongWordHighlight] asks for it).
 *  - **Nothing between sentences (#56).** Storyteller puts the space after a sentence inside its element
 *    (`<span id="…">The dead are dead. </span>`), and Readium draws its boxes over the whole element, so the wash ran on
 *    past the full stop and the next sentence's began at its first letter. [fitScript] also clips each row's box to
 *    the sentence's words: from the first to the last character that is not white space, with only [SIDE_PX] of air
 *    round them. The EPUB is left as it is.
 *
 * Read along by the word (#66), the same boxes are the trail: [fitScript] cuts them back to the end of the word being
 * said (none at all when the trail is off), and draws that word in its own boxes ([WORD_CLASS]) over them, each its line's
 * line box and round at every corner, in the same container so they lie in the same place and go with it.
 */
object ReadAlongGlow {
    const val CLASS = "pocket-narration"
    /** The word being said, drawn by [fitScript] over the trail (#66). */
    const val WORD_CLASS = "pocket-narration-word"
    /** The decoration group Readium is given the sentence in; its container carries this as `data-group`. */
    const val GROUP = "readalong"

    /** The least of the colour the hold for the ink goes down to, and the step it goes down by: the demo's numbers (#66). */
    const val WEAKEST = 0.06
    const val STEP = 0.02
    /** WCAG's AA for text: the page's ink on the wash. */
    const val MIN_CONTRAST = 4.5

    /** The soft corners, and the air each side of a line's words (a space between two words is wider than both). */
    private const val CORNER_PX = 3
    const val SIDE_PX = 2
    /** Each box is this much taller than its line at each edge, so two that meet are one: a seam of two edges drawn half over a pixel is lighter. */
    private const val SEAM_PX = 0.75

    /**
     * [color] let into [page], opaque: [strength] of the way, or less, by [STEP] at a time, until [ink] reads at
     * [MIN_CONTRAST] on it; never below [WEAKEST] (a strength asked for below that is taken as it is). All three are ARGB,
     * only their colour channels are used. The same loop as the owner's demo, number for number, so the Pocket and Apple
     * draw the same colours.
     */
    fun wash(color: Int, page: Int, ink: Int, strength: Double): Int {
        var t = strength
        while (t > WEAKEST && contrast(ink, mix(page, color, t)) < MIN_CONTRAST) t -= STEP
        return mix(page, color, t)
    }

    /** [page] moved [strength] of the way to [accent], as an opaque colour. */
    fun mix(page: Int, accent: Int, strength: Double): Int {
        fun channel(shift: Int): Int {
            val from = (page shr shift) and 0xFF
            val to = (accent shr shift) and 0xFF
            return (from + (to - from) * strength).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** WCAG's contrast ratio between two colours. */
    fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /**
     * Readium lays one of these over each line of a sentence. The colour is inline and important: Readium's own
     * stylesheet makes every element's background transparent, and an inline declaration is what outranks it.
     */
    fun element(tint: Int): String = """<div class="$CLASS" style="background-color: ${rgb(tint)} !important;"></div>"""

    /** A colour as CSS, without any transparency: a box that is not see-through cannot add up with another. */
    fun rgb(color: Int): String = "rgb(${(color shr 16) and 0xFF}, ${(color shr 8) and 0xFF}, ${color and 0xFF})"

    /**
     * Behind the words, the room at each side of them, and the corners. `z-index` is on the boxes themselves, which
     * are absolutely placed by Readium, with nothing between them and the page that makes a stacking context (an
     * opacity or a filter on the group would): so they are painted under the page's text, over only its background.
     */
    const val STYLESHEET = ".$CLASS, .$WORD_CLASS { z-index: -1 !important; margin-left: -${SIDE_PX}px; padding: 0 ${SIDE_PX}px; " +
        "box-sizing: content-box; border-radius: ${CORNER_PX}px; }"

    /** A string as a JavaScript string literal, single-quoted: what [fitScript] is given for the sentence's element id. */
    fun jsString(value: String): String = buildString {
        append('\'')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '<' -> append("\\u003c")
                else -> append(c)
            }
        }
        append('\'')
    }

    /**
     * Makes each of the sentence's boxes its line's line box, once Readium has put them on the page and again whenever
     * it lays them anew (a resize, a new text size): the height of the line-height of the element the sentence is
     * (the element with the id [fragment], which the narration is told to read), centred on its row of the sentence's
     * text (Readium's own boxes are trimmed where two lines' rectangles overlap, which moves their centres), with
     * [SEAM_PX] over at each edge. Sideways each box is cut back to the sentence's words on its row (#56): the rects of a
     * Range from the first to the last character of the element's text that is not white space, the text nodes walked in
     * order, so the space Storyteller leaves after a sentence (or before it) inside its element is not tinted; the
     * [SIDE_PX] of air stays round the words, and a row with only white space has no box (it is hidden). And the corners: a
     * box's corner is square where another box of the sentence is directly beside it vertically (the join of two lines),
     * round where it is the edge of the shape. With no line-height in pixels (`normal`) the boxes stay Readium's own.
     * Run through the navigator after the decoration is applied; it installs its own observer on the page the first
     * time, so a reflow keeps the boxes fitted.
     *
     * With a [word] (#66, the element of that id inside the sentence) the boxes are the trail: the Range ends at the word's
     * last character instead, so the trail grows a word at a time from the sentence's first letter, and a row the voice has
     * not reached has no box; [trail] false hides them all (a trail of 0%). The word itself is drawn over them in
     * [wordTint], a [WORD_CLASS] box on each row of it (a hyphenated word has two), its line's line box with [SIDE_PX] of air
     * and round corners, in the same container as the trail so it lies where the trail does and goes when Readium takes the
     * decoration away. Each box keeps Readium's own extent (in a WeakMap, off the page) the first time it is seen, so a later word
     * cuts it back from there, not from the last word's cut. The same script is run for every word: it is the state.
     */
    fun fitScript(fragment: String, word: String? = null, wordTint: Int? = null, trail: Boolean = true): String = """(function () {
  var GROUP = '$GROUP', BOX = '$CLASS', WORD = '$WORD_CLASS', CORNER = $CORNER_PX, SEAM = $SEAM_PX, SIDE = $SIDE_PX;
  window.__pocketNarrationFragment = ${jsString(fragment)};
  window.__pocketNarrationWord = ${word?.let(::jsString) ?: "null"};
  window.__pocketNarrationWordTint = ${wordTint?.let { jsString(rgb(it)) } ?: "null"};
  window.__pocketNarrationTrail = ${if (trail) "true" else "false"};
  function px(value) { return parseFloat(value) || 0; }
  // The first and the last character of an element's text that are not white space, as text nodes and offsets.
  function ends(element) {
    var walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    var first = null, last = null, node;
    while ((node = walker.nextNode())) {
      var text = node.nodeValue, from = text.search(/\S/), to = text.length;
      if (from < 0) continue;
      while (to > from && /\s/.test(text.charAt(to - 1))) to--;
      if (!first) first = { node: node, at: from };
      last = { node: node, at: to };
    }
    return first ? { first: first, last: last } : null;
  }
  function fit() {
    var group = document.querySelector('[data-group="' + GROUP + '"]');
    if (!group) return;
    var target = document.getElementById(window.__pocketNarrationFragment);
    var word = window.__pocketNarrationWord ? document.getElementById(window.__pocketNarrationWord) : null;
    if (word && !(target && target.contains(word))) word = null;
    var trail = !word || window.__pocketNarrationTrail !== false;
    var line = target ? parseFloat(getComputedStyle(target).lineHeight) : NaN;
    // Where each row of the sentence's text is, from the text itself: Readium trims its boxes where two lines' rectangles
    // overlap (they do, at 1.3), which moves their centres off the line's.
    var rows = [];
    var scroller = document.scrollingElement || document.documentElement;
    var offset = scroller.scrollTop, across = scroller.scrollLeft;
    if (line > 0) {
      var range = document.createRange();
      range.selectNodeContents(target);
      Array.prototype.forEach.call(range.getClientRects(), function (r) {
        var middle = r.top + r.height / 2 + offset;
        if (r.height > 0 && !rows.some(function (c) { return Math.abs(c - middle) < line / 2; })) rows.push(middle);
      });
    }
    function rowNear(centre) {
      var best = centre, away = line;
      rows.forEach(function (row) { if (Math.abs(row - centre) < away) { away = Math.abs(row - centre); best = row; } });
      return best;
    }
    // Rows of a stretch of text, sideways, in the page's own coordinates (what Readium's boxes are in).
    function spans(start, end) {
      var out = [];
      var stretch = document.createRange();
      stretch.setStart(start.node, start.at);
      stretch.setEnd(end.node, end.at);
      Array.prototype.forEach.call(stretch.getClientRects(), function (r) {
        if (r.width <= 0 || r.height <= 0) return;
        var row = line > 0 ? rowNear(r.top + r.height / 2 + offset) : r.top + r.height / 2 + offset;
        var here = out.filter(function (w) { return w.row === row; })[0];
        if (here) { here.l = Math.min(here.l, r.left + across); here.r = Math.max(here.r, r.right + across); }
        else out.push({ row: row, l: r.left + across, r: r.right + across, h: r.height });
      });
      return out;
    }
    // Where the sentence's words are on each row: from the first character of its text that is not white space to the last,
    // so the space inside the element after or before the words (Storyteller's) is not in it; with a word being said, to
    // that word's last character, which is the trail.
    var sentence = target ? ends(target) : null;
    var said = word ? ends(word) : null;
    var words = sentence ? spans(sentence.first, said ? said.last : sentence.last) : [];
    var strong = said && window.__pocketNarrationWordTint ? spans(said.first, said.last) : [];
    // Readium's own extent of each box, kept the first time the box is seen (a later word cuts it back from there, not from
    // the last word's cut), and kept off the page: every read here comes before the first write, so a word costs the page
    // one layout, not one a box.
    var kept = window.__pocketNarrationKept || (window.__pocketNarrationKept = new WeakMap());
    var items = Array.prototype.map.call(group.querySelectorAll('[data-style]'), function (item) {
      var all = Array.prototype.filter.call(item.children, function (c) { return c.classList.contains(BOX); });
      return { item: item, old: item.querySelectorAll('.' + WORD), all: all, own: all.map(function (el) {
        var own = kept.get(el);
        if (!own) { var s = getComputedStyle(el); own = { l: px(s.left), w: px(s.width), t: px(s.top), h: px(s.height) }; kept.set(el, own); }
        return own;
      }) };
    });
    items.forEach(function (entry) {
      // Where each box goes: its line's line box, cut back to the words on its row; hidden where there are none, or no trail.
      var placed = entry.all.map(function (el, i) {
        var own = entry.own[i];
        if (!trail) return null;
        if (!(line > 0)) return { el: el, keep: true, l: own.l - SIDE, t: own.t, w: own.w + 2 * SIDE, h: own.h };
        var centre = rowNear(own.t + own.h / 2);
        var here = words.filter(function (w) { return w.row === centre; })[0];
        var from = here ? Math.max(own.l, here.l) : 0, to = here ? Math.min(own.l + own.w, here.r) : 0;
        if (!(to > from)) return null;
        return { el: el, left: from, width: to - from, top: centre - line / 2 - SEAM, height: line + 2 * SEAM,
          l: from - SIDE, t: centre - line / 2 - SEAM, w: to - from + 2 * SIDE, h: line + 2 * SEAM };
      });
      var shown = placed.filter(function (p) { return p; });
      function covered(x, y, own) {
        return shown.some(function (c) { return c !== own && x >= c.l && x <= c.l + c.w && y >= c.t && y <= c.t + c.h; });
      }
      // Only what changed is written: a word repaints the row the trail grows on and the word's own box, not the sentence.
      // What was last written is kept on the element itself: the style reads back a value rounded, which would never match.
      function set(el, name, value, important) {
        var last = el.__pocketLast || (el.__pocketLast = {});
        if (last[name] === value) return;
        last[name] = value;
        el.style.setProperty(name, value, important || '');
      }
      entry.all.forEach(function (el, i) {
        var p = placed[i];
        if (!p) { set(el, 'display', 'none'); return; }
        if ((el.__pocketLast || {}).display) { el.__pocketLast.display = ''; el.style.removeProperty('display'); }
        if (!p.keep) {
          set(el, 'top', p.top + 'px');
          set(el, 'height', p.height + 'px');
          set(el, 'left', p.left + 'px');
          set(el, 'width', p.width + 'px');
        }
        // Square where another box of the sentence is directly beside it vertically (two lines' join), round at the shape's edge.
        function round(x, y) { return covered(x, y, p) ? '0px' : CORNER + 'px'; }
        set(el, 'border-radius', [
          round(p.l + 1, p.t - 1), round(p.l + p.w - 1, p.t - 1), round(p.l + p.w - 1, p.t + p.h + 1), round(p.l + 1, p.t + p.h + 1)
        ].join(' '));
      });
      // The word being said, strong, over the trail: a box on each row of it, its line's line box, round at every corner.
      // The boxes of the word before are moved, not made again; one the new word does not need goes.
      strong.forEach(function (w, k) {
        var box = entry.old[k];
        if (!box) {
          box = document.createElement('div');
          box.className = WORD;
          box.style.setProperty('position', 'absolute');
          box.style.setProperty('pointer-events', 'none');
          entry.item.appendChild(box);
        }
        var height = line > 0 ? line : w.h;
        set(box, 'left', w.l + 'px');
        set(box, 'width', (w.r - w.l) + 'px');
        set(box, 'top', (w.row - height / 2 - SEAM) + 'px');
        set(box, 'height', (height + 2 * SEAM) + 'px');
        set(box, 'background-color', window.__pocketNarrationWordTint, 'important');
      });
      for (var extra = strong.length; extra < entry.old.length; extra++) entry.old[extra].remove();
    });
  }
  if (!window.__pocketNarrationFit) {
    var watcher = new MutationObserver(function () { window.__pocketNarrationFit(); });
    // The script's own boxes are a change to the page too: it is not watched while it makes them.
    window.__pocketNarrationFit = function () {
      watcher.disconnect();
      try { fit(); } finally { watcher.observe(document.body, { childList: true, subtree: true }); }
    };
  }
  window.__pocketNarrationFit();
})()"""
}
