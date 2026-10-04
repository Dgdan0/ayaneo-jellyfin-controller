import HubKit
import SwiftUI

// Glass's panels and buttons (GLASS_PLAN.md, "What Glass is"). The numbers are
// the prototype's: `.glass`, `.bw`, `.bg`, `.rb` and `.ibtn` in its stylesheet.

extension Color {
    /// A HubKit colour, 0xAARRGGBB.
    init(argb: UInt32) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255, opacity: Double(argb >> 24) / 255)
    }

    /// Words and icons on anything white (`GlassColors.ink`).
    static let glassInk = Color(argb: GlassColors.ink)
    /// A notification count's red (`GlassColors.badge`).
    static let glassAlert = Color(argb: GlassColors.badge)
}

extension EnvironmentValues {
    /// The colours of the artwork the page shows. Under Reduce transparency a
    /// panel is a tint of them instead of a blur.
    @Entry var glassPalette: ArtworkPalette = .neutral
    /// Teal on the Media side, gold on Books.
    @Entry var glassAccent: AccentPreset = .teal
    /// Glass over a playing video (the player sets it): dark whatever the frame.
    @Entry var glassOverVideo = false
}

extension AccentPreset {
    var tint: Color { Color(argb: color) }
    var inkColor: Color { Color(argb: ink) }
}

extension HubType {
    /// Type in the bars and tabs: a fixed size, as the system's own bars keep
    /// theirs, so Dynamic Type cannot push the capsule out of its row.
    static func chrome(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        let name = switch weight {
        case .medium: "Figtree-Medium"
        case .semibold: "Figtree-SemiBold"
        case .bold, .heavy, .black: "Figtree-Bold"
        default: "Figtree-Regular"
        }
        return .custom(name, fixedSize: size)
    }
}

/// A Glass panel: real material over the page, a hairline edge and a light
/// along the top. Under Reduce transparency it is the Pocket's panel instead,
/// the page's artwork colour mixed into #12141C at 80% (`GlassColors.panel`),
/// which reads as frosted over the blurred page without blurring anything.
struct GlassPanel<S: InsettableShape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.glassPalette) private var palette
    @Environment(\.glassOverVideo) private var overVideo

    func body(content: Content) -> some View {
        content
            .background {
                if reduceTransparency {
                    shape.fill(Color(argb: GlassColors.panel(palette)))
                } else if overVideo {
                    // Over video the glass stays dark whatever the frame: over a
                    // bright one the material alone turned light grey under
                    // white words. So the title's panel colour lies on it at 70%
                    // (the user's rule: nothing white over video but Play).
                    shape.fill(.ultraThinMaterial)
                        .overlay { shape.fill(Color(argb: GlassColors.panel(palette)).opacity(0.7)) }
                } else {
                    // Dark material already lifts a dark page about as much as
                    // the prototype's white 11% does, and dims a bright one;
                    // a little white meets the two halfway.
                    shape.fill(.ultraThinMaterial)
                        .overlay { shape.fill(Color.white.opacity(0.05)) }
                }
            }
            .overlay {
                ZStack {
                    shape.strokeBorder(Color(argb: GlassColors.edge), lineWidth: 1)
                    // The prototype's `inset 0 1px 0`: the inside of the edge
                    // less itself one point lower leaves a line along the top.
                    let inner = shape.inset(by: 1)
                    inner.subtracting(inner.offset(y: 1)).fill(Color(argb: GlassColors.highlight))
                }
                .allowsHitTesting(false)
            }
    }
}

extension View {
    func glassPanel<S: InsettableShape>(_ shape: S) -> some View {
        modifier(GlassPanel(shape: shape))
    }
}

/// A side sheet's or a dialog's fill (the prototype's `.sheet`): glass with a
/// dark blue-grey at 72%. Under Reduce transparency it is the page's panel
/// colour at 95% (`GlassColors.sheet`), nearly solid, because a sheet sits
/// over the screen's own words: the account sheet, the player's panels.
struct GlassSheetFill: View {
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.glassPalette) private var palette

    var body: some View {
        if reduceTransparency {
            Color(argb: GlassColors.sheet(palette))
        } else {
            Rectangle().fill(.ultraThinMaterial)
                .overlay(Color(argb: 0xB81A_1C26))
        }
    }
}

/// The page's main action: a white pill (Play, Resume, Request). The Books
/// side passes its accent instead, for Resume reading.
struct PrimaryPillStyle: ButtonStyle {
    var accent: AccentPreset?
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let compact = sizeClass == .compact
        configuration.label
            .font(HubType.body(compact ? 15 : 16, weight: .bold))
            .padding(.horizontal, compact ? 18 : 22)
            .padding(.vertical, compact ? 12 : 13)
            .foregroundStyle(accent?.inkColor ?? .glassInk)
            .background(accent?.tint ?? .white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .opacity(configuration.isPressed ? 0.82 : isEnabled ? 1 : 0.55)
            #if os(iOS)
            .hoverEffect(.lift)
            #endif
    }
}

/// A secondary action beside the main one (Details, Trailer): the same shape
/// in glass.
struct GlassPillStyle: ButtonStyle {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let compact = sizeClass == .compact
        configuration.label
            .font(HubType.body(compact ? 15 : 16, weight: .bold))
            .padding(.horizontal, 18)
            .padding(.vertical, compact ? 12 : 13)
            .foregroundStyle(.white)
            .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .brightness(configuration.isPressed ? 0.1 : 0)
            .opacity(isEnabled ? 1 : 0.55)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

/// A round glass button: the toggles beside the main pill (watched,
/// favourite) and the icons in the bar. White with dark ink when on. A count
/// above zero sits on its corner in red, as the bell's does.
struct GlassRoundButton: View {
    let systemImage: String
    let label: String
    var on = false
    var size: CGFloat = 46
    var count = 0
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if on {
                    Circle().fill(.white)
                } else {
                    Circle().fill(.clear).glassPanel(Circle())
                }
                Image(systemName: systemImage)
                    .font(.system(size: size * 0.4, weight: .semibold))
                    .foregroundStyle(on ? Color.glassInk : .white)
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
            .overlay(alignment: .topTrailing) {
                if count > 0 {
                    Text(count > 99 ? "99+" : "\(count)")
                        .font(HubType.chrome(10, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .frame(minWidth: 19, minHeight: 19)
                        .background(Color.glassAlert, in: Capsule())
                        .offset(x: 4, y: -4)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(count > 0 ? "\(count)" : "")
        .accessibilityAddTraits(on ? .isSelected : [])
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}
