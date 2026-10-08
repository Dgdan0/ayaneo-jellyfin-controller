import Foundation

// The voice and the page move each other (#49): the narration turns the page
// when the voice reaches the next page's first word, and a page turned by
// hand while it plays takes the voice to its own first word. Storyteller
// times whole sentences (its worker asks for `granularity: "sentence"`), so a
// page that begins or ends inside a sentence is placed inside the sentence's
// clip by the share of its letters before that word. The page's script only
// says where its first and last characters are (`ReadAlongPageScript.edges`);
// everything that follows from them is here. Aligned word by word, every
// sentence is a word and the same sums are exact.

extension ReadAlongPosition: Comparable {
    /// In the order the narration is heard: by stretch, then by moment.
    public static func < (left: ReadAlongPosition, right: ReadAlongPosition) -> Bool {
        left.track != right.track ? left.track < right.track : left.offsetMs < right.offsetMs
    }
}

/// A character of a narrated sentence, as the page reports it: the
/// sentence's element (`fragment`), its words as the page has them, and where
/// in them, counted in UTF-16 units as the page's script counts.
public struct ReadAlongCaret: Equatable, Sendable {
    public let fragment: String
    public let text: String
    public let offset: Int

    public init(fragment: String, text: String, offset: Int) {
        self.fragment = fragment
        self.text = text
        self.offset = offset
    }
}

/// Where the page on screen begins and ends among the narrated sentences:
/// `first` is at the page's first character, `last` just after its last one.
/// In two columns the page is the spread.
public struct ReadAlongPageEdges: Equatable, Sendable {
    public let first: ReadAlongCaret
    public let last: ReadAlongCaret

    public init(first: ReadAlongCaret, last: ReadAlongCaret) {
        self.first = first
        self.last = last
    }

    /// The page script's answer: `{"first": {"id", "text", "offset"}, "last": {…}}`,
    /// nil when nothing narrated is on the page. An offset the script could not
    /// place (-1) is the sentence's start for `first` and its end for `last`.
    public static func parse(_ answer: Any?) -> ReadAlongPageEdges? {
        guard let json = answer as? String, let data = json.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        func caret(_ key: String, end: Bool) -> ReadAlongCaret? {
            guard let value = object[key] as? [String: Any], let id = value["id"] as? String, !id.isEmpty else { return nil }
            let text = value["text"] as? String ?? ""
            let offset = (value["offset"] as? NSNumber)?.intValue ?? -1
            let length = text.utf16.count
            return ReadAlongCaret(fragment: id, text: text, offset: offset < 0 ? (end ? length : 0) : min(offset, length))
        }
        guard let first = caret("first", end: false), let last = caret("last", end: true) else { return nil }
        return ReadAlongPageEdges(first: first, last: last)
    }
}

/// A sentence's words, for placing a moment inside its clip.
public enum ReadAlongWords {
    /// Where the word at `offset` of `text` starts, in UTF-16 units: back to
    /// its beginning when `offset` is inside it (a word broken over the page
    /// is heard from its start), on to the next word when `offset` is in the
    /// space between two. Nil when no word follows.
    public static func wordStart(_ text: String, at offset: Int) -> Int? {
        let spaces = spaceMap(text)
        guard offset >= 0, offset < spaces.count else { return nil }
        var index = offset
        if spaces[index] {
            while index < spaces.count && spaces[index] { index += 1 }
            return index < spaces.count ? index : nil
        }
        while index > 0 && !spaces[index - 1] { index -= 1 }
        return index
    }

    /// The share of `text`'s letters before `offset`: letters, not spaces,
    /// since a voice takes no longer over a line's end than over a word.
    public static func share(_ text: String, before offset: Int) -> Double {
        let spaces = spaceMap(text)
        let letters = spaces.filter { !$0 }.count
        guard letters > 0 else { return 0 }
        let before = spaces.prefix(max(0, min(offset, spaces.count))).filter { !$0 }.count
        return Double(before) / Double(letters)
    }

    /// Each UTF-16 unit of `text`: whether it is a space. Both halves of a
    /// character outside the basic plane are a letter.
    private static func spaceMap(_ text: String) -> [Bool] {
        var out: [Bool] = []
        out.reserveCapacity(text.utf16.count)
        for scalar in text.unicodeScalars {
            let space = scalar.properties.isWhitespace
            out.append(contentsOf: Array(repeating: space, count: scalar.utf16.count))
        }
        return out
    }
}

/// A page as the narration sees it: when the voice says its first word and
/// the next page's, and which sentences it holds.
public struct ReadAlongPageSpan: Equatable, Sendable {
    /// Where the voice says the page's first word.
    public let start: ReadAlongPosition
    /// Where it says the next page's first word: the page turns there. Nil
    /// when the narration ends on this page.
    public let end: ReadAlongPosition?
    /// Where the page's first sentence begins: before `start` when it began on the page before.
    public let firstSentence: ReadAlongPosition
    /// Where the page's last sentence begins.
    public let lastSentence: ReadAlongPosition
    /// Where the sentence the next page begins with ends: until then, one
    /// page on shows the voice.
    public let nextSentenceEnd: ReadAlongPosition?

