import SwiftUI
import WidgetKit
import AppIntents
import TWCore

// MARK: providers

struct HomeProvider: TimelineProvider {
    func placeholder(in context: Context) -> WxEntry { .placeholder() }
    func getSnapshot(in context: Context, completion: @escaping (WxEntry) -> Void) {
        if context.isPreview { completion(.placeholder()) } else { Task { completion(await WidgetLoader.load(placeId: nil, follow: true, refreshStale: false)) } }
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<WxEntry>) -> Void) {
        Task { completion(Timeline(entries: [await WidgetLoader.load(placeId: nil, follow: true)], policy: .after(WidgetLoader.nextRefresh))) }
    }
}

struct SavedProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> WxEntry { .placeholder() }
    func snapshot(for configuration: SavedPlaceIntent, in context: Context) async -> WxEntry {
        context.isPreview ? .placeholder() : await WidgetLoader.load(placeId: configuration.place?.id, follow: configuration.place == nil, refreshStale: false)
    }
    func timeline(for configuration: SavedPlaceIntent, in context: Context) async -> Timeline<WxEntry> {
        Timeline(entries: [await WidgetLoader.load(placeId: configuration.place?.id, follow: configuration.place == nil)], policy: .after(WidgetLoader.nextRefresh))
    }
}

// MARK: shared pieces

private func updatedText(_ e: WxEntry, short: Bool = false) -> String {
    guard let ms = e.updatedMs else { return "Not updated yet" }
    return short ? Units.clock(ms, e.tz).replacingOccurrences(of: " ", with: "") : "Updated \(Units.clock(ms, e.tz))"
}
private func url(_ tab: String, _ e: WxEntry) -> URL { URL(string: "touchweather://tab/\(tab)" + (e.place.map { "?place=\($0.id)" } ?? ""))! }

/// Shows the lock / no-data states, or the real layout.
private struct Guarded<Content: View>: View {
    let e: WxEntry
    var needsPlace = true
    @ViewBuilder var content: Content
    var body: some View {
        Group {
            if e.locked { Message(title: "Locked", text: "Open Touch Weather Tracker to unlock") }
            else if !e.hasSharedContainer && e.place == nil { Message(title: "Open the app", text: "Widgets need the signed app's shared data") }
            else if needsPlace && e.place == nil { Message(title: "No place", text: "Open Touch Weather Tracker to add a place") }
            else { content }
        }
        .foregroundStyle(.white)
        .containerBackground(for: .widget) { LinearGradient(colors: (e.locked || (needsPlace && e.place == nil)) ? [Color(hex: 0x27415C), Color(hex: 0x0E1D2F)] : e.background, startPoint: .top, endPoint: .bottom) }
    }
}
private struct Message: View {
    let title: String, text: String
    var body: some View { VStack(spacing: 4) { Text(title).font(.headline); Text(text).font(.caption).multilineTextAlignment(.center).opacity(0.85) }.frame(maxWidth: .infinity, maxHeight: .infinity) }
}
private func icon(_ name: String, _ size: CGFloat) -> some View { Image(systemName: name).symbolRenderingMode(.multicolor).font(.system(size: size)) }

// MARK: Temp (small + lock screen)

struct TempWidgetView: View {
    @Environment(\.widgetFamily) var systemFamily
    @Environment(\.twFamilyOverride) var familyOverride
    var family: WidgetFamily { familyOverride ?? systemFamily }
    let e: WxEntry
    var body: some View {
        Guarded(e: e) {
            switch family {
            case .accessoryCircular:
                ZStack { AccessoryWidgetBackground(); VStack(spacing: 0) { Image(systemName: Wx.symbol(e.summary, day: e.isDay)); Text(Units.temp(e.temp)).font(.headline) } }
            case .accessoryInline:
                Label("\(Units.temp(e.temp)) \(e.summary ?? "")", systemImage: Wx.symbol(e.summary, day: e.isDay))
            case .accessoryRectangular:
                VStack(alignment: .leading) { Text(e.label).font(.headline).lineLimit(1); HStack { Image(systemName: Wx.symbol(e.summary, day: e.isDay)); Text(Units.temp(e.temp)).font(.title3.bold()); Text(e.summary ?? "").font(.caption).lineLimit(1) }; Text(updatedText(e)).font(.caption2) }
            default:
                VStack(spacing: 2) {
                    Text(e.label).font(.caption.weight(.bold)).lineLimit(1)
                    Spacer(minLength: 0)
                    icon(Wx.symbol(e.summary, day: e.isDay), 44)
                    Text(Units.temp(e.temp)).font(.system(size: 40, weight: .semibold))
                    Text(e.summary ?? "").font(.caption).lineLimit(1)
                    Spacer(minLength: 0)
                    Text("↻ " + updatedText(e)).font(.system(size: 9)).opacity(0.8)
                }.frame(maxWidth: .infinity)
            }
        }.widgetURL(url("today", e))
    }
}

