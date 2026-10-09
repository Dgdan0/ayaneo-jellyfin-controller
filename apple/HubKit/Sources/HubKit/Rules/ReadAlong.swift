import Foundation

// Read along (#25 phase 4, Android's #16 and #19): the narration of a
// read-along edition as a timeline of sentences, where a moment of it is, the
// sentence steps, what the dock and the page say, and the place as a text
// locator. Ports of Android's `ReadAlongTimeline`, `ReadAlongFollow`,
// `ReadAlongDockText`, `ReadAlongLocation` and `ReadAlongSession`, held to
// their tests.

/// A sentence the narration speaks: where its words are (`textHref`, a path
/// inside the EPUB, and the element's id), and when in which audio file it
/// is spoken.
public struct ReadAlongSegment: Equatable, Hashable, Sendable {
    public let textHref: String
    public let fragment: String
    public let audioHref: String
    public let beginMs: Int64
    public let endMs: Int64

    public init(textHref: String, fragment: String, audioHref: String, beginMs: Int64, endMs: Int64) {
        self.textHref = textHref
        self.fragment = fragment
        self.audioHref = audioHref
        self.beginMs = beginMs
        self.endMs = endMs
    }
}

/// A moment of the narration: a stretch of it (`track`) and how far into
/// the stretch, counted from its first sentence.
public struct ReadAlongPosition: Equatable, Hashable, Sendable {
    public let track: Int
    public let offsetMs: Int64

    public init(track: Int, offsetMs: Int64) {
        self.track = track
        self.offsetMs = offsetMs
    }
}

/// A stretch of the narration: sentences one after another in one audio
/// file. It is heard from its first sentence's start to its last one's end.
public struct ReadAlongTrack: Equatable, Sendable {
    public let audioHref: String
    public let segments: [ReadAlongSegment]

    public init(audioHref: String, segments: [ReadAlongSegment]) {
        precondition(!segments.isEmpty, "A stretch of narration has a sentence")
        self.audioHref = audioHref
        self.segments = segments
    }

    public var startMs: Int64 { segments[0].beginMs }
    public var durationMs: Int64 { segments[segments.count - 1].endMs - startMs }
}

/// The narration, stretch by stretch, in the order the book is read.
public struct ReadAlongTimeline: Equatable, Sendable {
    public let tracks: [ReadAlongTrack]

    /// Further into a sentence than this, back goes to its start rather than the sentence before.
    public static let restartMs: Int64 = 1_500

    public init(tracks: [ReadAlongTrack]) {
        self.tracks = tracks
    }

    /// The sentence spoken at `offsetMs` into stretch `track`, or nil in a
    /// pause between two: the wrong sentence is never lit. A book aligned
    /// word by word holds hundreds of thousands, and this is asked on every
    /// tick of the player, so it is a binary search.
    public func active(track: Int, offsetMs: Int64) -> ReadAlongSegment? {
        guard tracks.indices.contains(track) else { return nil }
        let value = tracks[track]
        let absolute = value.startMs + offsetMs
        var low = 0
        var high = value.segments.count - 1
        while low <= high {
            let middle = (low + high) / 2
            if value.segments[middle].beginMs <= absolute { low = middle + 1 } else { high = middle - 1 }
        }
        guard high >= 0, absolute < value.segments[high].endMs else { return nil }
        return value.segments[high]
    }

    /// Where the sentence `fragment` of `href` starts. `href` as Readium or
    /// a kept locator spells it: compared by its `BookHref.key` (#61).
    public func find(href: String, fragment: String) -> ReadAlongPosition? {
        let key = BookHref.key(href)
        for (index, track) in tracks.enumerated() {
            if let segment = track.segments.first(where: { $0.textHref == key && $0.fragment == fragment }) {
                return ReadAlongPosition(track: index, offsetMs: segment.beginMs - track.startMs)
            }
        }
        return nil
    }