    public init(start: ReadAlongPosition, end: ReadAlongPosition?, firstSentence: ReadAlongPosition,
                lastSentence: ReadAlongPosition, nextSentenceEnd: ReadAlongPosition?) {
        self.start = start
        self.end = end
        self.firstSentence = firstSentence
        self.lastSentence = lastSentence
        self.nextSentenceEnd = nextSentenceEnd
    }

    /// The voice at `now` is on this page.
    public func shows(_ now: ReadAlongPosition) -> Bool {
        now >= start && (end.map { now < $0 } ?? true)
    }

    /// The sentence that begins at `begin` is on this page, some of it at least.
    public func holds(sentenceBeginning begin: ReadAlongPosition) -> Bool {
        begin >= firstSentence && begin <= lastSentence
    }
}

extension ReadAlongTimeline {
    /// A page of `href` as the narration sees it, from where its first and
    /// last characters are. A moment inside a sentence is its clip's start
    /// plus the share of its letters before the word, of the clip's length:
    /// `clipBegin + share × (clipEnd − clipBegin)`. Nil when the page's
    /// sentences are not the narration's.
    public func span(href: String, edges: ReadAlongPageEdges) -> ReadAlongPageSpan? {
        guard let first = locate(href: href, fragment: edges.first.fragment),
              let last = locate(href: href, fragment: edges.last.fragment) else { return nil }
        let opening = segment(first)
        let closing = segment(last)
        let start = moment(first, ReadAlongWords.wordStart(edges.first.text, at: edges.first.offset)
            .map { ReadAlongWords.share(edges.first.text, before: $0) } ?? 0)
        var end: ReadAlongPosition?
        var nextSentenceEnd: ReadAlongPosition?
        if let word = ReadAlongWords.wordStart(edges.last.text, at: edges.last.offset) {
            // The next page begins inside the page's last sentence.
            end = moment(last, ReadAlongWords.share(edges.last.text, before: word))
            nextSentenceEnd = position(last.track, closing.endMs)
        } else if let next = after(last) {
            // The page ends with its last sentence: the next one begins the next page.
            end = position(next.track, segment(next).beginMs)
            nextSentenceEnd = position(next.track, segment(next).endMs)
        }
        return ReadAlongPageSpan(start: start, end: end, firstSentence: position(first.track, opening.beginMs),
                                 lastSentence: position(last.track, closing.beginMs), nextSentenceEnd: nextSentenceEnd)
    }

    /// Where the sentence `segment` begins.
    public func begin(of segment: ReadAlongSegment) -> ReadAlongPosition? {
        locate(href: segment.textHref, fragment: segment.fragment).map { position($0.track, self.segment($0).beginMs) }
    }

    /// The sentence spoken at `now`, else the next one to be (in a pause
    /// between two), else the last: the one the page goes to for the voice.
    public func sentence(atOrAfter now: ReadAlongPosition) -> ReadAlongSegment? {
        if let spoken = active(track: now.track, offsetMs: now.offsetMs) { return spoken }
        for (index, track) in tracks.enumerated() where index >= now.track {
            let absolute = index == now.track ? track.startMs + now.offsetMs : Int64.min
            if let next = track.segments.first(where: { $0.beginMs >= absolute }) { return next }
        }
        return tracks.last?.segments.last
    }

    /// The first sentence after every sentence of `href`, in the order the
    /// narration reads: where the voice goes on from past the end of a part.
    public func sentence(after href: String) -> ReadAlongSegment? {
        let all = tracks.flatMap(\.segments)
        guard let last = all.lastIndex(where: { $0.textHref == href }), last + 1 < all.count else { return nil }
        return all[last + 1]
    }

    // MARK: Inside

    private struct Place {
        let track: Int
        let index: Int
    }

    private func locate(href: String, fragment: String) -> Place? {
        for (track, value) in tracks.enumerated() {
            if let index = value.segments.firstIndex(where: { $0.textHref == href && $0.fragment == fragment }) {
                return Place(track: track, index: index)
            }
        }
        return nil
    }

    private func segment(_ place: Place) -> ReadAlongSegment { tracks[place.track].segments[place.index] }

    /// The sentence after `place`, across stretches; nil after the last.
    private func after(_ place: Place) -> Place? {
        if place.index + 1 < tracks[place.track].segments.count { return Place(track: place.track, index: place.index + 1) }
        return place.track + 1 < tracks.count ? Place(track: place.track + 1, index: 0) : nil
    }

    private func position(_ track: Int, _ ms: Int64) -> ReadAlongPosition {
        ReadAlongPosition(track: track, offsetMs: ms - tracks[track].startMs)
    }

    /// `share` of the way through the sentence at `place`.
    private func moment(_ place: Place, _ share: Double) -> ReadAlongPosition {
        let sentence = segment(place)
        let length = Double(sentence.endMs - sentence.beginMs)
        return position(place.track, sentence.beginMs + Int64((min(max(share, 0), 1) * length).rounded()))
    }
}

