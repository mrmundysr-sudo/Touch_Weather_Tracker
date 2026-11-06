import Foundation

public struct Hdob: Sendable, Equatable {
    public var timeMs: Int64, lat: Double, lon: Double
    public var flightLevelWindKt: Int?, peakFlWindKt: Int?, sfmrWindKt: Int?
    public var extrapSlpMb: Double?, staticPressureMb: Double?
}
public struct VortexFix: Sendable, Equatable {
    public var stormId: String, timeMs: Int64, lat: Double, lon: Double
    public var minSlpMb: Int?, maxFlWindKt: Int?
}
public struct DropsondeObs: Sendable, Equatable { public var relMs: Int64?; public var lat: Double?; public var lon: Double?; public var mblWindDirDeg: Int?; public var mblWindKt: Int? }

public struct ReconSummary: Sendable {
    public var hdobCount: Int, windowStartMs: Int64, latestObsMs: Int64?
    public var maxSfmrKt: Int?, maxFlWindKt: Int?, minExtrapSlpMb: Double?
    public var vortex: VortexFix?, dropsondes: [DropsondeObs]
}

/// Official NHC reconnaissance products from the public NHC recon archive tree and tgftp (Vortex Data Message). No third-party displays.
public enum ReconSource {
    static let S = "NHC recon"
    static let root = "https://www.nhc.noaa.gov/archive/recon"

    static func dirs(_ basin: String) -> (hdob: String, drop: String) {
        switch basin.lowercased() { case "al": return ("AHONT1", "REPNT3"); case "ep": return ("AHOPN1", "REPPN3"); default: return ("AHOPA1", "REPPA3") }
    }

