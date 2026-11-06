import Foundation

public struct ActiveStorm: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var bin: String
    public var name: String
    public var classification: String
    public var intensityKt: Int?
    public var pressureMb: Int?
    public var lat: Double
    public var lon: Double
    public var moveDirDeg: Int?
    public var moveSpeedMph: Int?
    public var lastUpdateMs: Int64?
    public var advisoryNumber: String?
    public var advisoryIssuedMs: Int64?
    public var publicAdvisoryUrl: String?
    public var discussionUrl: String?
    public var graphicsUrl: String?
    public var forecastAdvisoryUrl: String?
    public var windProbUrl: String?

    public var basinCode: String { String(id.prefix(2)).lowercased() }
    public var isHurricaneStrength: Bool { (intensityKt ?? 0) >= 64 }
    public var classLabel: String { Self.classificationLabel(classification, intensityKt) }

    public static func classificationLabel(_ c: String, _ kt: Int?) -> String {
        switch c.uppercased() {
        case "TD": return "Tropical Depression"; case "TS": return "Tropical Storm"; case "HU": return "Hurricane"
        case "STD": return "Subtropical Depression"; case "STS": return "Subtropical Storm"; case "PTC": return "Potential Tropical Cyclone"
        case "PC": return "Post-Tropical Cyclone"; case "EX": return "Extratropical"; case "LO": return "Low"; case "DB": return "Disturbance"
        default: return (kt ?? 0) >= 64 ? "Hurricane" : c
        }
    }
    public static func saffir(_ kt: Int?) -> String? {
        guard let kt else { return nil }
        if kt >= 137 { return "Category 5" }; if kt >= 113 { return "Category 4" }; if kt >= 96 { return "Category 3" }
        if kt >= 83 { return "Category 2" }; if kt >= 64 { return "Category 1" }
        return nil
    }
}

/// One row of an ATCF a-deck / best-track file.
public struct AtcfFix: Equatable, Sendable {
    public var basin: String, cyclone: Int, dtg: String, tech: String, tauHours: Int
    public var lat: Double, lon: Double
    public var vmaxKt: Int?, mslpMb: Int?, type: String?
}

/// A forecast/model track at a single initialisation time.
public struct TrackLine: Equatable, Sendable { public var tech: String; public var initDtg: String; public var initMs: Int64; public var points: [AtcfFix] }

public enum Atcf {
    /// "342N" → 34.2 ; "713W" → -71.3
    public static func coord(_ s: String) -> Double? {
        let t = s.trimmingCharacters(in: .whitespaces)
        guard t.count >= 2, let h = t.last?.uppercased(), let v = Double(t.dropLast()) else { return nil }
        let d = v / 10
        switch h { case "N", "E": return d; case "S", "W": return -d; default: return nil }
    }

    public static func parse(_ text: String) -> [AtcfFix] { text.split(whereSeparator: \.isNewline).compactMap { parseLine(String($0)) } }

    public static func parseLine(_ line: String) -> AtcfFix? {
        let f = line.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        guard f.count >= 10, let lat = coord(f[6]), let lon = coord(f[7]), let cy = Int(f[1]), let tau = Int(f[5]) else { return nil }
        let vmax = Int(f[8]).flatMap { $0 > 0 ? $0 : nil }
        let mslp = Int(f[9]).flatMap { $0 > 0 ? $0 : nil }
        return AtcfFix(basin: f[0], cyclone: cy, dtg: f[2], tech: f[4], tauHours: tau, lat: lat, lon: lon, vmaxKt: vmax, mslpMb: mslp,
                       type: f.count > 10 && !f[10].isEmpty ? f[10] : nil)
    }

    public static func dtgToMs(_ dtg: String) -> Int64 {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "UTC")!
        guard dtg.count == 10, let y = Int(dtg.prefix(4)), let mo = Int(dtg.dropFirst(4).prefix(2)), let d = Int(dtg.dropFirst(6).prefix(2)), let h = Int(dtg.suffix(2)),
              let date = c.date(from: DateComponents(year: y, month: mo, day: d, hour: h)) else { return 0 }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// Latest initialised track for `tech` (one point per tau; wind-radii duplicate rows collapsed).
    public static func latestTrack(_ fixes: [AtcfFix], tech: String) -> TrackLine? {
        let mine = fixes.filter { $0.tech.caseInsensitiveCompare(tech) == .orderedSame }
        guard let dtg = mine.map(\.dtg).max() else { return nil }
        var seen = Set<Int>()
        let pts = mine.filter { $0.dtg == dtg && seen.insert($0.tauHours).inserted }.sorted { $0.tauHours < $1.tauHours }
        guard !pts.isEmpty else { return nil }
        return TrackLine(tech: tech, initDtg: dtg, initMs: dtgToMs(dtg), points: pts)
    }

