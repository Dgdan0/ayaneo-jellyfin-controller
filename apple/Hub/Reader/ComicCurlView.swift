#if os(iOS)
import HubKit
import SwiftUI
import UIKit

/// What the curl shows now, read off the reader in the screen's body, so a
/// change to any of it reaches the curl (#32).
struct ComicCurlSnapshot: Equatable {
    var publication: String
    /// The unit on screen, and how many the issue has.
    var unit: Int
    var unitCount: Int
    /// Two pages side by side: they turn as a book, the spine between them.
    var spreads: Bool
    var rtl: Bool
    var size: CGSize
    var camera: ComicCamera
    var fit: ComicFit
    /// The pictures decoded: the leaves on show are drawn again as they come.
    var decoded: Int
    var turn: ComicReaderModel.CurlTurn?
}

/// The page curl (#32): `UIPageViewController` turning the pages like paper,
/// under the reader's own page. The canvas covers it, and lets the touches
/// at the outer edges through while a curl may start there
/// (`ComicCurl.edge`, `curlAllowed`); a curl hides the canvas until it ends,
/// and a finished one turns the reading as a swipe would. The keys and a
/// controller play the same curl. Taps at the edges still read on or back,
/// as on the canvas (`ComicTouch`), and `UIPageViewController`'s own tap is off.
///
/// A curl knows only left to right: it ignores the view's direction, and its
/// right-hand spine (`.max`) turned a drag from the left edge to the page
/// before the first. Read right to left, the book is drawn mirrored, each
/// leaf mirrored back inside it, so the page lifts at the left edge and
/// turns over to the right, as a manga's does.
struct ComicCurlView: UIViewControllerRepresentable {
    let reader: ComicReaderModel
    let snapshot: ComicCurlSnapshot

    func makeUIViewController(context: Context) -> ComicCurlController {
        ComicCurlController(reader: reader)
    }

    func updateUIViewController(_ controller: ComicCurlController, context: Context) {
        controller.update(snapshot)
    }

    static func dismantleUIViewController(_ controller: ComicCurlController, coordinator: ()) {
        controller.detach()
    }
}

