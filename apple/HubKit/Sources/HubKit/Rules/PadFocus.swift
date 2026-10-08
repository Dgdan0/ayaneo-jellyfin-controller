import CoreGraphics
import Foundation

// A controller or keyboard driving every screen (#46, C): the Pocket's focus
// rules on plain rectangles and ids, so every screen shares them and they are
// tested here. From Android:
// - `input/FocusGuard`: a candidate must really lie the way pressed, and left
//   and right stay in their band (`PadGuard`);
// - `ui/StripNav`: a row moves by position, never by a search, so a card a
//   lazy stack has not drawn yet is still next (`PadLayout.row`);
// - `ui/RowStep`: up and down go row by row, past an empty one, to the card
//   nearest the one left (`PadLayout.column`);
// - `ui/FocusPlace`: where Back and a tab return focus (`PadMemory`);
// - `input/InputModeTracker`: the ring only while a controller or a keyboard
//   is in use (`PadInput`).
// A page registers its focusable items' frames and the groups that order
// them (`PadMap`); `PadFocus.step` says where a press goes, and `PadReveal`
// how far to scroll so it can be seen.

// MARK: A page

/// How a group's members sit, which decides how a press moves among them.
public enum PadLayout: Equatable, Sendable {
    /// Side by side: a strip of cards, a row of tabs or buttons. Left and right
    /// go by position, and at either end the press does nothing (rows are left
    /// with up and down) unless a row round it goes on; up and down leave the row.
    case row
    /// One above another: a page's rows, a list. Up and down go by position,
    /// past a member with nothing in it; past either end, and left and right,
    /// leave the column.
    case column
    /// Lines `columns` wide (0: counted from the members laid out). Left and
    /// right go by position, so they wrap onto the next or the previous line
    /// and stop at the first and last member; up and down go a line at a time
    /// (the last member when the last line is shorter) and leave the grid
    /// past its first or last line.
    case grid(columns: Int)
}

/// Members moved through in their order: item ids, or other groups' ids. All of
/// them, laid out or not, so a press reaches a card a lazy stack has not drawn
/// yet (the page scrolls it in, and it takes focus as it appears).
public struct PadGroup: Equatable, Sendable {
    public var id: String
    public var layout: PadLayout
    public var members: [String]

    public init(_ id: String, _ layout: PadLayout, members: [String]) {
        self.id = id
        self.layout = layout
        self.members = members
    }
}

/// Left and right outside a group: the Pocket's `HorizontalMode`.
public enum PadHorizontal: Equatable, Sendable {
    /// They stay in the band they started in.
    case confined
    /// They may fall onto the next line (right) or the previous one (left).
    case grid
}

/// A page as the engine sees it: each focusable item laid out now, by id, with
/// its frame in one coordinate space (the page's scroll content, so an item
/// scrolled away keeps its place), and the groups that order them. Anything
/// in no group is reached by looking the way pressed.
public struct PadMap: Equatable, Sendable {
    public var frames: [String: CGRect]
    public var groups: [PadGroup]
    public var horizontal: PadHorizontal

    public init(frames: [String: CGRect] = [:], groups: [PadGroup] = [], horizontal: PadHorizontal = .confined) {
        self.frames = frames
        self.groups = groups
        self.horizontal = horizontal
    }

    /// Whether `id` is on the page: laid out, or a member of a group.
    public func contains(_ id: String) -> Bool {
        frames[id] != nil || (group(id) == nil && parent(of: id) != nil)
    }

    func group(_ id: String) -> PadGroup? {
        groups.first { $0.id == id }
    }

    /// The group `member` sits in directly: a focused card's row, whose frame
    /// a page pins to the top.
    public func parent(of member: String) -> PadGroup? {
        groups.first { $0.members.contains(member) }
    }

    /// Every item under `member`: itself when it is an item.
    func leaves(_ member: String, seen: Set<String> = []) -> [String] {
        guard let group = group(member) else { return [member] }
        guard !seen.contains(member) else { return [] }
        return group.members.flatMap { leaves($0, seen: seen.union([member])) }
    }

