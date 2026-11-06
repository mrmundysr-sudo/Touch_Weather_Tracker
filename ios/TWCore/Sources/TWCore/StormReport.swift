import Foundation

/// What kind of statement an item is. The UI shows this as a label so observations, forecasts, warnings and guidance are never blurred.
public enum ReportKind: String, Sendable { case observed = "Observed", forecast = "Forecast", warning = "Official warning", model = "Model guidance", summary = "Summary"
    public var label: String { rawValue } }

public struct ReportItem: Sendable, Identifiable {
    public var id = UUID()
    public var text: String, kind: ReportKind, source: String
    public var timeMs: Int64?, link: String?
    public init(_ text: String, _ kind: ReportKind, _ source: String, _ timeMs: Int64? = nil, _ link: String? = nil) {
        self.text = text; self.kind = kind; self.source = source; self.timeMs = timeMs; self.link = link
    }
}
public struct ReportSection: Sendable, Identifiable { public var id = UUID(); public var title: String, question: String; public var items: [ReportItem]; public var emptyNote: String? }
public struct DetailRow: Sendable, Identifiable { public var id = UUID(); public var label: String, value: String }

public struct StormInputs: Sendable {
    public var place: Place
    public var storm: ActiveStorm
    public var officialTrack: TrackLine?
    public var coneRings: [[[Double]]]
    public var advisoryHighlights: [(String, String)]
    public var placeAlerts: [Alert]
    public var buoys: [BuoyObs]
    public var waterLevels: [WaterLevelObs]
    public var recon: ReconSummary?
    public var weather: WeatherData?
    public var nowMs: Int64
    public init(place: Place, storm: ActiveStorm, officialTrack: TrackLine?, coneRings: [[[Double]]], advisoryHighlights: [(String, String)], placeAlerts: [Alert],
                buoys: [BuoyObs], waterLevels: [WaterLevelObs], recon: ReconSummary?, weather: WeatherData?, nowMs: Int64 = Units.nowMs()) {
        self.place = place; self.storm = storm; self.officialTrack = officialTrack; self.coneRings = coneRings; self.advisoryHighlights = advisoryHighlights
        self.placeAlerts = placeAlerts; self.buoys = buoys; self.waterLevels = waterLevels; self.recon = recon; self.weather = weather; self.nowMs = nowMs
    }
}

public struct StormReportData: Sendable {
    public var storm: ActiveStorm
    public var placeName: String, headline: String
    public var sections: [ReportSection], details: [DetailRow]
    public var threatens: Bool
    public var generatedMs: Int64
}

public enum StormReportBuilder {
    static let tropicalAlerts = try! NSRegularExpression(pattern: "Hurricane|Tropical Storm|Storm Surge|Typhoon|Extreme Wind|Flash Flood|Flood Warning|Coastal Flood|Tornado|High Wind|Rip Current|Surf", options: .caseInsensitive)
    static func matchesTropical(_ s: String) -> Bool { tropicalAlerts.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil }

    public static func ktToMph(_ kt: Int) -> Int { Int((Double(kt) * 1.15078 / 5).rounded(.toNearestOrAwayFromZero)) * 5 }
    private static func r(_ d: Double) -> Int { Int(d.rounded(.toNearestOrAwayFromZero)) }

    public static func threatens(_ i: StormInputs) -> Bool {
        let d = Geo.distanceMiles(i.place.lat, i.place.lon, i.storm.lat, i.storm.lon)
        if d <= 600 { return true }
        if i.coneRings.contains(where: { Geo.inRing(i.place.lat, i.place.lon, $0) }) { return true }
        let path = (i.officialTrack?.points ?? []).map { ($0.lat, $0.lon) }
        if let m = Geo.minDistanceToPath(i.place.lat, i.place.lon, path), m.miles <= 400 { return true }
        return i.placeAlerts.contains { $0.event.localizedCaseInsensitiveContains("Hurricane") || $0.event.localizedCaseInsensitiveContains("Tropical Storm") || $0.event.localizedCaseInsensitiveContains("Storm Surge") }
    }