final class ComicCurlController: UIViewController, UIPageViewControllerDataSource, UIPageViewControllerDelegate,
    UIGestureRecognizerDelegate {
    private weak var reader: ComicReaderModel?
    private var book: UIPageViewController?
    private var snapshot: ComicCurlSnapshot?
    /// The unit and issue the book shows.
    private var shownUnit: Int?
    private var shownPublication = ""
    /// The last turn the keys asked for that was played.
    private var playedTurn = 0
    /// A curl is moving: the book is left alone until it ends.
    private var turning = false
    /// The book's own say on its drags, asked after the edge's.
    private var bookGestures: [ObjectIdentifier: UIGestureRecognizerDelegate] = [:]
    /// Holds the book, mirrored while the reading goes right to left.
    private let mirror = UIView()

    init(reader: ComicReaderModel) {
        self.reader = reader
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        mirror.backgroundColor = .black
        view.addSubview(mirror)
        // The edges' own taps, as the canvas reads them: a double tap zooms,
        // a single one (once a double tap is ruled out) reads on or back.
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        single.require(toFail: double)
        view.addGestureRecognizer(double)
        view.addGestureRecognizer(single)
        // Not in the middle of SwiftUI's update that made this controller.
        Task { @MainActor [weak self] in self?.reader?.curlAvailable = true }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // Bounds and centre, not frame: the mirror has a transform.
        mirror.bounds = CGRect(origin: .zero, size: view.bounds.size)
        mirror.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
    }

    func detach() {
        let reader = reader
        Task { @MainActor in
            reader?.curlAvailable = false
            reader?.curling = false
        }
    }

    @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
        reader?.tapped(x: recognizer.location(in: view).x)
    }

    @objc private func doubleTapped(_ recognizer: UITapGestureRecognizer) {
        reader?.doubleTapped(at: recognizer.location(in: view))
    }

    // MARK: Following the reader

    func update(_ next: ComicCurlSnapshot) {
        let before = snapshot
        snapshot = next
        guard next.size.width > 0, next.size.height > 0 else { return }
        if book == nil || before?.spreads != next.spreads || before?.rtl != next.rtl || before?.size != next.size {
            build(next)
            return
        }
        guard !turning else { return }
        if next.publication != shownPublication {
            show(unit: next.unit, animated: false, forward: true)
        } else if next.unit != shownUnit {
            if let turn = next.turn, turn.id != playedTurn, turn.unit == next.unit, turn.publication == next.publication {
                playedTurn = turn.id
                show(unit: next.unit, animated: true, forward: turn.forward)
            } else {
                show(unit: next.unit, animated: false, forward: true)
            }
        } else {
            // The same unit: drawn where the reader now looks at it, with the pictures that came.
            for case let leaf as ComicCurlLeaf in book?.viewControllers ?? [] { draw(leaf) }
        }
    }

    /// The book, made again for a new shape: spreads or not, the reading's
    /// way, the page's size.
    private func build(_ next: ComicCurlSnapshot) {
        if let book {
            book.willMove(toParent: nil)
            book.view.removeFromSuperview()
            book.removeFromParent()
        }
        let spine: UIPageViewController.SpineLocation = next.spreads ? .mid : .min
        let pages = UIPageViewController(transitionStyle: .pageCurl, navigationOrientation: .horizontal,
                                         options: [.spineLocation: NSNumber(value: spine.rawValue)])
        pages.isDoubleSided = true
        pages.dataSource = self
        pages.delegate = self
        pages.view.backgroundColor = .black
        addChild(pages)
        mirror.transform = next.rtl ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        pages.view.frame = mirror.bounds
        pages.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        mirror.addSubview(pages.view)
        pages.didMove(toParent: self)
        bookGestures = [:]
        for recognizer in pages.gestureRecognizers {
            if recognizer is UITapGestureRecognizer {
                recognizer.isEnabled = false
            } else {
                if let own = recognizer.delegate { bookGestures[ObjectIdentifier(recognizer)] = own }
                recognizer.delegate = self
            }
        }
        book = pages
        shownUnit = nil
        turning = false
        show(unit: next.unit, animated: false, forward: true)
    }

    /// `unit`'s leaves on show: turned to as a curl when `animated`.
    private func show(unit: Int, animated wanted: Bool, forward: Bool) {
        guard let book, let snapshot else { return }
        var shown = ComicCurl.shown(unit: unit, spreads: snapshot.spreads)
        // A curl played at an outer spine turns a page over: UIKit wants its
        // back as well as the page it lands on (the turning page is the one
        // shown going on, the one landed on going back).
        if wanted, !snapshot.spreads {
            shown.append(ComicCurl.Leaf(unit: forward ? unit - 1 : unit, side: .back))
        }
        let leaves = shown.map { ComicCurlLeaf(leaf: $0) }
        // Every leaf drawn, or no curl: a curl never turns a blank page.
        var drawn = true
        for leaf in leaves where !draw(leaf) { drawn = false }
        let animated = wanted && drawn
        shownUnit = unit
        shownPublication = snapshot.publication
        turning = animated
        if wanted, !animated { reader?.curling = false }
        // The keys hid the page as they turned it (`padWithCurl`); anything else is not played.
        book.setViewControllers(leaves, direction: forward ? .forward : .reverse, animated: animated) { [weak self] _ in
            guard let self, animated else { return }
            self.turning = false
            self.reader?.curling = false
        }
    }

    // MARK: The leaves

    /// A leaf of `unit`, or nil while its pictures are still on their way: a
    /// curl never shows a blank page (`PageSlots` decodes the neighbours ahead).
    private func makeLeaf(_ leaf: ComicCurl.Leaf) -> ComicCurlLeaf? {
        let made = ComicCurlLeaf(leaf: leaf)
        return draw(made) ? made : nil
    }

    /// Draws the leaf's picture: the unit where the reader looks for the unit
    /// on screen, else where a turn to it arrives.
    @discardableResult
    private func draw(_ leaf: ComicCurlLeaf) -> Bool {
        guard let reader, let snapshot, let picture = picture(unit: leaf.leaf.unit, reader: reader, snapshot: snapshot) else {
            return false
        }
        leaf.show(picture, size: snapshot.size, span: ComicCurl.span(leaf.leaf.side, rtl: snapshot.rtl),
                  back: leaf.leaf.side == .back, mirrored: snapshot.rtl)
        return true
    }

    private func picture(unit: Int, reader: ComicReaderModel, snapshot: ComicCurlSnapshot) -> ComicCurlPicture? {
        guard reader.units.units.indices.contains(unit),
              let layout = reader.layout(pages: reader.units.units[unit].onScreen, publication: snapshot.publication),
              let frame = reader.frame(for: layout) else { return nil }
        var images: [Int: CGImage] = [:]
        for placed in layout.placed {
            guard let image = reader.images[PageKey(snapshot.publication, placed.page)] else { return nil }
            images[placed.page] = image.image
        }
        let camera = unit == snapshot.unit ? snapshot.camera
            : frame.placement(fit: snapshot.fit, step: 0, atEnd: unit < snapshot.unit, zoom: ComicZoom())
        return ComicCurlPicture(layout: layout, images: images, camera: camera)
    }

    // MARK: UIPageViewControllerDataSource

    func pageViewController(_ pageViewController: UIPageViewController,
                            viewControllerAfter viewController: UIViewController) -> UIViewController? {
        neighbour(of: viewController, reading: true)
    }

    func pageViewController(_ pageViewController: UIPageViewController,
                            viewControllerBefore viewController: UIViewController) -> UIViewController? {
        neighbour(of: viewController, reading: false)
    }

    private func neighbour(of viewController: UIViewController, reading forward: Bool) -> UIViewController? {
        guard let leaf = (viewController as? ComicCurlLeaf)?.leaf, let snapshot,
              let next = forward ? ComicCurl.after(leaf, units: snapshot.unitCount)
                                 : ComicCurl.before(leaf, units: snapshot.unitCount) else { return nil }
        return makeLeaf(next)
    }

    // MARK: UIPageViewControllerDelegate

    func pageViewController(_ pageViewController: UIPageViewController,
                            willTransitionTo pendingViewControllers: [UIViewController]) {
        turning = true
        reader?.curling = true
    }

    func pageViewController(_ pageViewController: UIPageViewController, didFinishAnimating finished: Bool,
                            previousViewControllers: [UIViewController], transitionCompleted completed: Bool) {
        turning = false
        if completed, let unit = (pageViewController.viewControllers?.first as? ComicCurlLeaf)?.leaf.unit {
            shownUnit = unit
            // The reading turns as a swipe would; the canvas shows it as the curl lets go.
            reader?.curled(to: unit)
        }
        reader?.curling = false
    }

    // MARK: UIGestureRecognizerDelegate

    /// The book's drag starts only at an outer edge, moving in from it, and
    /// only while a curl may, with a page that side to land on: a drag that
    /// finds no page there stops the app.
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let reader, let snapshot, !turning else { return false }
        let point = gestureRecognizer.location(in: view)
        guard let edge = ComicCurl.edge(x: point.x, width: view.bounds.width, rtl: snapshot.rtl) else { return false }
        if let pan = gestureRecognizer as? UIPanGestureRecognizer {
            // In from the edge: leftwards from the right edge, rightwards from the left.
            let across = pan.velocity(in: view).x
            guard across != 0, (across < 0) == (point.x > view.bounds.midX) else { return false }
        }
        let own = bookGestures[ObjectIdentifier(gestureRecognizer)]
        return reader.curlAllowed(edge) && (own?.gestureRecognizerShouldBegin?(gestureRecognizer) ?? true)
    }
}

