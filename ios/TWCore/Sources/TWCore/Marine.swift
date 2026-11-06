import Foundation

public struct BuoyObs: Sendable, Equatable, Identifiable {
    public var id: String
    public var lat: Double, lon: Double, distanceMi: Double, bearingDeg: Double, timeMs: Int64
    public var windMph: Double?, gustMph: Double?, windDirDeg: Double?, pressureInHg: Double?
    public var waveHeightFt: Double?, wavePeriodSec: Double?, waterTempF: Double?, airTempF: Double?
}
public struct WaterLevelObs: Sendable, Equatable, Identifiable {
    public var id: String, name: String
    public var lat: Double, lon: Double, distanceMi: Double, timeMs: Int64
    public var levelFtMllw: Double, predictedFtMllw: Double?, departureFt: Double?, preliminary: Bool
}

/// NOAA NDBC latest observations (all stations in one text file).
public enum NdbcSource {
    static let S = "NOAA NDBC"
    static let latest = "https://www.ndbc.noaa.gov/data/latest_obs/latest_obs.txt"

    static func num(_ s: String?) -> Double? { guard let s, s != "MM" else { return nil }; return Double(s) }

    public static func parse(_ text: String) -> [BuoyObs] {
        var out: [BuoyObs] = []
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "UTC")!
        for ln in text.split(whereSeparator: \.isNewline) {
            if ln.hasPrefix("#") { continue }
            let f = ln.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
            guard f.count >= 21, let lat = num(f[1]), let lon = num(f[2]),
                  let y = Int(f[3]), let mo = Int(f[4]), let d = Int(f[5]), let h = Int(f[6]), let mi = Int(f[7]),
                  let date = cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi)) else { continue }
            out.append(BuoyObs(id: f[0], lat: lat, lon: lon, distanceMi: 0, bearingDeg: 0, timeMs: Int64(date.timeIntervalSince1970 * 1000),
                               windMph: num(f[9]).map(Units.mpsToMph), gustMph: num(f[10]).map(Units.mpsToMph), windDirDeg: num(f[8]),
                               pressureInHg: num(f[15]).map(Units.hpaToInHg), waveHeightFt: num(f[11]).map(Units.mToFeet), wavePeriodSec: num(f[12]),
                               waterTempF: num(f[18]).map(Units.cToF), airTempF: num(f[17]).map(Units.cToF)))
        }
        return out
    }

    public static func all(maxAgeMin: Int = 180) async throws -> [BuoyObs] {
        let now = Units.nowMs()
        return parse(try await Http.text(S, latest)).filter { now - $0.timeMs <= Int64(maxAgeMin) * 60_000 && ($0.windMph != nil || $0.waveHeightFt != nil || $0.pressureInHg != nil) }
    }

    /// Nearby buoys with a recent report and at least one meaningful reading.
    public static func nearby(lat: Double, lon: Double, radiusMi: Double = 150, maxAgeMin: Int = 180, limit: Int = 4) async throws -> [BuoyObs] {
        try await all(maxAgeMin: maxAgeMin).map { b -> BuoyObs in
            var b = b; b.distanceMi = Geo.distanceMiles(lat, lon, b.lat, b.lon); b.bearingDeg = Geo.bearing(lat, lon, b.lat, b.lon); return b
        }.filter { $0.distanceMi <= radiusMi }.sorted { $0.distanceMi < $1.distanceMi }.prefix(limit).map { $0 }
    }
}

private actor StationCache {
    var list: [(id: String, name: String, lat: Double, lon: Double)]?
    func get() -> [(id: String, name: String, lat: Double, lon: Double)]? { list }
    func set(_ l: [(id: String, name: String, lat: Double, lon: Double)]) { list = l }
}

/// NOAA CO-OPS water levels.
public enum CoopsSource {
    static let S = "NOAA CO-OPS"
    static let md = "https://api.tidesandcurrents.noaa.gov/mdapi/prod/webapi/stations.json?type=waterlevels"
    static let data = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter"
    private static let cache = StationCache()

    static func stations() async throws -> [(id: String, name: String, lat: Double, lon: Double)] {
        if let l = await cache.get() { return l }
        let root = try await Http.json(S, md)
        let l = (root["stations"].array ?? []).compactMap { o -> (id: String, name: String, lat: Double, lon: Double)? in
            guard let id = o["id"].string, let la = o["lat"].double, let lo = o["lng"].double else { return nil }
            return (id, o["name"].string ?? "", la, lo)
        }
        await cache.set(l); return l
    }