struct TempWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "twt.temp", provider: HomeProvider()) { TempWidgetView(e: $0) }
            .configurationDisplayName("Temp").description("Icon and temperature for your Home. Updated periodically by iOS, not instantly.")
            .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryInline, .accessoryRectangular])
    }
}

// MARK: Hourly (medium)

struct HourlyWidgetView: View {
    let e: WxEntry
    var body: some View {
        Guarded(e: e) {
            let now = Units.nowMs()
            let hours = Array((e.weather?.hourly ?? []).filter { $0.timeMs >= now - 30 * 60_000 }.prefix(6))
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(e.label).font(.caption.weight(.bold)).lineLimit(1)
                        HStack(alignment: .firstTextBaseline, spacing: 6) { Text(Units.temp(e.temp)).font(.system(size: 32, weight: .semibold)); Text(e.summary ?? "").font(.caption).lineLimit(1) }
                    }
                    Spacer(); icon(Wx.symbol(e.summary, day: e.isDay), 34)
                }
                Spacer(minLength: 0)
                HStack { ForEach(hours, id: \.timeMs) { h in
                    VStack(spacing: 2) {
                        Text(Units.clock(h.timeMs, e.tz).replacingOccurrences(of: ":00", with: "").replacingOccurrences(of: " ", with: "")).font(.system(size: 10)).opacity(0.8)
                        icon(Wx.symbol(h.summary, day: h.isDay), 18)
                        Text(Units.temp(h.tempF)).font(.system(size: 13, weight: .semibold))
                    }.frame(maxWidth: .infinity) } }
                Text(updatedText(e) + " · NWS forecast").font(.system(size: 9)).opacity(0.8)
            }
        }.widgetURL(url("today", e))
    }
}
struct HourlyWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "twt.hourly", provider: HomeProvider()) { HourlyWidgetView(e: $0) }
            .configurationDisplayName("Hourly").description("Current conditions plus the next hours for your Home.").supportedFamilies([.systemMedium])
    }
}

// MARK: Today (medium + large)

struct TodayWidgetView: View {
    @Environment(\.widgetFamily) var systemFamily
    @Environment(\.twFamilyOverride) var familyOverride
    var family: WidgetFamily { familyOverride ?? systemFamily }
    let e: WxEntry
    var body: some View {
        Guarded(e: e) {
            let c = e.weather?.conditions
            let day = groupDays(e.weather?.daily ?? []).first
            let now = Units.nowMs()
            let hours = Array((e.weather?.hourly ?? []).filter { $0.timeMs >= now - 30 * 60_000 }.prefix(family == .systemLarge ? 6 : 3))
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .center) {
                    Text(Units.temp(e.temp)).font(.system(size: 40, weight: .semibold))
                    VStack(alignment: .leading) { Text(e.summary ?? "—").font(.subheadline.weight(.bold)).lineLimit(2); Text(e.label).font(.caption).opacity(0.85).lineLimit(1) }
                    Spacer()
                    VStack(alignment: .trailing) {
                        Text("H \(Units.temp(day?.high)) / L \(Units.temp(day?.low))").font(.caption.weight(.bold))
                        Text("Feels \(Units.temp(c?.feelsLikeF ?? e.temp))").font(.caption).opacity(0.85)
                        if let w = c?.windMph { Text("Wind \(Int(w.rounded())) mph").font(.caption).opacity(0.85) }
                    }
                }
                Spacer(minLength: 0)
                HStack(spacing: 0) { ForEach(hours, id: \.timeMs) { h in
                    VStack(spacing: 1) {
                        Text(Units.clock(h.timeMs, e.tz).replacingOccurrences(of: ":00", with: "").replacingOccurrences(of: " ", with: "")).font(.system(size: 10)).opacity(0.8).lineLimit(1)
                        icon(Wx.symbol(h.summary, day: h.isDay), 20)
                        Text(Units.temp(h.tempF)).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                    }.frame(maxWidth: .infinity) } }
                if family == .systemLarge {
                    Divider().overlay(.white.opacity(0.3))
                    ForEach(Array(groupDays(e.weather?.daily ?? [], limit: 5).enumerated()), id: \.element.id) { i, d in
                        HStack { Text(i == 0 ? "Today" : Units.dayName(d.startMs, e.tz)).font(.caption.weight(.semibold)).frame(width: 52, alignment: .leading); icon(Wx.symbol(d.summary), 18); Text(d.summary).font(.caption).lineLimit(1); Spacer()
                            Text(Units.temp(d.high)).font(.caption.weight(.bold)); Text(Units.temp(d.low)).font(.caption).opacity(0.8) }
                    }
                    Spacer(minLength: 0)
                }
                Text(updatedText(e) + (c?.observedAtMs.map { " · observed \(Units.clock($0, e.tz))" } ?? "")).font(.system(size: 9)).opacity(0.8)
            }
        }.widgetURL(url("today", e))
    }
}
struct TodayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "twt.today", provider: HomeProvider()) { TodayWidgetView(e: $0) }
            .configurationDisplayName("Today").description("Temperature, high/low, feels-like, wind and the next hours.").supportedFamilies([.systemMedium, .systemLarge])
    }
}

