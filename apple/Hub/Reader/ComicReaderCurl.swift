import HubKit
import SwiftUI

/// The page curl's side of the reader model (#32): whether a curl may start
/// from each edge now, the turn a finished curl made, and the turn the keys
/// and a controller ask the curl to play. The curl itself is iOS's
/// (`ComicCurlView`); the Mac keeps its own turn.
extension ComicReaderModel {
    /// A turn for the curl to play, from the keys or a controller.
    struct CurlTurn: Equatable {
        let id: Int
        let publication: String
        let unit: Int
        let forward: Bool
    }

    /// Whether a curl may start from `edge` now: reading, with nothing over
    /// the page, at its normal size, in thirds from the step it turns at, and
    /// with a page that side to land on. Past the issue's ends the page keeps
    /// its edges, so a swipe there still reaches the end card.
    func curlAllowed(_ edge: ComicCurl.Edge) -> Bool {
        guard curlAvailable, phase == .reading, shown != nil, endCard == nil, !gridOpen, sheet == nil,
              pendingUnit == nil, gestureIsIdle,
              curlReady(unit: state.pageIndex), curlReady(unit: state.pageIndex + (edge == .forward ? 1 : -1))
        else { return false }
        let zoomed = zoom.active || (shownFrame?.isZoomed(camera, fit: view.fit) ?? false)
        return ComicCurl.allowed(edge, zoomed: zoomed, thirds: view.fit == .thirds, step: state.viewportIndex,
                                 steps: state.viewportSteps)
    }

    /// Whether `unit` can be drawn on a leaf now: in the issue, its pictures
    /// decoded (`PageSlots` decodes the neighbours ahead), so a curl never
    /// turns to a blank page.
    func curlReady(unit: Int) -> Bool {
        guard let publication = shown?.publication, units.units.indices.contains(unit),
              let layout = layout(pages: units.units[unit].onScreen, publication: publication) else { return false }
        return layout.placed.allSatisfy { images[PageKey(publication, $0.page)] != nil }
    }

    /// A finger curled the page over to `unit`, the one next to the one
    /// shown: the reading turns there, as a swipe would, without another turn
    /// drawn over the curl's.
    func curled(to unit: Int) {
        guard phase == .reading, unit != state.pageIndex, abs(unit - state.pageIndex) == 1 else { return }
        turnPage(unit > state.pageIndex ? 1 : -1)
    }

    /// Runs a key's or a controller's `action`, and when it turns to the unit
    /// next door, asks the curl to play the turn: the page is hidden under
    /// the curl from the same moment, so the next page never shows first.
    func padWithCurl(_ action: PadAction) {
        let publication = manifest?.sourceItemId
        let before = pendingUnit ?? state.pageIndex
        let edgeForward = curlAllowed(.forward)
        let edgeBackward = curlAllowed(.backward)
        pad(action)
        guard curlAvailable, let publication, publication == manifest?.sourceItemId else { return }
        let after = pendingUnit ?? state.pageIndex
        guard abs(after - before) == 1 else { return }
        let forward = after > before
        // A turn the curl could have made from the edge: not a zoomed page's.
        guard forward ? edgeForward : edgeBackward else { return }
        curlTurns += 1
        curlTurn = CurlTurn(id: curlTurns, publication: publication, unit: after, forward: forward)
        curling = true
    }
}
