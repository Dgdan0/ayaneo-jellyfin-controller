import Foundation

// How a comic series is read, kept per series (#16, C1): Android's
// `reader/ComicView.kt`, with its test cases in `ComicViewTests`. The stored
// strings are Android's, so a series reads the same way on either device once
// settings travel.

/// How a comic page is fitted to the screen.
public enum ComicFit: String, CaseIterable, Sendable {
    case whole
    case width
    /// Fit the width and read down the page in steps worked out from its shape (#16, C2).
    case thirds

    /// What it is called where it is chosen.
    public var label: String {
        switch self {
        case .whole: "Whole page"
        case .width: "Fit page width"
        case .thirds: "Read in thirds"
        }
    }

    public var stored: String { rawValue }

    public static func fromStored(_ raw: String?, fallback: ComicFit) -> ComicFit {
        raw.flatMap(ComicFit.init(rawValue:)) ?? fallback
    }
}

/// How a series is read (#16, C1): its fit, and a direction chosen over the
/// library's (nil keeps the library's, "rtl" for Manga). Kept per series, so
/// the next issue opens the same way. `trim`: the paper border round each page
/// is left out of the fit and the steps (#18, C5); on unless turned off for
/// the series.
public struct ComicView: Equatable, Sendable {
    public var fit: ComicFit
    public var direction: String?
    public var trim: Bool

    public init(_ fit: ComicFit, direction: String? = nil, trim: Bool = true) {
        self.fit = fit
        self.direction = direction
        self.trim = trim
    }

    /// Thirds unless the person chose otherwise: a portrait page at fit width
    /// is about 92% of print.
    public static let defaultFit = ComicFit.thirds

    /// "width|rtl", and "|untrimmed" after it only when trimming is off, as #16 wrote it.
    public func encode() -> String {
        fit.stored + "|" + (direction ?? "") + (trim ? "" : "|untrimmed")
    }

    public static func decode(_ raw: String?, fallback: ComicFit) -> ComicView {
        guard let raw, !raw.trimmingCharacters(in: .whitespaces).isEmpty else { return ComicView(fallback) }
        let parts = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        let fit = ComicFit.fromStored(parts[0], fallback: fallback)
        let direction = parts.count > 1 && (parts[1] == "ltr" || parts[1] == "rtl") ? parts[1] : nil
        return ComicView(fit, direction: direction, trim: !(parts.count > 2 && parts[2] == "untrimmed"))
    }
}

/// The page and the third last shown in an issue, so opening it again puts you
/// on the same third, not the top of the page.
public struct ComicPlace: Equatable, Sendable {
    public var sourceItemId: String
    public var page: Int
    public var step: Int

    public init(_ sourceItemId: String, page: Int, step: Int) {
        self.sourceItemId = sourceItemId
        self.page = page
        self.step = step
    }

    public func encode() -> String { "\(sourceItemId)|\(page)|\(step)" }

    /// The step to open `page` of `publication` at, out of `steps`: the saved one only on the same page.
    public func stepFor(_ publication: String, page: Int, steps: Int) -> Int {
        guard publication == sourceItemId, page == self.page else { return 0 }
        return min(max(step, 0), max(0, steps - 1))
    }

    public static func decode(_ raw: String?) -> ComicPlace? {
        guard let raw else { return nil }
        let parts = raw.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, let page = Int(parts[1]), let step = Int(parts[2]),
              !parts[0].trimmingCharacters(in: .whitespaces).isEmpty, page >= 0, step >= 0 else { return nil }
        return ComicPlace(parts[0], page: page, step: step)
    }
}

/// A zoom kept from page to page (#16, C1, the owner's decision): zoom in a
/// little and turn the page, and the next page opens at the same zoom and the
/// same horizontal place, at its top. `factor` is how far past the fit's own
/// scale; `anchorX` is where the middle of the view sits across the page, 0 to
/// 1. At 1 the fit reads as it always does (thirds step, whole pages show).
public struct ComicZoom: Equatable, Sendable {
    public var factor: Double
    public var anchorX: Double

    public init(factor: Double = 1, anchorX: Double = 0.5) {
        self.factor = factor
        self.anchorX = anchorX
    }

    /// Within 2% of the fit is the fit: a zoom pinched back to it ends.
    public static let close = 0.02

    public var active: Bool { abs(factor - 1) > Self.close }

    /// The scale for a page whose fit has the scale `base`, within the view's limits.
    public func scaleFor(base: Double, min lower: Double, max upper: Double) -> Double {
        min(upper, max(lower, base * factor))
    }

    /// Where the middle of the view goes on a new page `pageWidth` by
    /// `pageHeight`, when `visibleWidth` by `visibleHeight` of it shows (in the
    /// page's pixels): the same place across, at the top going forward and at
    /// the bottom going back. A page narrower than the view is centred.
    public func center(pageWidth: Double, pageHeight: Double, visibleWidth: Double, visibleHeight: Double,
                       atEnd: Bool) -> (x: Double, y: Double) {
        let x = visibleWidth >= pageWidth ? pageWidth / 2
            : min(pageWidth - visibleWidth / 2, max(visibleWidth / 2, anchorX * pageWidth))
        let y: Double = if visibleHeight >= pageHeight {
            pageHeight / 2
        } else if atEnd {
            pageHeight - visibleHeight / 2
        } else {
            visibleHeight / 2
        }
        return (x, y)
    }

    public static func of(scale: Double, base: Double, centerX: Double, pageWidth: Double) -> ComicZoom {
        guard base > 0, pageWidth > 0 else { return ComicZoom() }
        let factor = scale / base
        if abs(factor - 1) <= close { return ComicZoom() }
        return ComicZoom(factor: factor, anchorX: min(1, max(0, centerX / pageWidth)))
    }
}

/// An issue's cover, for the reader's glass to take its colours (#16, X7):
/// Kavita's cover of the chapter, as the hub serves it on a run's page
/// (`/v1/img/reading/kavita-chapter/{id}`). Nil for a publication that is not
/// a Kavita chapter, which keeps the colours the page already has.
///
/// The hub names a Kavita issue by its bare chapter id ("8358" on the live
/// hub, 2026-10-06); the prefixed form is kept for ids written that way. Only
/// the page reader asks, and it reads Kavita only, so bare digits are a
/// chapter (Android's rule since #28, e70832f).
public enum IssueCover {
    private static let prefix = "kavita-chapter:"

    public static func path(_ sourceItemId: String) -> String? {
        let id = sourceItemId.hasPrefix(prefix) ? String(sourceItemId.dropFirst(prefix.count)) : sourceItemId
        guard !id.isEmpty, id.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }) else { return nil }
        return "/v1/img/reading/kavita-chapter/" + id
    }
}
