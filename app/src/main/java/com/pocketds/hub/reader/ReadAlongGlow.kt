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
 *  - **Strong enough to read, not so strong the words do not.** [wash] is as much of the accent as keeps the page's
 *    ink at 4.5:1 on it, up to 45%: Paper and Sepia take all of it, the dark pages a little under a third.
 *  - **Nothing between sentences (#56).** Storyteller puts the space after a sentence inside its element
 *    (`<span id="…">The dead are dead. </span>`), and Readium draws its boxes over the whole element, so the wash ran on
 *    past the full stop and the next sentence's began at its first letter. [fitScript] also clips each row's box to
 *    the sentence's words: from the first to the last character that is not white space, with only [SIDE_PX] of air
 *    round them. The EPUB is left as it is.
 */
object ReadAlongGlow {
    const val CLASS = "pocket-narration"
    /** The decoration group Readium is given the sentence in; its container carries this as `data-group`. */
    const val GROUP = "readalong"

    /** How much of the accent a page takes at most, and the least it is ever given. */
    const val STRONGEST = 0.45
    const val WEAKEST = 0.12
    private const val STEP = 0.02
    /** WCAG's AA for text: the page's ink on the wash. */
    const val MIN_CONTRAST = 4.5

    /** The soft corners, and the air each side of a line's words (a space between two words is wider than both). */
    private const val CORNER_PX = 3
    const val SIDE_PX = 2
    /** Each box is this much taller than its line at each edge, so two that meet are one: a seam of two edges drawn half over a pixel is lighter. */
    private const val SEAM_PX = 0.75

    /**
     * The accent let into [page], opaque: as much as keeps [ink] at [MIN_CONTRAST] on it, between [WEAKEST] and
     * [STRONGEST] of the way. All three are ARGB, only their colour channels are used.
     */
    fun wash(accent: Int, page: Int, ink: Int): Int {
        var strength = STRONGEST
        while (strength > WEAKEST + 1e-9) {
            val mixed = mix(page, accent, strength)
            if (contrast(ink, mixed) >= MIN_CONTRAST) return mixed
            strength -= STEP
        }
        return mix(page, accent, WEAKEST)
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
    const val STYLESHEET = ".$CLASS { z-index: -1 !important; margin-left: -${SIDE_PX}px; padding: 0 ${SIDE_PX}px; " +
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
     */
    fun fitScript(fragment: String): String = """(function () {
  var GROUP = '$GROUP', BOX = '$CLASS', CORNER = $CORNER_PX, SEAM = $SEAM_PX;
  window.__pocketNarrationFragment = ${jsString(fragment)};
  function px(value) { return parseFloat(value) || 0; }
  function fit() {
    var group = document.querySelector('[data-group="' + GROUP + '"]');
    if (!group) return;
    var target = document.getElementById(window.__pocketNarrationFragment);
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
    // Where the sentence's words are on each row, sideways, in the page's own coordinates (what Readium's boxes are in): from the
    // first character of its text that is not white space to the last, so the space inside the element after or before the
    // words (Storyteller's) is not in it.
    var words = [];
    if (line > 0) {
      var walker = document.createTreeWalker(target, NodeFilter.SHOW_TEXT);
      var first = null, last = null, node;
      while ((node = walker.nextNode())) {
        var text = node.nodeValue, from = text.search(/\S/), to = text.length;
        if (from < 0) continue;
        while (to > from && /\s/.test(text.charAt(to - 1))) to--;
        if (!first) first = { node: node, at: from };
        last = { node: node, at: to };
      }
      if (first) {
        var spoken = document.createRange();
        spoken.setStart(first.node, first.at);
        spoken.setEnd(last.node, last.at);
        Array.prototype.forEach.call(spoken.getClientRects(), function (r) {
          if (r.width <= 0 || r.height <= 0) return;
          var row = rowNear(r.top + r.height / 2 + offset);
          var here = words.filter(function (w) { return w.row === row; })[0];
          if (here) { here.l = Math.min(here.l, r.left + across); here.r = Math.max(here.r, r.right + across); }
          else words.push({ row: row, l: r.left + across, r: r.right + across });
        });
      }
    }
    Array.prototype.forEach.call(group.querySelectorAll('[data-style]'), function (item) {
      var all = Array.prototype.filter.call(item.children, function (c) { return c.classList.contains(BOX); });
      if (line > 0) all.forEach(function (el) {
        var s = getComputedStyle(el);
        var centre = rowNear(px(s.top) + px(s.height) / 2);
        el.style.setProperty('top', (centre - line / 2 - SEAM) + 'px');
        el.style.setProperty('height', (line + 2 * SEAM) + 'px');
        // Only the words: the box's own extent cut back to theirs on its row, none where the row has nothing but white space.
        var here = words.filter(function (w) { return w.row === centre; })[0];
        var l = px(s.left), r = l + px(s.width);
        var from = here ? Math.max(l, here.l) : 0, to = here ? Math.min(r, here.r) : 0;
        if (to > from) {
          el.style.removeProperty('display');
          el.style.setProperty('left', from + 'px');
          el.style.setProperty('width', (to - from) + 'px');
        } else el.style.setProperty('display', 'none');
      });
      var els = all.filter(function (el) { return el.style.display !== 'none'; });
      var boxes = els.map(function (el) {
        var s = getComputedStyle(el);
        return { l: px(s.left) + px(s.marginLeft), t: px(s.top), w: px(s.width) + px(s.paddingLeft) + px(s.paddingRight), h: px(s.height) };
      });
      function covered(x, y, own) {
        return boxes.some(function (c, i) { return i !== own && x >= c.l && x <= c.l + c.w && y >= c.t && y <= c.t + c.h; });
      }
      els.forEach(function (el, i) {
        var b = boxes[i];
        function round(x, y) { return covered(x, y, i) ? '0' : CORNER + 'px'; }
        el.style.setProperty('border-radius', [
          round(b.l + 1, b.t - 1), round(b.l + b.w - 1, b.t - 1), round(b.l + b.w - 1, b.t + b.h + 1), round(b.l + 1, b.t + b.h + 1)
        ].join(' '));
      });
    });
  }
  if (!window.__pocketNarrationFit) {
    window.__pocketNarrationFit = fit;
    var watcher = new MutationObserver(function () {
      watcher.disconnect();
      try { fit(); } finally { watcher.observe(document.body, { childList: true, subtree: true }); }
    });
    watcher.observe(document.body, { childList: true, subtree: true });
  }
  window.__pocketNarrationFit();
})()"""
}