/// A unit's pictures and where the reader looks at them.
struct ComicCurlPicture {
    let layout: ComicUnitLayout
    let images: [Int: CGImage]
    let camera: ComicCamera
}

/// One leaf of the book: a unit's picture, or its half, or its back.
final class ComicCurlLeaf: UIViewController {
    let leaf: ComicCurl.Leaf
    /// The leaf's own mirror, undoing the book's while it reads right to left.
    private let flip = UIView()
    private let canvas = UIView()
    private var pageViews: [UIImageView] = []
    /// Behind each page on a back: the paper, which the print shows through.
    private var paperViews: [UIView] = []

    init(leaf: ComicCurl.Leaf) {
        self.leaf = leaf
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        view.clipsToBounds = true
        flip.clipsToBounds = true
        view.addSubview(flip)
        flip.addSubview(canvas)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        flip.bounds = CGRect(origin: .zero, size: view.bounds.size)
        flip.center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
    }

    /// The picture laid out as the reader's page lays it out (`ComicPageCanvas`),
    /// over a page `size` across, of which this leaf shows `span`; a back is
    /// the page itself, mirrored and faded into its paper, as Apple Books
    /// shows the back of a page.
    func show(_ picture: ComicCurlPicture, size: CGSize, span: ClosedRange<Double>, back: Bool, mirrored: Bool) {
        loadViewIfNeeded()
        flip.transform = mirrored ? CGAffineTransform(scaleX: -1, y: 1) : .identity
        canvas.transform = .identity
        canvas.frame = CGRect(x: -span.lowerBound * size.width, y: 0, width: size.width, height: size.height)
        while pageViews.count < picture.layout.placed.count {
            let paper = UIView()
            paper.backgroundColor = UIColor(white: 0.96, alpha: 1)
            canvas.addSubview(paper)
            paperViews.append(paper)
            let page = UIImageView()
            page.contentMode = .scaleToFill
            canvas.addSubview(page)
            pageViews.append(page)
        }
        let camera = picture.camera
        for (index, page) in pageViews.enumerated() {
            let paper = paperViews[index]
            guard index < picture.layout.placed.count else {
                page.isHidden = true
                paper.isHidden = true
                continue
            }
            let placed = picture.layout.placed[index]
            page.isHidden = false
            page.image = picture.images[placed.page].map { UIImage(cgImage: $0) }
            page.frame = CGRect(x: (placed.x - camera.x) * camera.scale + size.width / 2,
                                y: (placed.y - camera.y) * camera.scale + size.height / 2,
                                width: placed.width * camera.scale, height: placed.height * camera.scale)
            page.alpha = back ? 0.2 : 1
            paper.frame = page.frame
            paper.isHidden = !back
        }
        canvas.transform = back ? CGAffineTransform(scaleX: -1, y: 1) : .identity
    }
}
#endif