// MARK: 5-Day (medium compact strip; large vertical list)

struct FiveDayWidgetView: View {
    @Environment(\.widgetFamily) var systemFamily
    @Environment(\.twFamilyOverride) var familyOverride
    var family: WidgetFamily { familyOverride ?? systemFamily }
    let e: WxEntry
    var body: some View {
        Guarded(e: e) {
            let days = groupDays(e.weather?.daily ?? [], limit: family == .systemLarge ? 7 : 5)
            if family == .systemLarge {
                VStack(alignment: .leading, spacing: 4) {
                    HStack { Text(e.label).font(.subheadline.weight(.bold)).lineLimit(1); Spacer(); Text(Units.temp(e.temp)).font(.title3.bold()) }
                    ForEach(Array(days.enumerated()), id: \.element.id) { i, d in
                        HStack { Text(i == 0 ? "Today" : Units.dayName(d.startMs, e.tz)).font(.caption.weight(.semibold)).frame(width: 52, alignment: .leading); icon(Wx.symbol(d.summary), 18); Text(d.summary).font(.caption).lineLimit(1); Spacer()
                            Text(Units.temp(d.high)).font(.caption.weight(.bold)); Text(Units.temp(d.low)).font(.caption).opacity(0.8) }
                    }
                    Spacer(minLength: 0)
                    Text(updatedText(e) + " · NWS forecast").font(.system(size: 9)).opacity(0.8)
                }
            } else {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.label).font(.system(size: 11, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                        Text(Units.temp(e.temp)).font(.system(size: 26, weight: .semibold))
                        Text(updatedText(e, short: true)).font(.system(size: 9)).opacity(0.8)
                    }.frame(width: 80, alignment: .leading)
                    ForEach(Array(days.enumerated()), id: \.element.id) { i, d in
                        VStack(spacing: 2) {
                            Text(i == 0 ? "Today" : Units.dayName(d.startMs, e.tz)).font(.system(size: 10)).opacity(0.8)
                            icon(Wx.symbol(d.summary), 22)
                            Text(Units.temp(d.high)).font(.system(size: 13, weight: .bold)); Text(Units.temp(d.low)).font(.system(size: 11)).opacity(0.8)
                        }.frame(maxWidth: .infinity)
                    }
                }
            }
        }.widgetURL(url("today", e))
    }
}
struct FiveDayWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "twt.fiveday", provider: HomeProvider()) { FiveDayWidgetView(e: $0) }
            .configurationDisplayName("5-Day").description("Five-day forecast with icons and highs/lows. iOS has no 4×1 widget; this uses a compact strip.").supportedFamilies([.systemMedium, .systemLarge])
    }
}

// MARK: Saved Place (configurable)

struct SavedPlaceWidgetView: View {
    @Environment(\.widgetFamily) var systemFamily
    @Environment(\.twFamilyOverride) var familyOverride
    var family: WidgetFamily { familyOverride ?? systemFamily }
    let e: WxEntry
    var body: some View {
        Guarded(e: e) {
            let alert = e.topAlert
            let alertLine = Group {
                if let a = alert { Label("\(a.level.rawValue.capitalized): \(a.event)\((e.alerts?.count ?? 0) > 1 ? " +\((e.alerts?.count ?? 1) - 1)" : "")", systemImage: "exclamationmark.triangle.fill").font(.caption.weight(.bold)).lineLimit(2) }
                else if e.alerts != nil { Text("No active alerts found").font(.caption2).opacity(0.85) }
            }
            if family == .systemMedium {
                HStack(spacing: 12) {
                    icon(Wx.symbol(e.summary, day: e.isDay), 40)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline) { Text(Units.temp(e.temp)).font(.system(size: 30, weight: .semibold)); Text(e.label).font(.caption.weight(.bold)).lineLimit(1) }
                        Text(e.summary ?? "").font(.caption).opacity(0.85).lineLimit(1)
                        alertLine
                    }
                    Spacer(); Text(updatedText(e, short: true)).font(.system(size: 9)).opacity(0.8)
                }
            } else {
                VStack(alignment: .leading, spacing: 3) {
                    Text(e.label).font(.caption.weight(.bold)).lineLimit(1)
                    HStack { icon(Wx.symbol(e.summary, day: e.isDay), 28); Text(Units.temp(e.temp)).font(.system(size: 28, weight: .semibold)) }
                    Text(e.summary ?? "").font(.caption).opacity(0.85).lineLimit(1)
                    alertLine
                    Spacer(minLength: 0)
                    Text(updatedText(e)).font(.system(size: 9)).opacity(0.8)
                }
            }
        }.widgetURL(url("alerts", e))
    }
}
struct SavedPlaceWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "twt.saved", intent: SavedPlaceIntent.self, provider: SavedProvider()) { SavedPlaceWidgetView(e: $0) }
            .configurationDisplayName("Saved Place").description("Weather and the top active alert for a place you choose.").supportedFamilies([.systemSmall, .systemMedium])
    }
}
