import SwiftUI
import WidgetKit
import TWCore

struct StormWatchWidgetView: View {
    @Environment(\.widgetFamily) var systemFamily
    @Environment(\.twFamilyOverride) var familyOverride
    var family: WidgetFamily { familyOverride ?? systemFamily }
    let e: WxEntry
    var body: some View {
        let storms = e.storms ?? []
        let s = storms.max { ($0.intensityKt ?? 0) < ($1.intensityKt ?? 0) }
        Group {
            if e.locked { VStack(spacing: 4) { Text("Locked").font(.headline); Text("Open Touch Weather Tracker to unlock").font(.caption).multilineTextAlignment(.center) }.frame(maxWidth: .infinity, maxHeight: .infinity) }
            else if family == .accessoryRectangular {
                VStack(alignment: .leading) {
                    Label("Storm Watch", systemImage: "hurricane").font(.headline)
                    if let s { Text("\(s.classLabel) \(s.name) · \(s.intensityKt.map { "\(StormReportBuilder.ktToMph($0)) mph" } ?? "")").font(.caption).lineLimit(2) }
                    else { Text(e.stormsFetchedMs == nil ? "No storm data yet" : "No active tropical cyclones").font(.caption) }
                }
            } else {
                VStack(alignment: .leading, spacing: 3) {
                    Label("Storm Watch", systemImage: "hurricane").font(.caption.weight(.bold)).opacity(0.9)
                    if e.stormsFetchedMs == nil { Text("No storm data yet").font(.headline); Text("Open the app to load NHC data.").font(.caption).opacity(0.85) }
                    else if let s {
                        Text("\(s.classLabel) \(s.name)").font(.headline).lineLimit(2).minimumScaleFactor(0.8)
                        Text([ActiveStorm.saffir(s.intensityKt), s.intensityKt.map { "\(StormReportBuilder.ktToMph($0)) mph" }].compactMap { $0 }.joined(separator: " · ")).font(.subheadline)
                        if let p = e.place { Text("\(Int(Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).rounded())) mi \(Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))) of \(e.label)").font(.caption).lineLimit(2) }
                        if storms.count > 1 && family != .systemSmall { Text("+\(storms.count - 1) more active").font(.caption).opacity(0.85) }
                        Text("Advisory \(s.advisoryNumber ?? "—") · \(s.advisoryIssuedMs.map { Units.dayClock($0, e.tz) } ?? "time unknown")").font(.system(size: 10)).opacity(0.85).lineLimit(2)
                    } else {
                        Text("No active tropical cyclones").font(.headline).lineLimit(2)
                        Text("Per the National Hurricane Center. This does not mean other hazards are absent.").font(.caption).opacity(0.85).lineLimit(3)
                    }
                    Spacer(minLength: 0)
                    Text(e.stormsFetchedMs.map { "Updated \(Units.clock($0, e.tz))" } ?? "Not updated yet").font(.system(size: 9)).opacity(0.8)
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .foregroundStyle(.white)
        .containerBackground(for: .widget) { LinearGradient(colors: s != nil && !e.locked ? [Color(hex: 0x7A3B8F), Color(hex: 0x2A0F3A)] : [Color(hex: 0x27415C), Color(hex: 0x0E1D2F)], startPoint: .top, endPoint: .bottom) }
        .widgetURL(URL(string: "touchweather://tab/storms")!)
    }
}

struct StormWatchWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "twt.storm", provider: HomeProvider()) { StormWatchWidgetView(e: $0) }
            .configurationDisplayName("Storm Watch").description("Active hurricane status, distance from Home and the latest official advisory time.")
            .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
    }
}