    /// The frame round what is laid out of `member` (an item, or a group).
    public func frame(_ member: String) -> CGRect? {
        let laid = leaves(member).compactMap { frames[$0] }
        guard let first = laid.first else { return nil }
        return laid.dropFirst().reduce(first) { $0.union($1) }
    }
}

/// Where a press goes.
public enum PadStep: Equatable, Sendable {
    /// Focus moves to this item. It may not be laid out yet (a lazy row's next
    /// card): the page scrolls it into view, and it takes focus as it appears.
    case to(String)
    /// The press is the page's, and nothing is that way: the end of a row or a
    /// grid. Nothing happens, which is right while a row loads more.
    case stay
    /// Nothing on the page lies that way: the press may leave it (up from the
    /// first row to the tabs).
    case leave
}

public enum PadFocus {
    /// Where `direction` goes from `from`. With nothing focused (or focus on
    /// something the page no longer has), the page's first item: a press must
    /// never do nothing just because nothing had focus yet.
    public static func step(from: String?, _ direction: PadDirection, in map: PadMap,
                            memory: PadMemory = PadMemory()) -> PadStep {
        guard let from, map.frames[from] != nil || map.parent(of: from) != nil else {
            return first(in: map).map(PadStep.to) ?? .leave
        }
        let source = map.frames[from]
        var current = from
        var seen: Set<String> = []
        // The end of a row or a grid: a group round it that goes that way may
        // still move on (a row of rows), else the press stays the page's.
        var atEnd = false
        while let group = map.parent(of: current), !seen.contains(group.id) {
            seen.insert(group.id)
            switch step(in: group, at: current, direction, map: map, source: source, memory: memory) {
            case .stay?: atEnd = true
            case let step?: return step
            case nil: break
            }
            current = group.id
        }
        return atEnd ? .stay : search(from: from, source, direction, in: map)
    }

    /// The page's first item in reading order: the top line, then the left.
    public static func first(in map: PadMap) -> String? {
        let byLeft: ((key: String, value: CGRect), (key: String, value: CGRect)) -> Bool = { a, b in
            a.value.minX != b.value.minX ? a.value.minX < b.value.minX : a.key < b.key
        }
        let highest = map.frames.min { a, b in a.value.minY != b.value.minY ? a.value.minY < b.value.minY : byLeft(a, b) }
        if let top = highest?.value {
            // The top line, read from the left.
            return map.frames.filter { PadGuard.overlapsVertically($0.value, top) }.min(by: byLeft)?.key
        }
        // Nothing laid out yet: the first member of the outermost group.
        let outer = map.groups.first { map.parent(of: $0.id) == nil }
        return outer.flatMap { map.leaves($0.id).first }
    }

    // MARK: Groups

    /// A press inside `group` from its member `current`: a step, or nil when it leaves the group.
    private static func step(in group: PadGroup, at current: String, _ direction: PadDirection, map: PadMap,
                             source: CGRect?, memory: PadMemory) -> PadStep? {
        guard let index = group.members.firstIndex(of: current) else { return nil }
        let forward = direction == .right || direction == .down
        let horizontal = direction == .left || direction == .right
        switch group.layout {
        case .row:
            guard horizontal else { return nil }
            guard let next = nonEmpty(group, after: index, forward: forward, map: map) else { return .stay }
            return .to(enter(next, direction, map: map, source: source, memory: memory))
        case .column:
            guard !horizontal, let next = nonEmpty(group, after: index, forward: forward, map: map) else { return nil }
            return .to(enter(next, direction, map: map, source: source, memory: memory))
        case .grid(let wanted):
            if horizontal {
                guard let next = nonEmpty(group, after: index, forward: forward, map: map) else { return .stay }
                return .to(enter(next, direction, map: map, source: source, memory: memory))
            }
            let columns = wanted > 0 ? wanted : laidColumns(group, map: map)
            let count = group.members.count
            if forward {
                if index + columns < count {
                    return .to(enter(group.members[index + columns], direction, map: map, source: source, memory: memory))
                }
                // A shorter last line below: its last member.
                guard (count - 1) / columns > index / columns else { return nil }
                return .to(enter(group.members[count - 1], direction, map: map, source: source, memory: memory))
            }
            guard index - columns >= 0 else { return nil }
            return .to(enter(group.members[index - columns], direction, map: map, source: source, memory: memory))
        }
    }

