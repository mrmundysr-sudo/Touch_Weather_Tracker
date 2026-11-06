import Foundation

public enum Units {
    public static func cToF(_ c: Double) -> Double { c * 9 / 5 + 32 }
    public static func fToC(_ f: Double) -> Double { (f - 32) * 5 / 9 }
    public static func mpsToMph(_ v: Double) -> Double { v * 2.236936 }
    public static func kmhToMph(_ v: Double) -> Double { v * 0.621371 }
    public static func knToMph(_ v: Double) -> Double { v * 1.150779 }
    public static func mToMiles(_ v: Double) -> Double { v / 1609.344 }
    public static func mToFeet(_ v: Double) -> Double { v * 3.28084 }
    public static func mmToIn(_ v: Double) -> Double { v / 25.4 }
    public static func paToInHg(_ v: Double) -> Double { v * 0.0002953 }
    public static func hpaToInHg(_ v: Double) -> Double { v * 0.02953 }

    private static func r(_ d: Double) -> Int { Int(d.rounded(.toNearestOrAwayFromZero)) }
    public static func temp(_ f: Double?) -> String { f.map { "\(r($0))°" } ?? "—" }
    public static func mph(_ v: Double?) -> String { v.map { "\(r($0)) mph" } ?? "—" }
    public static func pct(_ v: Double?) -> String { v.map { "\(r($0))%" } ?? "—" }
    public static func miles(_ v: Double?) -> String { v.map { $0 >= 10 ? "\(r($0)) mi" : String(format: "%.1f mi", $0) } ?? "—" }
    public static func inches(_ v: Double?) -> String { v.map { String(format: "%.2f in", $0) } ?? "—" }

    // ---- time helpers: always render in the place's own time zone when known ----
    public static func zone(_ id: String?) -> TimeZone {
        if let id, !id.isEmpty, let z = TimeZone(identifier: id) { return z }
        return .current
    }
    private static func fmt(_ pattern: String, _ tz: String?) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = zone(tz)
        f.dateFormat = pattern
        return f
    }
    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    public static func clock(_ ms: Int64, _ tz: String? = nil) -> String { fmt("h:mm a", tz).string(from: date(ms)) }
    public static func dayClock(_ ms: Int64, _ tz: String? = nil) -> String { fmt("EEE h:mm a", tz).string(from: date(ms)) }
    public static func dayName(_ ms: Int64, _ tz: String? = nil) -> String { fmt("EEE", tz).string(from: date(ms)) }
    public static func stamp(_ ms: Int64, _ tz: String? = nil) -> String { fmt("MMM d, h:mm a z", tz).string(from: date(ms)) }
    public static func shortDate(_ ms: Int64, _ tz: String? = nil) -> String { fmt("MMM d", tz).string(from: date(ms)) }

    public static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// ISO-8601 (with or without fractional seconds / offsets) → epoch ms.
    public static func parseIso(_ s: String?) -> Int64? {
        guard let s, !s.isEmpty else { return nil }
        let withFrac = ISO8601DateFormatter(); withFrac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter(); plain.formatOptions = [.withInternetDateTime]
        if let d = withFrac.date(from: s) ?? plain.date(from: s) { return Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        return nil
    }

    /// "12 min ago", "3 h ago".
    public static func ago(_ ms: Int64, now: Int64 = Units.nowMs()) -> String {
        let m = max(0, (now - ms) / 60000)
        if m < 1 { return "just now" }
        if m < 60 { return "\(m) min ago" }
        if m < 60 * 36 { return "\(m / 60) h ago" }
        return "\(m / 1440) d ago"
    }
    public static func until(_ ms: Int64, now: Int64 = Units.nowMs()) -> String {
        let m = (ms - now) / 60000
        if m < 0 { return "expired" }
        if m < 60 { return "in \(m) min" }
        if m < 60 * 36 { return "in \(m / 60) h" }
        return "in \(m / 1440) d"
    }
}