/// What the page does with the voice (#49). The "Reading" state, where the
/// page stayed where the hand left it while the voice read on elsewhere, is
/// gone: while the narration plays, the page and the voice are together.
public enum ReadAlongPageFollow {
    /// The page for the voice at a moment.
    public enum Move: Equatable, Sendable {
        /// The page shows the voice.
        case stay
        /// The voice has gone on into the next page (a turn, as a reader turns it).
        case forward
        /// The voice is in the page before, in the sentence the page begins with.
        case backward
        /// The voice is elsewhere: the page goes to its sentence.
        case go
    }

    /// Where the page goes for the voice at `now`; `span` is the page on
    /// screen, nil when nothing on it is narrated.
    public static func move(_ now: ReadAlongPosition, on span: ReadAlongPageSpan?) -> Move {
        guard let span else { return .go }
        if now < span.start { return now >= span.firstSentence ? .backward : .go }
        guard let end = span.end, now >= end else { return .stay }
        if let limit = span.nextSentenceEnd, now < limit { return .forward }
        return .go
    }

    /// What a page moved by hand does while the voice reads.
    public enum HandMove: Equatable, Sendable {
        /// The voice reads on, and turns the page at `turnAt` (nil: not this page).
        case stay(turnAt: ReadAlongPosition?)
        /// The voice goes to the page's first word and reads on from there.
        case seek(ReadAlongPosition)
        /// Nothing on the page is narrated.
        case unnarrated
    }

    /// A page turned or jumped to by hand while the voice reads at `now`, in
    /// the sentence beginning at `speaking` (nil in a pause between two). When
    /// that sentence is still on the page (turned a little early, onto the
    /// page it goes on to), nothing restarts; otherwise the voice goes to the
    /// page's first word. Turned back onto a sentence the voice has already
    /// read past the page's end, the page waits for the next sentence rather
    /// than turning straight back.
    public static func handMoved(_ now: ReadAlongPosition, speaking: ReadAlongPosition?,
                                 on span: ReadAlongPageSpan?) -> HandMove {
        guard let span else { return .unnarrated }
        if span.shows(now) { return .stay(turnAt: span.end) }
        if let speaking, span.holds(sentenceBeginning: speaking) {
            return .stay(turnAt: now < span.start ? span.end : nil)
        }
        return .seek(span.start)
    }
}

extension ReadAlongPageScript {
    /// The first of `ids` (in reading order) that lies wholly beyond the page
    /// on screen: where the narration goes on from a page that shows none of
    /// them, as a part's heading page at a large size does. "" when every one
    /// is before the page (the part's narration is behind it), null when none
    /// is laid out yet.
    public static func firstAfter(_ ids: [String]) -> String {
        "(function(){var ids=\(json(ids)),W=innerWidth,H=innerHeight,laid=false;for(var i=0;i<ids.length;i++){"
            + "var e=document.getElementById(ids[i]);if(!e)continue;var b=e.getClientRects();if(!b.length)continue;laid=true;"
            + "if(b[0].left>=W||b[0].top>=H)return ids[i];}return laid?'':null;})()"
    }

    /// Where the page on screen begins and ends among the narrated sentences
    /// `ids` (in reading order): the first and the last of them on screen, the
    /// offset of the first visible character of the first and the offset just
    /// after the last visible character of the last, as JSON
    /// (`ReadAlongPageEdges.parse`), or null. A character is on screen when
    /// any of its boxes is: in columns, the whole spread is the page.
    public static func edges(_ ids: [String]) -> String {
        "(function(){var ids=\(json(ids)),W=innerWidth,H=innerHeight;"
            + "function on(r){return(r.width>0||r.height>0)&&r.right>0&&r.left<W&&r.bottom>0&&r.top<H;}"
            + "function seen(e){var b=e.getClientRects();for(var i=0;i<b.length;i++)if(on(b[i]))return true;return false;}"
            + "function at(e,last){var w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT,null),n,ns=[],t=0;"
            + "while((n=w.nextNode())){ns.push([n,t]);t+=n.data.length;}var r=document.createRange();"
            + "for(var k=0;k<ns.length;k++){var p=ns[last?ns.length-1-k:k],s=p[0].data;"
            + "for(var m=0;m<s.length;m++){var j=last?s.length-1-m:m;if(/\\s/.test(s.charAt(j)))continue;"
            + "r.setStart(p[0],j);r.setEnd(p[0],j+1);var b=r.getClientRects();"
            + "for(var q=0;q<b.length;q++)if(on(b[q]))return p[1]+j+(last?1:0);}}return -1;}"
            + "var a=null,z=null;for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(!e)continue;"
            + "if(seen(e)){if(!a)a=e;z=e;}else if(a)break;}"
            + "if(!a)return null;function cut(e,last){return{id:e.id,text:e.textContent,offset:at(e,last)};}"
            + "return JSON.stringify({first:cut(a,false),last:cut(z,true)});})()"
    }
}