    /// Best-track (BEST) points in time order, de-duplicated by time.
    public static func bestTrack(_ fixes: [AtcfFix]) -> [AtcfFix] {
        var seen = Set<String>()
        return fixes.filter { $0.tech.caseInsensitiveCompare("BEST") == .orderedSame && seen.insert($0.dtg).inserted }.sorted { $0.dtg < $1.dtg }
    }
}

/// One map feature from the NHC ArcGIS tropical service (coordinates are [lon, lat]).
public struct GeoFeature: Sendable { public var kind: String; public var rings: [[[Double]]]; public var line: [[Double]]; public var attrs: [String: String] }

public struct ForecastGraphics: Sendable {
    public var cone: [GeoFeature] = [], track: [GeoFeature] = [], points: [GeoFeature] = []
    public var warnings: [GeoFeature] = [], past: [GeoFeature] = [], windRadii: [GeoFeature] = []
    public init() {}
}

private actor NhcLayerCache {
    var names: [String: Int]?
    func get() -> [String: Int]? { names }
    func set(_ m: [String: Int]) { names = m }
}

public enum NhcSource {
    static let S = "NHC"
    static let current = "https://www.nhc.noaa.gov/CurrentStorms.json"
    static let atcfAid = "https://ftp.nhc.noaa.gov/atcf/aid_public"
    static let atcfBtk = "https://ftp.nhc.noaa.gov/atcf/btk"
    static let mapsvc = "https://mapservices.weather.noaa.gov/tropical/rest/services/tropical/NHC_tropical_weather/MapServer"
    private static let layers = NhcLayerCache()

    public static func activeStorms() async throws -> [ActiveStorm] { parseActive(try await Http.json(S, current)) }

    public static func parseActive(_ root: JSON) -> [ActiveStorm] {
        (root["activeStorms"].array ?? []).compactMap { o in
            guard let id = o["id"].string, let lat = o["latitudeNumeric"].double, let lon = o["longitudeNumeric"].double else { return nil }
            let adv = o["publicAdvisory"]
            let name = o["name"].string ?? ""
            return ActiveStorm(
                id: id, bin: o["binNumber"].string ?? "", name: name.isEmpty ? "Unnamed" : name, classification: o["classification"].string ?? "",
                intensityKt: o["intensity"].double.map(Int.init), pressureMb: o["pressure"].double.map(Int.init), lat: lat, lon: lon,
                moveDirDeg: o["movementDir"].double.map(Int.init), moveSpeedMph: o["movementSpeed"].double.map(Int.init),
                lastUpdateMs: Units.parseIso(o["lastUpdate"].string), advisoryNumber: adv["advNum"].string, advisoryIssuedMs: Units.parseIso(adv["issuance"].string),
                publicAdvisoryUrl: adv["url"].string, discussionUrl: o["forecastDiscussion"]["url"].string, graphicsUrl: o["forecastGraphics"]["url"].string,
                forecastAdvisoryUrl: o["forecastAdvisory"]["url"].string, windProbUrl: o["windSpeedProbabilities"]["url"].string)
        }
    }

    // ---- ATCF-based tracks (official forecast, GFS guidance, best track) ----
    public static func aDeck(_ stormId: String) async throws -> [AtcfFix] {
        let data = try await Http.data(S, "\(atcfAid)/a\(stormId.lowercased()).dat.gz")
        do { return Atcf.parse(String(decoding: try Gzip.decompress(data), as: UTF8.self)) }
        catch { throw SourceError(S, "Unreadable ATCF file") }
    }
    public static func bestTrack(_ stormId: String) async throws -> [AtcfFix] {
        Atcf.bestTrack(Atcf.parse(try await Http.text(S, "\(atcfBtk)/b\(stormId.lowercased()).dat")))
    }

    // ---- ArcGIS graphics: cone, official track, watches/warnings ----
    private static func layerId(_ name: String) async throws -> Int? {
        if let m = await layers.get() { return m[name] }
        let root = try await Http.json(S, "\(mapsvc)?f=json")
        var m: [String: Int] = [:]
        for l in root["layers"].array ?? [] { if let n = l["name"].string, let id = l["id"].int { m[n] = id } }
        await layers.set(m)
        return m[name]
    }