    /// The sentence steps (L1 and R1 read along): where the sentence `delta`
    /// away from `position` begins, across stretches. Back from more than
    /// `restartMs` into a sentence goes to its own start first, as a player's
    /// Previous does. Nil past either end.
    public func step(_ position: ReadAlongPosition, delta: Int) -> ReadAlongPosition? {
        let all = tracks.enumerated().flatMap { index, track in track.segments.map { (index, $0) } }
        guard !all.isEmpty, delta != 0, tracks.indices.contains(position.track) else { return nil }
        let absolute = tracks[position.track].startMs + position.offsetMs
        // The sentence playing, or the last one begun before a pause.
        let here = all.lastIndex { $0.0 < position.track || ($0.0 == position.track && $0.1.beginMs <= absolute) } ?? -1
        let target: Int
        if here < 0 {
            guard delta > 0 else { return nil }
            target = delta - 1
        } else if delta < 0 && all[here].0 == position.track && absolute - all[here].1.beginMs > Self.restartMs {
            target = here + delta + 1
        } else {
            target = here + delta
        }
        guard all.indices.contains(target) else { return nil }
        let (index, segment) = all[target]
        return ReadAlongPosition(track: index, offsetMs: segment.beginMs - tracks[index].startMs)
    }

    /// Some sentence of `href`'s text is narrated: the page can be followed.
    /// `href` as Readium spells it (#61).
    public func narrates(_ href: String) -> Bool {
        let key = BookHref.key(href)
        return tracks.contains { track in track.segments.contains { $0.textHref == key } }
    }

    /// Where Play starts on a page of a part the narration never reads (#61):
    /// the first sentence of the next part in `readingOrder` that it does
    /// read, else the last sentence of the nearest one before. A part it
    /// reads gives its own first sentence; a page not in the reading order,
    /// the narration's first. Both spelled as Readium spells them.
    public func nearest(to href: String, readingOrder: [String]) -> ReadAlongSegment? {
        var first: [String: ReadAlongSegment] = [:]
        var last: [String: ReadAlongSegment] = [:]
        for segment in tracks.flatMap(\.segments) {
            if first[segment.textHref] == nil { first[segment.textHref] = segment }
            last[segment.textHref] = segment
        }
        let page = BookHref.key(href)
        if let own = first[page] { return own }
        let order = readingOrder.map(BookHref.key)
        guard let here = order.firstIndex(of: page) else { return tracks.first?.segments.first }
        for part in order[(here + 1)...] {
            if let segment = first[part] { return segment }
        }
        for part in order[..<here].reversed() {
            if let segment = last[part] { return segment }
        }
        return tracks.first?.segments.first
    }
}

/// What a read-along page says about the narration: the page turns with the
/// voice, you have turned away to read on your own while it plays, or this
/// part of the book has no narration to follow.
public enum ReadAlongFollow {
    public static func label(following: Bool, narrated: Bool) -> String {
        if !narrated { return "Alignment unavailable" }
        return following ? "Following" : "Reading"
    }
}

/// The dock's words and its line (#21's lower bar).
public enum ReadAlongDockText {
    /// "4:13 of 27:05" in the stretch playing, and which of how many when there are several.
    public static func time(_ position: ReadAlongPosition, _ timeline: ReadAlongTimeline) -> String {
        guard timeline.tracks.indices.contains(position.track) else { return "0:00 of 0:00" }
        let track = timeline.tracks[position.track]
        let clock = "\(Fmt.clock(min(max(position.offsetMs, 0), track.durationMs))) of \(Fmt.clock(track.durationMs))"
        return timeline.tracks.count > 1 ? "\(clock) · part \(position.track + 1) of \(timeline.tracks.count)" : clock
    }

    /// How far through the stretch playing, 0 to 1.
    public static func fraction(_ position: ReadAlongPosition, _ timeline: ReadAlongTimeline) -> Double {
        guard timeline.tracks.indices.contains(position.track) else { return 0 }
        let track = timeline.tracks[position.track]
        guard track.durationMs > 0 else { return 0 }
        return min(max(Double(position.offsetMs) / Double(track.durationMs), 0), 1)
    }

    /// The dock's heading: "Read along", and where the page stands while it plays.
    public static func heading(follow: String) -> String {
        follow.trimmingCharacters(in: .whitespaces).isEmpty ? "Read along" : "Read along · " + follow
    }
}

/// Read along's place is a standard text locator: the sentence being read,
/// as any reader of the book understands it (#19). The hub turns a
/// listener's place into the sentence spoken there and a sentence into a
/// moment of the audiobook, so the place follows you between reading along
/// and listening. The private `pocketdsAudio` offset an older Pocket build
/// added is neither written nor read. Locators are Readium's JSON, as the
/// ebook reader keeps them (`BookLocator`).
public enum ReadAlongLocation {
    /// Where the narration resumes: the start of the sentence the locator names.
    public static func resume(_ locator: String, _ timeline: ReadAlongTimeline) -> ReadAlongPosition? {
        guard let object = BookLocator.object(locator), let href = object["href"] as? String,
              let locations = object["locations"] as? [String: Any] else { return nil }
        let fragments = (locations["fragments"] as? [Any])?.compactMap { $0 as? String } ?? []
        for fragment in fragments {
            if let position = timeline.find(href: href, fragment: fragment) { return position }
        }
        return nil
    }