    /// The next member of `group` past `index` with something to land on.
    private static func nonEmpty(_ group: PadGroup, after index: Int, forward: Bool, map: PadMap) -> String? {
        var next = index + (forward ? 1 : -1)
        while group.members.indices.contains(next) {
            if !map.leaves(group.members[next]).isEmpty { return group.members[next] }
            next += forward ? 1 : -1
        }
        return nil
    }

    /// A grid's width from its members laid out: its widest line.
    private static func laidColumns(_ group: PadGroup, map: PadMap) -> Int {
        let laid = group.members.compactMap { map.frames[$0] }
        let widest = laid.map { line in laid.filter { PadGuard.overlapsVertically($0, line) }.count }.max() ?? 1
        return max(widest, 1)
    }

    /// The item to land on in `member`, reached going `direction`: an item is
    /// itself; a group is entered at its near end along its own axis, or else
    /// at the member laid out nearest across (the card nearest the one left,
    /// as RowStep), or, with none laid out, where it was last left.
    static func enter(_ member: String, _ direction: PadDirection, map: PadMap, source: CGRect?,
                      memory: PadMemory, depth: Int = 0) -> String {
        guard let group = map.group(member), depth < 16 else { return member }
        let horizontal = direction == .left || direction == .right
        let forward = direction == .right || direction == .down
        let members = group.members.filter { !map.leaves($0).isEmpty }
        guard !members.isEmpty else { return member }
        func into(_ next: String) -> String {
            enter(next, direction, map: map, source: source, memory: memory, depth: depth + 1)
        }
        switch (group.layout, horizontal) {
        case (.column, false), (.row, true), (.grid, true):
            return into((forward ? members.first : members.last)!)
        case (.grid(let wanted), false):
            // The near line, at the member nearest across.
            let columns = wanted > 0 ? wanted : laidColumns(group, map: map)
            let all = group.members
            let line = forward ? Array(all.prefix(columns)) : Array(all.suffix(from: ((all.count - 1) / columns) * columns))
            return into(nearest(line, across: direction, map: map, source: source) ?? line.first ?? members[0])
        default:
            if let near = nearest(members, across: direction, map: map, source: source) { return into(near) }
            if let last = memory.member(in: group.id), members.contains(last) { return into(last) }
            return into(members[0])
        }
    }

    /// Of `members`, the one laid out nearest `source` across the way pressed.
    private static func nearest(_ members: [String], across direction: PadDirection, map: PadMap, source: CGRect?) -> String? {
        guard let source else { return nil }
        let vertical = direction == .up || direction == .down
        return members.compactMap { member in map.frame(member).map { (member, $0) } }.min { a, b in
            let da = vertical ? abs(a.1.midX - source.midX) : abs(a.1.midY - source.midY)
            let db = vertical ? abs(b.1.midX - source.midX) : abs(b.1.midY - source.midY)
            return da < db
        }?.0
    }

    // MARK: Looking the way pressed