    private static func query(_ layerName: String) async throws -> [GeoFeature] {
        guard let id = try await layerId(layerName) else { return [] }
        let url = "\(mapsvc)/\(id)/query?where=\(urlEncode("1=1"))&outFields=*&returnGeometry=true&outSR=4326&f=geojson"
        return parseGeoJson(layerName, try await Http.json(S, url))
    }

    public static func parseGeoJson(_ kind: String, _ root: JSON) -> [GeoFeature] {
        (root["features"].array ?? []).compactMap { o in
            let g = o["geometry"]
            guard !g.isNull else { return nil }
            var attrs: [String: String] = [:]
            for (k, v) in o["properties"].object ?? [:] { if let t = v.text { attrs[k] = t } }
            func pt(_ e: JSON) -> [Double]? { let a = (e.array ?? []).compactMap { $0.double }; return a.count >= 2 ? a : nil }
            func ring(_ e: JSON) -> [[Double]] { (e.array ?? []).compactMap(pt) }
            var rings: [[[Double]]] = []; var line: [[Double]] = []
            let c = g["coordinates"]
            switch g["type"].string {
            case "Polygon": for r in c.array ?? [] { rings.append(ring(r)) }
            case "MultiPolygon": for poly in c.array ?? [] { for r in poly.array ?? [] { rings.append(ring(r)) } }
            case "LineString": line = ring(c)
            case "MultiLineString": for r in c.array ?? [] { rings.append(ring(r)) }
            case "Point": if let p = pt(c) { line = [p] }
            case "MultiPoint": line = ring(c)
            default: break
            }
            return GeoFeature(kind: kind, rings: rings, line: line, attrs: attrs)
        }
    }

    /// Official NHC graphics for an active storm (cone, track, points, warnings).
    public static func graphics(_ storm: ActiveStorm) async -> ForecastGraphics {
        var g = ForecastGraphics()
        guard !storm.bin.isEmpty else { return g }
        func q(_ n: String) async -> [GeoFeature] { (try? await query("\(storm.bin) \(n)")) ?? [] }
        g.cone = await q("Forecast Cone"); g.track = await q("Forecast Track"); g.points = await q("Forecast Points")
        g.warnings = await q("Watch-Warning"); g.past = await q("Past Track"); g.windRadii = await q("Forecast Wind Radii")
        return g
    }

    /// Public advisory text as plain text (NHC .shtml pages wrap the product in <pre>).
    public static func productText(_ url: String) async throws -> String {
        let html = try await Http.text(S, url, accept: "text/html")
        var body = html
        if let r = html.range(of: #"<pre[^>]*>(.*?)</pre>"#, options: [.regularExpression]) {
            body = String(html[r]).replacingOccurrences(of: #"^<pre[^>]*>"#, with: "", options: .regularExpression).replacingOccurrences(of: "</pre>", with: "")
        }
        return body.replacingOccurrences(of: #"<[^>]+>"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: "&amp;", with: "&").replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Pull the headed blocks ("WATCHES AND WARNINGS", "SUMMARY OF…", "KEY MESSAGES"…) out of a public advisory.
    public static func advisoryHighlights(_ text: String) -> [(String, String)] {
        let sections = ["WATCHES AND WARNINGS", "SUMMARY OF", "KEY MESSAGES", "HAZARDS AFFECTING LAND", "STORM SURGE", "WIND", "RAINFALL", "NEXT ADVISORY"]
        let blocks = text.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        var out: [(String, String)] = []
        var i = 0
        while i < blocks.count {
            let head = (blocks[i].split(separator: "\n").first.map(String.init) ?? "").trimmingCharacters(in: .whitespaces)
            if head.hasSuffix("...") || head.hasPrefix("...") { i += 1; continue }
            let isHead = sections.contains { head.uppercased().hasPrefix($0) } && head.count < 70
            if isHead {
                var body = ""
                var j = i + 1
                while j < blocks.count && body.count < 900 {
                    let nh = (blocks[j].split(separator: "\n").first.map(String.init) ?? "").trimmingCharacters(in: .whitespaces)
                    if nh == nh.uppercased() && (4...60).contains(nh.count) && !nh.contains(".") && nh.contains(where: \.isLetter) { break }
                    body += blocks[j].replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression) + " "
                    j += 1
                }
                var title = head
                while let l = title.last, ".- ".contains(l) { title.removeLast() }
                let trimmed = body.trimmingCharacters(in: .whitespaces)
                if !trimmed.isEmpty { out.append((title, trimmed)) }
                i = j
            } else { i += 1 }
        }
        return out
    }
}