    /// The page's locator moved to the sentence playing at `point`, finished
    /// when `completed`: its part and fragment, no stale selector, no text,
    /// no private offset. A point the timeline does not hold leaves it as it was.
    /// The part keeps Readium's spelling (#61): the page's own when the
    /// sentence is in it, else `hrefs`'.
    public static func save(_ locator: String, _ timeline: ReadAlongTimeline, point: ReadAlongPosition,
                            completed: Bool, hrefs: BookHrefs = BookHrefs(readingOrder: [])) -> String {
        guard timeline.tracks.indices.contains(point.track), var object = BookLocator.object(locator) else { return locator }
        let track = timeline.tracks[point.track]
        let segment = timeline.active(track: point.track, offsetMs: point.offsetMs)
            ?? track.segments.last { $0.endMs <= track.startMs + point.offsetMs }
            ?? track.segments[0]
        var locations = object["locations"] as? [String: Any] ?? [:]
        locations.removeValue(forKey: "cssSelector")
        locations.removeValue(forKey: "pocketdsAudio")
        locations["fragments"] = [segment.fragment]
        if completed { locations["totalProgression"] = 1.0 }
        if (object["href"] as? String).map(BookHref.key) != segment.textHref {
            object["href"] = hrefs.readium(segment.textHref)
        }
        object["locations"] = locations
        object.removeValue(forKey: "text")
        return BookLocator.canonical(object) ?? locator
    }
}

/// Keeps the narration's place while Readium delivers its first page
/// callbacks on opening: those are the page Readium chose, not the reader
/// moving, and saving them would replace the place the narration opened at.
public struct ReadAlongSession: Sendable {
    private var preparing = false
    private var retained: ReadAlongPosition?

    public init() {}

    public mutating func beginOpen() { preparing = true }

    /// The narration is ready, at `resume` (nil for none).
    public mutating func ready(_ resume: ReadAlongPosition?) {
        retained = resume
        preparing = false
    }

    public mutating func endOpenIfPending() { preparing = false }

    /// A page may be saved: not while opening, and a page without narration
    /// may not replace a narration place kept.
    public func canSavePage(narrationAvailable: Bool = true) -> Bool {
        !preparing && (narrationAvailable || retained == nil)
    }

    /// The narration's place to save with the page: the moment playing, else the one kept.
    public func pointForSave(_ playing: ReadAlongPosition?) -> ReadAlongPosition? { playing ?? retained }

    public mutating func record(_ point: ReadAlongPosition) { retained = point }

    /// Reading on without the narration: its place is no longer kept.
    public mutating func switchToText() { retained = nil }
}

/// The sentence being read, as Readium draws it (#16, X7, #52): one opaque
/// wash of the Books accent under its words, as the Pocket draws it (0.4.23).
/// Pure strings and sums, so tests pin them; the reader gives the template to
/// Readium and runs the fitting script after each sentence. Android's
/// `ReadAlongGlow`.
///
/// Readium lays one box over each line of the sentence, as tall as the words
/// (their font's height), which at a tight spacing reaches into the next line's
/// and at a loose one leaves a gap. The owner's notes on #52:
/// - **Behind the words, in an opaque colour.** The boxes sit at `z-index: -1`,
///   under the page's text, and are the accent mixed into the page's colour
///   (`wash`): up to `most` of the way, less where the ink would lose its
///   contrast (Dim and Dark). No translucent layer over the text, so the words
///   keep their ink, and nothing is laid twice, so the tint is even.
/// - **Each row its own line, joined to the next.** The page script
///   (`ReadAlongPageScript.fitNarration`) makes each row of the sentence one box
///   as tall as its words and fills the gap to the sentence's next row with a
///   join only as wide as the two rows share, so the rows meet with no gap at
///   any spacing and the wash is never under another sentence's ink: at a tight
///   spacing a line box reaches into the descenders of the words before the
///   sentence on its first line, which a halfway join would have washed.
/// - **No glow and no ring:** any soft edge tinted the next sentence's first letters.
/// - **Corners** are square where two rows join and round only on the outside of the shape.
public enum ReadAlongGlow {
    public static let className = "pocket-narration"
    /// How far the wash goes from the page's colour towards the accent, at most.
    public static let most = 0.45
    /// The contrast the words keep against the wash (WCAG AA for body text).
    public static let contrast = 4.5
    /// How far the wash reaches past the words at either end of a row, in points.
    public static let side = 2
    /// The rounding of an outside corner, in points.
    public static let corner = 4
    /// How far a row's box stays inside its words' height where another
    /// sentence's line is above or below, in the book's em: past the descenders
    /// of the line above, which reach beyond their face's declared height.
    public static let overflow = 0.15

