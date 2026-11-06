import Foundation

public struct GeoResult: Sendable, Equatable, Identifiable {
    public var id: String { "\(label)|\(lat)|\(lon)" }
    public var label: String; public var subtitle: String?; public var lat: Double; public var lon: Double; public var state: String?; public var kind: String
}

func urlEncode(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
    return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
}

private actor LayerCache {
    var ids: [String: Int]?
    func get() -> [String: Int]? { ids }
    func set(_ m: [String: Int]) { ids = m }
}

/// Location search using only free U.S. Census services: Geocoder for addresses; TIGERweb for ZIP codes, cities and counties.
public enum CensusSource {
    static let S = "U.S. Census"
    static let geocoder = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress"
    static let tiger = "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/tigerWMS_Current/MapServer"
    private static let cache = LayerCache()

    /// Layer ids in TIGERweb change between releases, so resolve them by name.
    static func layer(_ name: String) async throws -> Int {
        if let m = await cache.get(), let id = m[name] { return id }
        let root = try await Http.json(S, "\(tiger)?f=json")
        var m: [String: Int] = [:]
        for l in root["layers"].array ?? [] { if let n = l["name"].string, let id = l["id"].int { m[n] = id } }
        await cache.set(m)
        guard let id = m[name] else { throw SourceError(S, "Census layer '\(name)' not found") }
        return id
    }