    static func gmt(_ d: Date) -> String {
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyyMMdd HH:mm"
        return urlEncode(f.string(from: d))
    }

    public static func nearby(lat: Double, lon: Double, radiusMi: Double = 60, limit: Int = 3, maxAgeMin: Int = 90) async -> [WaterLevelObs] {
        guard let all = try? await stations() else { return [] }
        let now = Units.nowMs()
        let near = all.map { ($0, Geo.distanceMiles(lat, lon, $0.lat, $0.lon)) }.filter { $0.1 <= radiusMi }.sorted { $0.1 < $1.1 }.prefix(limit + 3)
        var out: [WaterLevelObs] = []
        for (s, d) in near {
            guard let o = try? await latestObs(s, d), now - o.timeMs <= Int64(maxAgeMin) * 60_000 else { continue }
            out.append(o); if out.count >= limit { break }
        }
        return out
    }

    static func latestObs(_ s: (id: String, name: String, lat: Double, lon: Double), _ distMi: Double) async throws -> WaterLevelObs? {
        let end = Date(), begin = end.addingTimeInterval(-3 * 3600)
        let common = "station=\(s.id)&datum=MLLW&units=english&time_zone=gmt&format=json&application=TouchWeatherTracker"
        let wl = try await Http.json(S, "\(data)?product=water_level&begin_date=\(gmt(begin))&end_date=\(gmt(end))&\(common)")
        guard let last = (wl["data"].array ?? []).last(where: { $0["v"].double != nil }), let t = last["t"].string, let level = last["v"].double else { return nil }
        let f = DateFormatter(); f.locale = Locale(identifier: "en_US_POSIX"); f.timeZone = TimeZone(identifier: "UTC"); f.dateFormat = "yyyy-MM-dd HH:mm"
        guard let when = f.date(from: t) else { return nil }
        var pred: Double?
        if let p = try? await Http.json(S, "\(data)?product=predictions&begin_date=\(gmt(begin))&end_date=\(gmt(end.addingTimeInterval(3600)))&interval=6&\(common)") {
            pred = (p["predictions"].array ?? []).first { $0["t"].string == t }?["v"].double
        }
        let flags = last["f"].string ?? ""
        return WaterLevelObs(id: s.id, name: s.name, lat: s.lat, lon: s.lon, distanceMi: distMi, timeMs: Int64(when.timeIntervalSince1970 * 1000),
                             levelFtMllw: level, predictedFtMllw: pred, departureFt: pred.map { level - $0 }, preliminary: (last["q"].string ?? "p") != "v" || flags.isEmpty)
    }
}

/// NOAA nowCOAST WMS (GOES East/West imagery).
public enum NowCoast {
    public static let wms = "https://nowcoast.noaa.gov/geoserver/satellite/wms"
    public enum Band: String, CaseIterable, Sendable {
        case visible = "goes_visible_imagery", infrared = "goes_longwave_imagery", waterVapor = "goes_water_vapor_imagery"
        public var label: String { switch self { case .visible: return "Visible"; case .infrared: return "Infrared"; case .waterVapor: return "Water vapor" } }
    }
    private actor TimeCache { var at: Int64 = 0; var times: [String: Int64] = [:]
        func get() -> [String: Int64]? { Units.nowMs() - at < 300_000 && !times.isEmpty ? times : nil }
        func set(_ t: [String: Int64]) { times = t; at = Units.nowMs() } }
    private static let cache = TimeCache()

    /// Latest available image time per layer, from the WMS capabilities `time` dimension default.
    public static func latestTimes() async throws -> [String: Int64] {
        if let t = await cache.get() { return t }
        let xml = try await Http.text("nowCOAST", "\(wms)?service=WMS&request=GetCapabilities")
        var out: [String: Int64] = [:]
        for b in Band.allCases {
            guard let r = xml.range(of: "<Name>\(b.rawValue)</Name>") else { continue }
            let seg = String(xml[r.lowerBound...].prefix(6000))
            if let m = seg.firstMatch(#"<Dimension name="time"[^>]*default="([^"]+)""#), let ms = Units.parseIso(m[1]) { out[b.rawValue] = ms }
        }
        await cache.set(out); return out
    }
}