    /// The item laid out that lies `direction` from `source`: up and down, the
    /// nearest line that way, at the item nearest across; left and right, the
    /// nearest in the band (`PadGuard`), or on a grid page the next line's first
    /// or the previous line's last.
    static func search(from: String, _ source: CGRect?, _ direction: PadDirection, in map: PadMap) -> PadStep {
        guard let source else { return .leave }
        let candidates = map.frames.filter { id, frame in
            guard id != from, PadGuard.accepts(direction, from: source, to: frame, mode: map.horizontal) else { return false }
            // Off the band (a grid page's next or previous line) is the guard's to allow.
            let vertical = direction == .up || direction == .down
            return !vertical && !PadGuard.overlapsVertically(source, frame) || PadGuard.lies(direction, from: source, to: frame)
        }
        guard !candidates.isEmpty else { return .leave }
        func ordered(_ a: (key: String, value: CGRect), _ b: (key: String, value: CGRect),
                     _ measure: (CGRect) -> CGFloat) -> Bool {
            let (ma, mb) = (measure(a.value), measure(b.value))
            return ma != mb ? ma < mb : a.key < b.key
        }
        switch direction {
        case .up, .down:
            let gap: (CGRect) -> CGFloat = { direction == .down ? $0.minY - source.maxY : source.minY - $0.maxY }
            guard let closest = candidates.min(by: { ordered($0, $1, gap) }) else { return .leave }
            let line = candidates.filter { PadGuard.overlapsVertically($0.value, closest.value) }
            return line.min { ordered($0, $1) { abs($0.midX - source.midX) } }.map { .to($0.key) } ?? .leave
        case .left, .right:
            let band = candidates.filter { PadGuard.overlapsVertically($0.value, source) }
            if !band.isEmpty {
                let gap: (CGRect) -> CGFloat = { direction == .right ? $0.midX - source.midX : source.midX - $0.midX }
                return band.min { ordered($0, $1, gap) }.map { .to($0.key) } ?? .leave
            }
            // Off the line on a grid page: the next line's first, the previous line's last.
            let lineGap: (CGRect) -> CGFloat = { direction == .right ? $0.minY : -$0.maxY }
            guard let closest = candidates.min(by: { ordered($0, $1, lineGap) }) else { return .leave }
            let line = candidates.filter { PadGuard.overlapsVertically($0.value, closest.value) }
            return line.min { ordered($0, $1) { direction == .right ? $0.minX : -$0.maxX } }.map { .to($0.key) } ?? .leave
        }
    }
}

// MARK: The guard

/// Android's `input/FocusGuard`: whether a press may land on a candidate.
/// Left and right only land in the band they started in (a strip, a tab bar),
/// or on a grid page the next line down (right) or the previous one up (left),
/// never a wrap to the start of the same line nor an escape to the tabs above.
/// Up and down are never second-guessed.
public enum PadGuard {
    /// How much of the candidate may sit behind the current position before
    /// the move counts as backwards: half the current width, so a neighbour a
    /// few points out of line is still next and a real wrap is not.
    static let slackFraction: CGFloat = 0.5

    public static func accepts(_ direction: PadDirection, from: CGRect, to: CGRect,
                               mode: PadHorizontal = .confined) -> Bool {
        switch direction {
        case .up, .down:
            return true
        case .right:
            if overlapsVertically(from, to) { return to.minX > from.minX - from.width * slackFraction }
            return mode == .grid && to.minY > from.minY
        case .left:
            if overlapsVertically(from, to) { return to.minX < from.minX + from.width * slackFraction }
            return mode == .grid && to.minY < from.minY
        }
    }

    /// Whether two frames share any height: how "the same line" is decided
    /// without either knowing what a line is. Touching is not sharing.
    public static func overlapsVertically(_ a: CGRect, _ b: CGRect) -> Bool {
        a.minY < b.maxY && b.minY < a.maxY
    }

    /// Whether `to` lies `direction` from `from` at all (Android's
    /// `FocusFinder.isCandidate`): beyond it that way, not merely overlapping.
    static func lies(_ direction: PadDirection, from: CGRect, to: CGRect) -> Bool {
        switch direction {
        case .right: (from.minX < to.minX || from.maxX <= to.minX) && from.maxX < to.maxX
        case .left: (from.maxX > to.maxX || from.minX >= to.maxX) && from.minX > to.minX
        case .down: (from.minY < to.minY || from.maxY <= to.minY) && from.maxY < to.maxY
        case .up: (from.maxY > to.maxY || from.minY >= to.maxY) && from.minY > to.minY
        }
    }
}

// MARK: Where focus returns

/// Android's `ui/FocusPlace`, on ids: the item each page last had focus on,
/// which Back and a tab return to, and the member each group was last left
/// on, which a press back into a group nothing of which is laid out returns to.
public struct PadMemory: Equatable, Sendable {
    private var places: [String: String] = [:]
    private var members: [String: String] = [:]

    public init() {}

    /// `id` has focus on `page`.
    public mutating func focused(_ id: String, page: String, in map: PadMap) {
        places[page] = id
        var current = id
        var seen: Set<String> = []
        while let group = map.parent(of: current), !seen.contains(group.id) {
            seen.insert(group.id)
            members[group.id] = current
            current = group.id
        }
    }