    /// The wash: `accent` mixed into `page` as far as `most`, or less, so
    /// that `ink` keeps `contrast` against it. Opaque.
    public static func wash(accent: UInt32, page: UInt32, ink: UInt32) -> UInt32 {
        var share = most
        while share > 0 {
            let mixed = GlassColors.mix(page | 0xFF00_0000, accent | 0xFF00_0000, share)
            if GlassColors.contrast(ink | 0xFF00_0000, mixed) >= contrast { return mixed }
            share -= 0.01
        }
        return page | 0xFF00_0000
    }

    /// Readium lays one of these over each line of the sentence and places
    /// it; the page script fits it to its line.
    public static func element(tint: UInt32) -> String {
        #"<div class="\#(className)"></div>"#
    }

    /// The boxes under the words, in the wash the page script sets
    /// (`--pocket-narration-wash`; clear until it has). Selected strongly enough
    /// to win over ReadiumCSS's rule that clears every element's background.
    public static func stylesheet(tint: UInt32) -> String {
        #"div[data-style="\#(className)"] > div.\#(className) { z-index: -1 !important; "#
            + "background-color: var(--pocket-narration-wash, transparent) !important; }"
    }

    public static func rgb(_ color: UInt32) -> String {
        "rgb(\((color >> 16) & 0xFF), \((color >> 8) & 0xFF), \(color & 0xFF))"
    }

    public static func rgba(_ color: UInt32, _ alpha: Double) -> String {
        "rgba(\((color >> 16) & 0xFF), \((color >> 8) & 0xFF), \(color & 0xFF), "
            + String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), alpha) + ")"
    }
}

