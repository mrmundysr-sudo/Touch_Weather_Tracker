import Foundation

/// Presentation helpers shared by the app and its widgets.
public enum Wx {
    /// SF Symbol name for an NWS short forecast / observation text.
    public static func symbol(_ summary: String?, day: Bool = true) -> String {
        let s = (summary ?? "").lowercased()
        if s.contains("thunder") { return "cloud.bolt.rain.fill" }
        if ["snow", "flurr", "blizzard", "sleet", "ice"].contains(where: s.contains) { return "snowflake" }
        if ["rain", "shower", "drizzle"].contains(where: s.contains) { return "cloud.rain.fill" }
        if ["fog", "haze", "smoke", "mist"].contains(where: s.contains) { return "cloud.fog.fill" }
        if s.contains("partly") || s.contains("mostly sunny") || s.contains("mostly clear") || s.contains("few clouds") || s.contains("scattered") { return day ? "cloud.sun.fill" : "cloud.moon.fill" }
        if s.contains("cloud") || s.contains("overcast") { return "cloud.fill" }
        if s.contains("wind") || s.contains("breezy") { return "wind" }
        return day ? "sun.max.fill" : "moon.stars.fill"
    }
}

public struct DayRow: Identifiable, Sendable {
    public var id: Int64 { startMs }
    public var name: String, summary: String, detail: String
    public var startMs: Int64
    public var high: Double?, low: Double?, precip: Double?
    public var wind: String?
}

/// Pair NWS day/night periods into one row per day (high from the day period, low from the following night).
public func groupDays(_ periods: [DailyPoint], limit: Int = 8) -> [DayRow] {
    var out: [DayRow] = []; var i = 0
    while i < periods.count {
        let p = periods[i]
        if p.highF != nil {
            let night = i + 1 < periods.count && periods[i + 1].lowF != nil ? periods[i + 1] : nil
            let pr: Double? = (p.precipChance ?? night?.precipChance) != nil ? max(p.precipChance ?? 0, night?.precipChance ?? 0) : nil
            out.append(DayRow(name: p.name, summary: p.summary, detail: p.detail + (night.map { "\n\n\($0.name): \($0.detail)" } ?? ""), startMs: p.startMs, high: p.highF, low: night?.lowF, precip: pr, wind: p.windText))
            i += night != nil ? 2 : 1
        } else {
            out.append(DayRow(name: p.name, summary: p.summary, detail: p.detail, startMs: p.startMs, high: nil, low: p.lowF, precip: p.precipChance, wind: p.windText)); i += 1
        }
    }
    return Array(out.prefix(limit))
}

/// Label for a place on a widget: "City, ST" — or the saved name (street address) when two saved places share a city, so a switch stays visible.
public func widgetLabel(for place: Place, among all: [Place]) -> String {
    all.contains { $0.id != place.id && $0.cityState.caseInsensitiveCompare(place.cityState) == .orderedSame } ? place.name : place.cityState
}