    public static func classify(_ q: String) -> String {
        let t = q.trimmingCharacters(in: .whitespaces)
        if t.range(of: #"^\d{5}(-\d{4})?$"#, options: .regularExpression) != nil { return "zip" }
        if t.range(of: #"^\d+\s+\S+.*"#, options: .regularExpression) != nil && t.contains(where: { $0.isLetter }) { return "address" }
        return "place"
    }

    public static func search(_ query: String) async throws -> [GeoResult] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard q.count >= 2 else { return [] }
        switch classify(q) {
        case "zip": return try await zip(String(q.prefix(5)))
        case "address": return try await address(q)
        default:
            let p = try await place(q)
            return p.isEmpty ? try await address(q) : p
        }
    }

    public static func address(_ q: String) async throws -> [GeoResult] {
        let url = "\(geocoder)?address=\(urlEncode(q))&benchmark=Public_AR_Current&format=json"
        let matches = try await Http.json(S, url)["result"]["addressMatches"].array ?? []
        return matches.prefix(5).compactMap { m in
            guard let x = m["coordinates"]["x"].double, let y = m["coordinates"]["y"].double else { return nil }
            let matched = m["matchedAddress"].string ?? q
            let head = matched.split(separator: ",", maxSplits: 1).first.map(String.init) ?? matched
            let tail = matched.split(separator: ",", maxSplits: 1).dropFirst().first.map { $0.trimmingCharacters(in: .whitespaces) }
            return GeoResult(label: head.capitalized, subtitle: tail, lat: y, lon: x, state: m["addressComponents"]["state"].string, kind: "Address")
        }
    }

    public static func zip(_ zip5: String) async throws -> [GeoResult] {
        let id = try await layer("2020 Census ZIP Code Tabulation Areas")
        let url = "\(tiger)/\(id)/query?where=\(urlEncode("ZCTA5='\(zip5)'"))&outFields=ZCTA5,INTPTLAT,INTPTLON&returnGeometry=false&f=json"
        return (try await Http.json(S, url)["features"].array ?? []).compactMap { f in
            let a = f["attributes"]
            guard let lat = a["INTPTLAT"].double, let lon = a["INTPTLON"].double else { return nil }
            return GeoResult(label: "ZIP \(zip5)", subtitle: nil, lat: lat, lon: lon, state: nil, kind: "ZIP code")
        }
    }

    /// "Houston city" → "Houston"; "Spring CDP" → "Spring".
    public static func cleanPlaceName(_ n: String) -> String {
        n.replacingOccurrences(of: #"\s+(city|town|village|borough|municipality|CDP|city and borough|urban county)$"#, with: "", options: [.regularExpression, .caseInsensitive])
    }

    public static func place(_ query: String) async throws -> [GeoResult] {
        let parts = query.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard var name = parts.first else { return [] }
        var stateAbbr: String? = parts.count > 1 && parts[1].count == 2 ? parts[1].uppercased() : nil
        if stateAbbr == nil, let r = name.range(of: #"\s+([A-Za-z]{2})$"#, options: .regularExpression) {
            let ab = name[r].trimmingCharacters(in: .whitespaces).uppercased()
            if !NwsSource.stateFips(ab).isEmpty { stateAbbr = ab; name = String(name[..<r.lowerBound]) }
        }
        let safe = name.replacingOccurrences(of: "'", with: "''").replacingOccurrences(of: "%", with: "")
        var out: [GeoResult] = []
        for layerName in ["Incorporated Places", "Census Designated Places"] {
            guard let id = try? await layer(layerName) else { continue }
            var whereClause = "BASENAME LIKE '\(safe)%'"
            if let st = stateAbbr, !NwsSource.stateFips(st).isEmpty { whereClause += " AND STATE='\(NwsSource.stateFips(st))'" }
            let url = "\(tiger)/\(id)/query?where=\(urlEncode(whereClause))&outFields=NAME,BASENAME,STATE,INTPTLAT,INTPTLON&returnGeometry=false&resultRecordCount=12&f=json"
            guard let feats = try? await Http.json(S, url)["features"].array else { continue }
            for f in feats {
                let a = f["attributes"]
                guard let n = a["NAME"].string, let lat = a["INTPTLAT"].double, let lon = a["INTPTLON"].double else { continue }
                let st = NwsSource.fipsToState(a["STATE"].text ?? "")
                out.append(GeoResult(label: cleanPlaceName(n), subtitle: st.map(NwsSource.stateName), lat: lat, lon: lon, state: st, kind: "Place"))
            }
        }
        var seen = Set<String>()
        let sorted = out.sorted { a, b in
            let ea = a.label.caseInsensitiveCompare(name) == .orderedSame, eb = b.label.caseInsensitiveCompare(name) == .orderedSame
            return ea != eb ? ea : a.label < b.label
        }
        return sorted.filter { seen.insert($0.label + ($0.state ?? "")).inserted }.prefix(10).map { $0 }
    }

    /// Resolve a tapped map point to a friendly name using NWS (U.S. only).
    public static func reverse(lat: Double, lon: Double) async throws -> (name: String, state: String?)? {
        guard Geo.inUsCoverage(lat, lon) else { return nil }
        let pt = try await NwsSource.point(lat: lat, lon: lon)
        return (pt.city ?? "Dropped pin", pt.state)
    }

    /// U.S. states a polyline of (lat, lon) vertices crosses or touches — the NHC landfall test for event mode.
    public static func statesCrossed(_ path: [(Double, Double)]) async throws -> [String] {
        guard path.count >= 2 else { return [] }
        let id = try await layer("States")
        let coords = path.map { "[\($0.1),\($0.0)]" }.joined(separator: ",")
        let geom = "{\"paths\":[[\(coords)]],\"spatialReference\":{\"wkid\":4326}}"
        let url = "\(tiger)/\(id)/query?geometry=\(urlEncode(geom))&geometryType=esriGeometryPolyline&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=STUSAB,STATE&returnGeometry=false&f=json"
        return (try await Http.json(S, url)["features"].array ?? []).compactMap { $0["attributes"]["STUSAB"].string }
    }

    /// Counties for a state (favorite-county picker): (GEOID, NAME).
    public static func counties(_ stateAbbr: String) async throws -> [(fips: String, name: String)] {
        let fips = NwsSource.stateFips(stateAbbr)
        guard !fips.isEmpty else { return [] }
        let id = try await layer("Counties")
        let url = "\(tiger)/\(id)/query?where=\(urlEncode("STATE='\(fips)'"))&outFields=NAME,GEOID&returnGeometry=false&orderByFields=NAME&resultRecordCount=500&f=json"
        return (try await Http.json(S, url)["features"].array ?? []).compactMap { f in
            guard let g = f["attributes"]["GEOID"].string, let n = f["attributes"]["NAME"].string else { return nil }
            return (g, n)
        }
    }
}