    /// Where focus goes as `page` shows again (Back to it, its tab chosen, down
    /// from the tabs): its place while the page still has it, else its first item.
    public func returning(to page: String, in map: PadMap) -> String? {
        if let place = places[page], map.contains(place) { return place }
        return PadFocus.first(in: map)
    }

    /// The page is gone for good (popped, or another profile's pages).
    public mutating func forget(page: String) {
        places[page] = nil
    }

    /// The member `group` was last left on.
    public func member(in group: String) -> String? {
        members[group]
    }
}

// MARK: The ring

/// Which way the app is being driven. Android's `input/InputModeTracker`.
public enum PadInputMode: Equatable, Sendable {
    case directional, pointer
}

/// The ring follows the last input: a controller or a keyboard shows it, a
/// touch, a click or a trackpad hides it. It starts hidden, unlike the Pocket,
/// whose pad is the default: here a touch is, and the ring waits for the first
/// press. A change is reported once, so a stream of stick events redraws nothing.
public struct PadInput: Equatable, Sendable {
    public private(set) var mode: PadInputMode

    public init(mode: PadInputMode = .pointer) {
        self.mode = mode
    }

    public var showsRing: Bool { mode == .directional }

    /// A controller's or a keyboard's press. Whether the mode changed.
    @discardableResult
    public mutating func directional() -> Bool { set(.directional) }

    /// A touch, a click or a trackpad. Whether the mode changed.
    @discardableResult
    public mutating func pointer() -> Bool { set(.pointer) }

    private mutating func set(_ next: PadInputMode) -> Bool {
        guard mode != next else { return false }
        mode = next
        return true
    }
}

// MARK: Scrolling to it

/// How far a scroll view moves so the focused item can be seen: as little as
/// it takes, with a margin round it and room above it for a row's heading
/// (the Pocket's `revealAbove`), or with its row resting at the top
/// (`pin`, the Pocket's `PinnedRows`).
public enum PadReveal {
    /// The offset along one axis that shows `span` (an item's extent, in
    /// content coordinates) in a viewport `length` long now scrolled to
    /// `offset`: nil when it shows already. Longer than the viewport, its start
    /// shows. Never before the content's start, nor past its end when its
    /// `content` length is known.
    public static func offset(showing span: ClosedRange<Double>, at offset: Double, length: Double,
                              content: Double? = nil) -> Double? {
        guard length > 0 else { return nil }
        var target = offset
        if span.upperBound - span.lowerBound > length || span.lowerBound < offset {
            target = span.lowerBound
        } else if span.upperBound > offset + length {
            target = span.upperBound - length
        }
        target = max(target, 0)
        if let content { target = min(target, max(content - length, 0)) }
        return abs(target - offset) < 0.5 ? nil : target
    }

    /// The scroll origin that shows `frame` in `visible` (both in content
    /// coordinates): nil when no scroll is needed. `margin` all round, `above`
    /// more over it; `pin` rests it at the top, with `above` over it.
    public static func origin(showing frame: CGRect, in visible: CGRect, margin: CGFloat = 0, above: CGFloat = 0,
                              pin: Bool = false, content: CGSize? = nil) -> CGPoint? {
        let x = offset(showing: Double(frame.minX - margin)...Double(frame.maxX + margin), at: Double(visible.minX),
                       length: Double(visible.width), content: content.map { Double($0.width) })
        let top = Double(frame.minY - margin - above)
        var y: Double?
        if pin {
            let length = Double(visible.height)
            var target = max(top, 0)
            if let content { target = min(target, max(Double(content.height) - length, 0)) }
            y = abs(target - Double(visible.minY)) < 0.5 ? nil : target
        } else {
            y = offset(showing: top...Double(frame.maxY + margin), at: Double(visible.minY),
                       length: Double(visible.height), content: content.map { Double($0.height) })
        }
        guard x != nil || y != nil else { return nil }
        return CGPoint(x: x ?? Double(visible.minX), y: y ?? Double(visible.minY))
    }
}