    static var utc: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!; return c }

    static func listFiles(_ html: String, prefix: String) -> [(name: String, ms: Int64)] {
        guard let re = try? NSRegularExpression(pattern: "href=\"(\(prefix)-[A-Z]+\\.(\\d{12})\\.txt)\"") else { return [] }
        let ns = html as NSString
        return re.matches(in: html, range: NSRange(location: 0, length: ns.length)).compactMap { m in
            let name = ns.substring(with: m.range(at: 1)), ts = ns.substring(with: m.range(at: 2))
            guard let y = Int(ts.prefix(4)), let mo = Int(ts.dropFirst(4).prefix(2)), let d = Int(ts.dropFirst(6).prefix(2)), let h = Int(ts.dropFirst(8).prefix(2)), let mi = Int(ts.suffix(2)),
                  let date = utc.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi)) else { return nil }
            return (name, Int64(date.timeIntervalSince1970 * 1000))
        }
    }

    /// Parse an HDOB message body; `issuedMs` supplies the UTC date.
    public static func parseHdob(_ text: String, issuedMs: Int64) -> [Hdob] {
        let issued = Date(timeIntervalSince1970: Double(issuedMs) / 1000)
        let base = utc.dateComponents([.year, .month, .day], from: issued)
        var out: [Hdob] = []
        for ln in text.split(whereSeparator: \.isNewline) {
            let f = ln.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard f.count >= 13, f[0].count == 6, f[0].allSatisfy(\.isNumber), f[1].count == 5, "NS".contains(f[1].last!) else { continue }
            func coord(_ s: String, lat: Bool) -> Double? {
                let degLen = lat ? 2 : 3
                guard s.count == degLen + 3, let deg = Int(s.prefix(degLen)), let min = Int(s.dropFirst(degLen).prefix(2)) else { return nil }
                let v = Double(deg) + Double(min) / 60
                return (s.last == "S" || s.last == "W") ? -v : v
            }
            guard let la = coord(f[1], lat: true), let lo = coord(f[2], lat: false),
                  let hh = Int(f[0].prefix(2)), let mm = Int(f[0].dropFirst(2).prefix(2)), let ss = Int(f[0].suffix(2)),
                  let date = utc.date(from: DateComponents(year: base.year, month: base.month, day: base.day, hour: hh, minute: mm, second: ss)) else { continue }
            var t = Int64(date.timeIntervalSince1970 * 1000)
            if t - issuedMs > 3 * 3_600_000 { t -= 86_400_000 }   // observation from the previous UTC day
            let wind = f[8]
            let fl: Int? = (wind.count == 6 && wind.allSatisfy(\.isNumber)) ? Int(wind.suffix(3)) : nil
            let staticP = Int(f[3]).map { $0 >= 1000 ? Double($0) / 10 : Double($0) / 10 + 1000 }
            let extrapRaw = Int(f[5]).map { $0 >= 5000 ? Double($0) / 10 : Double($0) / 10 + 1000 }
            // Extrapolated surface pressure is only meaningful for low-level (roughly 700 mb) flight legs.
            let extrap = extrapRaw.flatMap { (850.0...1050.0).contains($0) && staticP != nil && (600.0...850.0).contains(staticP!) ? $0 : nil }
            out.append(Hdob(timeMs: t, lat: la, lon: lo, flightLevelWindKt: fl, peakFlWindKt: Int(f[9]), sfmrWindKt: Int(f[10]), extrapSlpMb: extrap, staticPressureMb: staticP))
        }
        return out
    }

    /// "A. 09/22:47:30Z", "B. 29.79 deg N 086.76 deg W", "D. 972 mb", "MAX FL WIND 128 KT"
    public static func parseVdm(_ text: String, nowMs: Int64 = Units.nowMs()) -> VortexFix? {
        guard text.contains("VORTEX DATA MESSAGE"),
              let id = text.firstMatch(#"VORTEX DATA MESSAGE\s+([A-Z]{2}\d{6})"#)?[1],
              let a = text.firstMatch(#"A\.\s+(\d{2})/(\d{2}):(\d{2}):(\d{2})Z"#),
              let b = text.firstMatch(#"B\.\s+([\d.]+) deg ([NS])\s+([\d.]+) deg ([EW])"#),
              let la = Double(b[1]), let lo = Double(b[3]), let day = Int(a[1]) else { return nil }
        let lat = b[2] == "S" ? -la : la, lon = b[4] == "W" ? -lo : lo
        let now = Date(timeIntervalSince1970: Double(nowMs) / 1000)
        let c = utc
        func candidate(monthOffset: Int) -> Date? {
            guard let m = c.date(byAdding: .month, value: monthOffset, to: now) else { return nil }
            var dc = c.dateComponents([.year, .month], from: m); dc.day = day
            dc.hour = Int(a[2]); dc.minute = Int(a[3]); dc.second = Int(a[4])
            guard let d = c.date(from: dc), c.component(.day, from: d) == day else { return nil }
            return d
        }
        var when = candidate(monthOffset: 0)
        if when == nil || when! > now.addingTimeInterval(86_400) { when = candidate(monthOffset: -1) }
        guard let w = when else { return nil }
        let slp = text.firstMatch(#"\nD\.\s+(\d{3,4}) mb"#).flatMap { Int($0[1]) }
        let maxFl = text.firstMatch(#"MAX FL WIND\s+(\d+)\s*KT"#).flatMap { Int($0[1]) }
        return VortexFix(stormId: id.lowercased(), timeMs: Int64(w.timeIntervalSince1970 * 1000), lat: lat, lon: lon, minSlpMb: slp, maxFlWindKt: maxFl)
    }

    /// Dropsonde TEMP DROP: "MBL WND dddff", release position "REL 2692N08526W hhmmss".
    public static func parseDrop(_ text: String) -> DropsondeObs? {
        guard text.contains("XXAA") else { return nil }
        let mbl = text.firstMatch(#"MBL WND (\d{3})(\d{2,3})"#)
        var lat: Double?, lon: Double?
        if let r = text.firstMatch(#"REL (\d{4})([NS])(\d{5})([EW]) (\d{6})"#) {
            if let d = Int(r[1].prefix(2)), let m = Int(r[1].suffix(2)) { lat = (Double(d) + Double(m) / 60) * (r[2] == "S" ? -1 : 1) }
            if let d = Int(r[3].prefix(3)), let m = Int(r[3].suffix(2)) { lon = (Double(d) + Double(m) / 60) * (r[4] == "W" ? -1 : 1) }
        }
        return DropsondeObs(relMs: nil, lat: lat, lon: lon, mblWindDirDeg: mbl.flatMap { Int($0[1]) }, mblWindKt: mbl.flatMap { Int($0[2]) })
    }

    /// Gather recent recon near a storm. Observations must be within `radiusKm` of the storm center and within `hours`; nil when nothing usable.
    public static func summary(stormId: String, centerLat: Double, centerLon: Double, hours: Int = 12, radiusKm: Double = 450) async -> ReconSummary? {
        let now = Units.nowMs(), since = now - Int64(hours) * 3_600_000
        let year = utc.component(.year, from: Date())
        let (hdobDir, dropDir) = dirs(String(stormId.prefix(2)))
        var hdobs: [Hdob] = []
        let listing = (try? await Http.text(S, "\(root)/\(year)/\(hdobDir)/")) ?? ""
        for f in listFiles(listing, prefix: hdobDir).filter({ $0.ms >= since }).sorted(by: { $0.ms > $1.ms }).prefix(18) {
            if let body = try? await Http.text(S, "\(root)/\(year)/\(hdobDir)/\(f.name)") { hdobs += parseHdob(body, issuedMs: f.ms) }
        }
        let near = hdobs.filter { $0.timeMs >= since && Geo.distanceKm(centerLat, centerLon, $0.lat, $0.lon) <= radiusKm }
        var vdm: VortexFix?
        if let t = try? await Http.text(S, "https://tgftp.nws.noaa.gov/data/raw/ur/urnt12.knhc..txt"), let v = parseVdm(t),
           v.stormId.caseInsensitiveCompare(stormId) == .orderedSame, v.timeMs >= since { vdm = v }
        var drops: [DropsondeObs] = []
        let dropListing = (try? await Http.text(S, "\(root)/\(year)/\(dropDir)/")) ?? ""
        for f in listFiles(dropListing, prefix: dropDir).filter({ $0.ms >= since }).sorted(by: { $0.ms > $1.ms }).prefix(8) {
            guard let body = try? await Http.text(S, "\(root)/\(year)/\(dropDir)/\(f.name)"), var d = parseDrop(body) else { continue }
            if let la = d.lat, let lo = d.lon, Geo.distanceKm(centerLat, centerLon, la, lo) > radiusKm { continue }
            d.relMs = f.ms; drops.append(d)
        }
        if near.isEmpty && vdm == nil && drops.isEmpty { return nil }
        return ReconSummary(hdobCount: near.count, windowStartMs: since, latestObsMs: near.map(\.timeMs).max(),
                            maxSfmrKt: near.compactMap(\.sfmrWindKt).max(), maxFlWindKt: near.compactMap(\.peakFlWindKt).max(),
                            minExtrapSlpMb: near.compactMap(\.extrapSlpMb).min(), vortex: vdm, dropsondes: drops)
    }
}

extension String {
    /// First regex match as [whole, group1, group2, …] (empty string for non-participating groups), or nil.
    func firstMatch(_ pattern: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern), let m = re.firstMatch(in: self, range: NSRange(location: 0, length: (self as NSString).length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in m.range(at: i).location == NSNotFound ? "" : (self as NSString).substring(with: m.range(at: i)) }
    }
}