    public static func build(_ i: StormInputs) -> StormReportData {
        let tz = i.place.timeZone, s = i.storm
        let dist = Geo.distanceMiles(i.place.lat, i.place.lon, s.lat, s.lon)
        let bearing = Geo.compass(Geo.bearing(i.place.lat, i.place.lon, s.lat, s.lon))
        var sections: [ReportSection] = [], details: [DetailRow] = []

        // 1. What is happening
        let windTxt = s.intensityKt.map { " with maximum sustained winds near \(ktToMph($0)) mph" } ?? ""
        let cat = ActiveStorm.saffir(s.intensityKt).map { " (\($0))" } ?? ""
        let move = (s.moveDirDeg != nil && s.moveSpeedMph != nil) ? ", moving \(Geo.compass(Double(s.moveDirDeg!))) at \(s.moveSpeedMph!) mph" : ""
        sections.append(ReportSection(title: "What is happening", question: "Where is the storm and how strong is it?", items: [
            ReportItem("\(s.classLabel) \(s.name)\(cat) is centered about \(r(dist)) mi \(bearing) of \(i.place.name)\(windTxt)\(move)." + (s.pressureMb.map { " Minimum central pressure \($0) mb." } ?? ""),
                       .observed, "NHC advisory", s.advisoryIssuedMs ?? s.lastUpdateMs, s.publicAdvisoryUrl)], emptyNote: nil))

        // 2. Forecast for this place
        var fc: [ReportItem] = []
        let inCone = i.coneRings.contains { Geo.inRing(i.place.lat, i.place.lon, $0) }
        if !i.coneRings.isEmpty {
            fc.append(ReportItem(inCone
                ? "\(i.place.name) is inside the NHC forecast cone. The cone shows uncertainty in the storm's CENTER track — not the size of the storm or where wind, rain or surge will occur."
                : "\(i.place.name) is outside the current NHC forecast cone. The cone shows uncertainty in the CENTER track only; impacts can extend well outside it.",
                .forecast, "NHC forecast cone", s.advisoryIssuedMs, s.graphicsUrl))
        }
        if let track = i.officialTrack {
            let path = track.points.map { ($0.lat, $0.lon) }
            if let (d, pos) = Geo.minDistanceToPath(i.place.lat, i.place.lon, path) {
                let idx = min(max(Int(pos), 0), track.points.count - 1)
                let next = idx + 1 < track.points.count ? track.points[idx + 1] : nil
                let tau = Double(track.points[idx].tauHours) + Double((next?.tauHours ?? track.points[idx].tauHours) - track.points[idx].tauHours) * (pos - Double(idx))
                let whenMs = track.initMs + Int64(tau * 3_600_000)
                fc.append(d <= 400
                    ? ReportItem("NHC's official forecast track brings the storm's center to within about \(r(d)) mi of \(i.place.name) around \(Units.dayClock(whenMs, tz)). This is the closest approach of the forecast center, not an arrival time for any hazard.", .forecast, "NHC official forecast (ATCF OFCL)", track.initMs)
                    : ReportItem("NHC's official forecast track stays more than \(r(d / 50) * 50) mi from \(i.place.name) through the \((track.points.last?.tauHours ?? 0) / 24)-day forecast.", .forecast, "NHC official forecast (ATCF OFCL)", track.initMs))
            }
        }
        if let w = i.weather {
            let next48 = w.hourly.filter { $0.timeMs >= i.nowMs - 3_600_000 && $0.timeMs <= i.nowMs + 48 * 3_600_000 }
            let maxWind = next48.compactMap(\.windMph).max(), maxGust = next48.compactMap(\.gustMph).max()
            let rain = next48.compactMap(\.precipIn).reduce(0, +)
            if maxWind != nil || maxGust != nil {
                var t = "Local NWS forecast for the next 48 hours: sustained winds up to \(maxWind.map { String(r($0)) } ?? "—") mph"
                if let g = maxGust { t += ", gusts up to \(r(g)) mph" }
                if rain >= 0.1 { t += "; about \(String(format: "%.1f", rain)) in of rain" }
                fc.append(ReportItem(t + ".", .forecast, "NWS \(w.gridId ?? "") gridded forecast", w.forecastUpdatedMs ?? w.fetchedAtMs))
            }
        }
        sections.append(ReportSection(title: "Forecast for \(i.place.name)", question: "What is forecast here?", items: fc, emptyNote: "No forecast could be retrieved for this place right now."))

        // 3. What nearby equipment measured
        var obs: [ReportItem] = []
        for b in i.buoys {
            var parts: [String] = []
            if let w = b.windMph { parts.append("wind \(r(w)) mph" + (b.gustMph.map { ", gusts \(r($0))" } ?? "") + (b.windDirDeg.map { " from \(Geo.compass($0))" } ?? "")) }
            if let h = b.waveHeightFt { parts.append("waves \(String(format: "%.1f", h)) ft" + (b.wavePeriodSec.map { " (\(r($0)) s)" } ?? "")) }
            if let p = b.pressureInHg { parts.append("pressure \(String(format: "%.2f", p)) inHg") }
            if let t = b.waterTempF { parts.append("water \(r(t))°F") }
            if parts.isEmpty { continue }
            obs.append(ReportItem("NDBC buoy \(b.id), \(r(b.distanceMi)) mi \(Geo.compass(b.bearingDeg)) of \(i.place.name): \(parts.joined(separator: "; ")). One buoy does not represent the whole coast.", .observed, "NOAA NDBC buoy \(b.id)", b.timeMs))
        }
        for g in i.waterLevels {
            let dep = g.departureFt.map { d in " — \(String(format: "%.1f", abs(d))) ft \(d >= 0 ? "above" : "below") the predicted tide" } ?? ""
            obs.append(ReportItem("\(g.name) water-level gauge, \(r(g.distanceMi)) mi away: \(String(format: "%.1f", g.levelFtMllw)) ft above MLLW\(dep). This is the measured water level, not a storm-surge forecast.", .observed, "NOAA CO-OPS \(g.id)" + (g.preliminary ? " (preliminary)" : ""), g.timeMs))
        }
        if let rc = i.recon {
            var bits: [String] = []
            if let k = rc.maxSfmrKt { bits.append("highest surface wind estimate (SFMR) \(k) kt (\(ktToMph(k)) mph)") }
            if let k = rc.maxFlWindKt { bits.append("highest flight-level wind \(k) kt") }
            if let p = rc.minExtrapSlpMb { bits.append("lowest extrapolated surface pressure \(String(format: "%.1f", p)) mb") }
            if !bits.isEmpty, let latest = rc.latestObsMs {
                obs.append(ReportItem("Aircraft reconnaissance in the last \((i.nowMs - rc.windowStartMs) / 3_600_000) h: \(bits.joined(separator: "; ")). Flight-level winds are not surface winds.", .observed, "NHC recon (HDOB)", latest))
            }
            if let v = rc.vortex {
                obs.append(ReportItem("Latest aircraft center fix: \(String(format: "%.2f", v.lat))°, \(String(format: "%.2f", v.lon))°" + (v.minSlpMb.map { "; minimum sea-level pressure \($0) mb" } ?? "") + (v.maxFlWindKt.map { "; max flight-level wind \($0) kt" } ?? "") + ".", .observed, "NHC Vortex Data Message", v.timeMs))
            }
            let mbl = rc.dropsondes.filter { $0.mblWindKt != nil }
            if !mbl.isEmpty {
                obs.append(ReportItem("\(mbl.count) dropsonde\(mbl.count > 1 ? "s" : "") released nearby; strongest lowest-500-m mean wind reported \(mbl.compactMap(\.mblWindKt).max()!) kt.", .observed, "NHC dropsondes", mbl.compactMap(\.relMs).max()))
            }
        }
        sections.append(ReportSection(title: "What nearby equipment measured", question: "What has actually been observed?", items: obs,
                                      emptyNote: "No fresh buoy, water-gauge or aircraft readings are available near \(i.place.name). Missing readings are not a sign of safety."))

        // 4. Warnings and actions
        var act: [ReportItem] = []
        for a in i.placeAlerts.filter({ matchesTropical($0.event) }).sorted(by: { $0.level < $1.level }) {
            act.append(ReportItem(a.event + (a.endMs.map { " until \(Units.dayClock($0, tz))" } ?? "") + ". \(a.headline ?? a.areaDesc)", .warning, a.senderName.isEmpty ? "NWS" : a.senderName, a.sentMs))
        }
        if let w = i.advisoryHighlights.first(where: { $0.0.localizedCaseInsensitiveContains("WATCHES") }) {
            act.append(ReportItem(String(w.1.prefix(420)), .warning, "NHC public advisory", s.advisoryIssuedMs, s.publicAdvisoryUrl))
        }
        act.append(ReportItem("Storm-surge watches/warnings and inundation maps come only from NHC and your local NWS office. Open the official peak storm-surge map for the latest forecast.", .summary, "NHC", nil, "https://www.nhc.noaa.gov/surge/"))
        sections.append(ReportSection(title: "Warnings and actions", question: "What warnings or actions apply?", items: act, emptyNote: nil))

        // Details panel
        details.append(DetailRow(label: "Advisory", value: "#\(s.advisoryNumber ?? "—") issued \(s.advisoryIssuedMs.map { Units.stamp($0, tz) } ?? "—")"))
        details.append(DetailRow(label: "Official forecast track", value: i.officialTrack.map { "NHC OFCL initialized \(Units.stamp($0.initMs, tz))" } ?? "Not available"))
        details.append(DetailRow(label: "Forecast cone", value: i.coneRings.isEmpty ? "Not available" : "NHC ArcGIS service"))
        for b in i.buoys { details.append(DetailRow(label: "Buoy \(b.id)", value: "\(r(b.distanceMi)) mi \(Geo.compass(b.bearingDeg)); reported \(Units.ago(b.timeMs, now: i.nowMs))")) }
        for g in i.waterLevels { details.append(DetailRow(label: "Gauge \(g.id)", value: "\(g.name); \(r(g.distanceMi)) mi; reported \(Units.ago(g.timeMs, now: i.nowMs))")) }
        details.append(DetailRow(label: "Recon", value: i.recon.map { "\($0.hdobCount) HDOB observations within range, latest \($0.latestObsMs.map { Units.ago($0, now: i.nowMs) } ?? "—")" } ?? "No recent aircraft data near this storm"))
        details.append(DetailRow(label: "Definitions", value: "SFMR = stepped-frequency microwave radiometer surface-wind estimate. MLLW = mean lower low water datum. HDOB = high-density observation. Kt = knots (1 kt ≈ 1.15 mph)."))

        let threat = threatens(i)
        let has = { (e: String) in i.placeAlerts.contains { $0.event.localizedCaseInsensitiveContains(e) } }
        let headline: String
        if has("Hurricane Warning") { headline = "Hurricane Warning in effect for \(i.place.name)" }
        else if has("Tropical Storm Warning") { headline = "Tropical Storm Warning in effect for \(i.place.name)" }
        else if has("Storm Surge") { headline = "Storm Surge alert for \(i.place.name)" }
        else if threat { headline = "\(s.classLabel) \(s.name) — what it means for \(i.place.name)" }
        else { headline = "\(s.classLabel) \(s.name) is not currently forecast to affect \(i.place.name)" }
        return StormReportData(storm: s, placeName: i.place.name, headline: headline, sections: sections, details: details, threatens: threat, generatedMs: i.nowMs)
    }
}