extension ReadAlongPageScript {
    /// Fits the sentence's boxes to its lines and sets the wash (#52). Readium
    /// draws each box as tall as the words, and leaves the gap between lines
    /// open. This makes each row of the sentence one box, `side` points past
    /// its words, and fills the gap to the sentence's row below with a join as
    /// wide as the two rows share: the rows meet with no gap, and the join never
    /// reaches what is beside the sentence (the descenders of the words before
    /// it on its first line, the next sentence's capitals after it on its last).
    /// Where a row has another sentence's words above or below it, its box also
    /// stops short of their line's words' height (one line pitch away, the
    /// sentence's own or the line height), which a tight spacing makes overlap
    /// with its own, and `ReadAlongGlow.overflow` short of its own: a text
    /// face's descenders reach past the height it declares (Literata's by a
    /// tenth of an em), into the line below.
    /// Corners round only on the outside of the shape. Readium lays the boxes
    /// out again when the page reflows, and the script, which stays in the
    /// page, fits them again then.
    ///
    /// Each row is also trimmed across to the sentence's own words (#56):
    /// Storyteller's element for a sentence holds the space after it (and can
    /// hold one before it), which Readium's boxes cover. The script measures a
    /// Range from the first to the last character of the element `fragment`
    /// that is not a space, and a row takes the left and right of that
    /// Range's boxes on its line, `side` points of air beyond them; a row with
    /// none of them (a line holding only the space) gets no box. Without the
    /// element on the page, Readium's own extents stand.
    public static func fitNarration(wash: UInt32, fragment: String? = nil) -> String {
        let colour = ReadAlongGlow.rgb(wash)
        let side = ReadAlongGlow.side
        let corner = ReadAlongGlow.corner
        let name = ReadAlongGlow.className
        return "(function(){document.documentElement.style.setProperty('--pocket-narration-wash','\(colour)');"
            + "window.__pocketNarrationId=\(json(fragment ?? ""));"
            + "if(window.__pocketNarration){window.__pocketNarration();return true;}"
            + "function words(){var el=window.__pocketNarrationId?document.getElementById(window.__pocketNarrationId):null;"
            + "if(!el)return null;var w=document.createTreeWalker(el,NodeFilter.SHOW_TEXT,null),n,a=null,z=null;"
            + "while((n=w.nextNode())){var s=n.data;for(var i=0;i<s.length;i++){if(/\\S/.test(s.charAt(i))){if(!a)a=[n,i];z=[n,i+1];}}}"
            + "if(!a)return [];var g=document.createRange();g.setStart(a[0],a[1]);g.setEnd(z[0],z[1]);"
            + "var se=document.scrollingElement||document.documentElement,ox=se.scrollLeft,oy=se.scrollTop;"
            + "return Array.prototype.slice.call(g.getClientRects()).filter(function(q){return q.width>0&&q.height>0;})"
            + ".map(function(q){return{l:q.left+ox,r:q.right+ox,c:(q.top+q.bottom)/2+oy};});}"
            + "function fit(){var items=document.querySelectorAll('div[data-style=\"\(name)\"]'),own=words();"
            + "for(var n=0;n<items.length;n++){var item=items[n];"
            + "Array.prototype.slice.call(item.querySelectorAll('[data-join]')).forEach(function(j){j.remove();});"
            + "var rows=[];Array.prototype.slice.call(item.children).forEach(function(b){"
            + "if(b.dataset.t===undefined){b.dataset.t=parseFloat(b.style.top);b.dataset.h=parseFloat(b.style.height);"
            + "b.dataset.l=parseFloat(b.style.left);b.dataset.w=parseFloat(b.style.width);}"
            + "b.style.display='';"
            + "var t=+b.dataset.t,h=+b.dataset.h,l=+b.dataset.l,w=+b.dataset.w,c=t+h/2,row=null;"
            + "for(var k=0;k<rows.length;k++){if(Math.abs(rows[k].c-c)<Math.min(rows[k].h,h)/2){row=rows[k];break;}}"
            + "if(row){row.boxes.push(b);row.l=Math.min(row.l,l)-0;row.r=Math.max(row.r,l+w);"
            + "row.t=Math.min(row.t,t);row.b=Math.max(row.b,t+h);}"
            + "else rows.push({c:c,h:h,t:t,b:t+h,l:l,r:l+w,boxes:[b]});});"
            + "if(own){rows=rows.filter(function(r){var on=own.filter(function(q){return Math.abs(q.c-r.c)<r.h/2;});"
            + "if(!on.length){r.boxes.forEach(function(b){b.style.display='none';});return false;}"
            + "r.l=Math.min.apply(null,on.map(function(q){return q.l;}));r.r=Math.max.apply(null,on.map(function(q){return q.r;}));"
            + "return true;});}"
            + "rows.forEach(function(r){r.l-=\(side);r.r+=\(side);});"
            + "function near(a,b){return b.r>a.l&&b.l<a.r&&Math.abs(a.c-b.c)<2.2*Math.max(a.h,b.h);}"
            + "rows.forEach(function(r){r.up=null;r.down=null;});"
            + "rows.forEach(function(r){rows.forEach(function(o){if(o===r||!near(r,o))return;"
            + "if(o.c>r.c&&(!r.down||o.c<r.down.c))r.down=o;});if(r.down&&(!r.down.up||r.c>r.down.up.c))r.down.up=r;});"
            + "var lh=parseFloat(getComputedStyle(item).lineHeight),steps=[];rows.forEach(function(r){if(r.down)steps.push(r.down.c-r.c);});"
            + "var a=\(ReadAlongGlow.overflow)*(parseFloat(getComputedStyle(item).fontSize)||16);"
            + "var p=steps.length?steps.sort(function(a,b){return a-b;})[steps.length>>1]:(lh>0?lh:rows[0].h);"
            + "rows.forEach(function(r){var up=r.up,down=r.down;"
            + "var tl=!up||up.l>r.l+1,tr=!up||up.r<r.r-1,bl=!down||down.l>r.l+1,br=!down||down.r<r.r-1;"
            + "r.t=(tl||tr)?Math.max(r.t+a,r.c-p+r.h/2+1):r.t;r.b=(bl||br)?Math.min(r.b-a,r.c+p-r.h/2-1):r.b;});"
            + "rows.forEach(function(r){var up=r.up,down=r.down;"
            + "var tl=!up||up.l>r.l+1,tr=!up||up.r<r.r-1,bl=!down||down.l>r.l+1,br=!down||down.r<r.r-1;"
            + "r.boxes.forEach(function(b,i){if(i>0){b.style.display='none';return;}"
            + "b.style.top=r.t+'px';b.style.height=(r.b-r.t)+'px';b.style.left=r.l+'px';b.style.width=(r.r-r.l)+'px';"
            + "b.style.borderRadius=(tl?'\(corner)px ':'0 ')+(tr?'\(corner)px ':'0 ')+(br?'\(corner)px ':'0 ')+(bl?'\(corner)px':'0');});"
            + "if(down&&down.t>r.b){var j=r.boxes[0].cloneNode(false),jl=Math.max(r.l,down.l),jr=Math.min(r.r,down.r);"
            + "if(jr>jl){j.dataset.join='1';j.removeAttribute('data-t');j.style.display='';j.style.borderRadius='0';"
            + "j.style.top=(r.b-0.5)+'px';j.style.height=(down.t-r.b+1)+'px';j.style.left=jl+'px';j.style.width=(jr-jl)+'px';"
            + "item.appendChild(j);}}});}}"
            + "window.__pocketNarration=fit;"
            + "new MutationObserver(function(list){for(var m=0;m<list.length;m++){for(var a=0;a<list[m].addedNodes.length;a++){"
            + "var node=list[m].addedNodes[a];if(!(node.dataset&&node.dataset.join)){fit();return;}}}})"
            + ".observe(document.body,{childList:true,subtree:true});fit();return true;})()"
    }
}

