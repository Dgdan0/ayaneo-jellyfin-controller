#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Whether something is presented over the shell's pages in the key window:
/// a screen's own sheet, alert or confirmation. Ⓑ, L1, R1 and Escape leave
/// the page under it alone, so it is answered before anything moves.
@MainActor
enum PresentedOver {
    static var any: Bool {
        #if os(macOS)
        return NSApp.keyWindow?.attachedSheet != nil || NSApp.modalWindow != nil
        #else
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.first { $0.activationState == .foregroundActive }?.keyWindow
            ?? scenes.compactMap(\.keyWindow).first
        return window?.rootViewController?.presentedViewController != nil
        #endif
    }
}