/// Result of evaluating the temporary hurricane event-mode trigger from the latest OFFICIAL NHC forecast.
public struct EventSignal: Sendable { public var eligibleStorm: ActiveStorm?; public var reason: String?; public var hurricaneWarningInEffect: Bool }

public enum EventMode {
    /// Eligible when NHC's official forecast (ATCF OFCL, never a GFS/ECMWF run) has the center reach a U.S. state within 72 h at hurricane
    /// strength, or when a U.S. Hurricane Watch/Warning is already in effect.
    public static func evaluate(storms: [ActiveStorm], hurricaneAlerts: [Alert]) async -> EventSignal {
        let warned = hurricaneAlerts.contains { $0.event.caseInsensitiveCompare("Hurricane Warning") == .orderedSame || $0.event.caseInsensitiveCompare("Hurricane Watch") == .orderedSame }
        for s in storms {
            guard let fixes = try? await NhcSource.aDeck(s.id), let ofcl = Atcf.latestTrack(fixes, tech: "OFCL") else { continue }
            let upTo72 = ofcl.points.filter { $0.tauHours <= 72 }
            guard upTo72.count >= 2, upTo72.contains(where: { ($0.vmaxKt ?? 0) >= 64 }) else { continue }
            let states = (try? await CensusSource.statesCrossed(upTo72.map { ($0.lat, $0.lon) })) ?? []
            if !states.isEmpty {
                return EventSignal(eligibleStorm: s, reason: "NHC's official forecast shows \(s.name) at hurricane strength reaching \(states.joined(separator: ", ")) within 72 hours.", hurricaneWarningInEffect: warned)
            }
        }
        if warned {
            return EventSignal(eligibleStorm: storms.first { $0.isHurricaneStrength } ?? storms.first, reason: "A Hurricane Watch or Warning is in effect for part of the U.S.", hurricaneWarningInEffect: true)
        }
        return EventSignal(eligibleStorm: nil, reason: nil, hurricaneWarningInEffect: false)
    }
}