extension ReadAlongTimeline {
    /// The narrated sentences of `href`, in the order they are read, each
    /// once: what "Listen from this page" looks for on the page. `href` as
    /// Readium spells it (#61).
    public func fragments(in href: String) -> [String] {
        let key = BookHref.key(href)
        var seen = Set<String>()
        return tracks.flatMap(\.segments).filter { $0.textHref == key && seen.insert($0.fragment).inserted }.map(\.fragment)
    }
}

/// What the page is asked while it reads along (Android's `EpubReaderScreen`):
/// scripts run in the page's own web view. Ids go in as JSON, so an id with a
/// quote or a backslash in it cannot break out of its string.
public enum ReadAlongPageScript {
    /// True while the element `fragment` is on screen: the page follows the
    /// voice only when the sentence spoken is not.
    public static func visible(_ fragment: String) -> String {
        "(function(){var e=document.getElementById(\(json(fragment)));if(!e)return false;"
            + "var r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;})()"
    }

    /// The first of `ids` on screen, or null: where "Listen from this page" starts.
    public static func firstVisible(_ ids: [String]) -> String {
        "(function(){var ids=\(json(ids));for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);"
            + "if(e){var r=e.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth)return ids[i];}}"
            + "return null;})()"
    }

    static func json(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject([value]),
              let data = try? JSONSerialization.data(withJSONObject: [value], options: [.withoutEscapingSlashes]),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        // The value alone, out of the array it was written in.
        return String(text.dropFirst().dropLast())
    }
}

extension TimeLeft {
    /// Reading along with the page following the voice (#18, E3): what the
    /// narration has left to say in the chapter being read (the sentence's
    /// file) and in the book, at its speed. Nil for a moment the timeline
    /// does not hold. Android's `TimeLeft.ofNarration`.
    public static func ofNarration(_ timeline: ReadAlongTimeline, _ position: ReadAlongPosition, speed: Float) -> TimeLeft? {
        guard timeline.tracks.indices.contains(position.track) else { return nil }
        let track = timeline.tracks[position.track]
        let now = track.startMs + max(0, position.offsetMs)
        guard let chapter = (timeline.active(track: position.track, offsetMs: position.offsetMs)
            ?? track.segments.first { $0.endMs > now })?.textHref else { return nil }
        var inChapter: Int64 = 0
        var inBook: Int64 = 0
        for (index, value) in timeline.tracks.enumerated() where index >= position.track {
            for segment in value.segments {
                let from = index == position.track ? max(segment.beginMs, now) : segment.beginMs
                let left = segment.endMs - from
                guard left > 0 else { continue }
                inBook += left
                if segment.textHref == chapter { inChapter += left }
            }
        }
        return TimeLeft(chapterMs: Listening.heard(inChapter, speed: speed), bookMs: Listening.heard(inBook, speed: speed))
    }
}
