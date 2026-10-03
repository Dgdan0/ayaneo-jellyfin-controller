import Foundation

/// Human-readable numbers, and the only place the app formats them. A port of
/// Android's `state/Fmt`, held to the same test cases.
///
/// Decimals always use a full stop, whatever the device's region: the Android
/// copy pins `Locale.US` for the same reason ("1,5 GB" on a German phone).
public enum Fmt {
    private static let units = ["B", "KB", "MB", "GB", "TB"]

    private static func oneDecimal(_ value: Double) -> String {
        String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
    }

    public static func bytes(_ value: Int64) -> String {
        if value <= 0 { return "0 B" }
        var size = Double(value)
        var unit = 0
        while size >= 1024 && unit < units.count - 1 {
            size /= 1024
            unit += 1
        }
        return unit == 0 || size >= 100 ? "\(Int(size)) \(units[unit])" : "\(oneDecimal(size)) \(units[unit])"
    }

    /// Zero is a dash: "0 B/s" reads as broken rather than idle.
    public static func speed(_ bytesPerSecond: Int64) -> String {
        bytesPerSecond <= 0 ? "—" : bytes(bytesPerSecond) + "/s"
    }

    /// Time left on a transfer; -1 (the hub could not estimate) is a dash.
    public static func eta(_ seconds: Int64) -> String {
        if seconds < 0 { return "—" }
        if seconds < 60 { return "\(seconds)s" }
        let minutes = seconds / 60
        if minutes < 60 { return "\(minutes)m" }
        let hours = minutes / 60
        if hours < 24 { return "\(hours)h \(minutes % 60)m" }
        return "\(hours / 24)d \(hours % 24)h"
    }

    public static func percent(_ fraction: Double) -> String {
        fraction < 0 ? "—" : "\(Int(fraction * 100))%"
    }

    /// A position within something playing: "24:05", or "1:15:30" once it
    /// passes an hour. Minutes never run past 59.
    public static func clock(_ millis: Int64) -> String {
        let total = max(0, millis) / 1_000
        let hours = total / 3_600
        let minutes = total % 3_600 / 60
        let seconds = total % 60
        let ss = seconds < 10 ? "0\(seconds)" : "\(seconds)"
        if hours > 0 {
            let mm = minutes < 10 ? "0\(minutes)" : "\(minutes)"
            return "\(hours):\(mm):\(ss)"
        }
        return "\(minutes):\(ss)"
    }

    /// How long something is: "24 min", or "2h 5m" from an hour up. Empty when
    /// unknown, so a "·"-joined line can drop it.
    public static func runtime(_ seconds: Int64) -> String {
        if seconds <= 0 { return "" }
        let minutes = seconds / 60
        return minutes < 60 ? "\(minutes) min" : "\(minutes / 60)h \(minutes % 60)m"
    }

    /// How long a machine has been up: "1 day 4 hours", "3 hours 12 min", "8 min".
    public static func uptime(_ seconds: Int64) -> String {
        if seconds <= 0 { return "" }
        let days = seconds / 86_400
        let hours = seconds % 86_400 / 3_600
        let minutes = seconds % 3_600 / 60
        func unit(_ n: Int64, _ one: String) -> String { n == 1 ? "1 \(one)" : "\(n) \(one)s" }
        if days > 0 {
            return ([unit(days, "day")] + (hours > 0 ? [unit(hours, "hour")] : [])).joined(separator: " ")
        }
        if hours > 0 {
            return ([unit(hours, "hour")] + (minutes > 0 ? ["\(minutes) min"] : [])).joined(separator: " ")
        }
        return "\(max(1, minutes)) min"
    }

    /// A stream or file bitrate. Empty when the server did not report one.
    public static func mbps(_ bitsPerSecond: Int64) -> String {
        bitsPerSecond <= 0 ? "" : "\(oneDecimal(Double(bitsPerSecond) / 1_000_000)) Mbps"
    }
}
