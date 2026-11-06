import Foundation

/// Resolved NWS grid/zone metadata for a point. Source: api.weather.gov /points (U.S. Government work, no key).
public struct NwsPoint: Sendable {
    public var lat: Double, lon: Double
    public var gridId: String, gridX: Int, gridY: Int
    public var forecastUrl: String, hourlyUrl: String, gridDataUrl: String, stationsUrl: String
    public var countyZoneId: String?, forecastZoneId: String?
    public var timeZone: String?, radarStation: String?
    public var city: String?, state: String?
    public var sunriseMs: Int64?, sunsetMs: Int64?
    public var countyFips: String?
}

public struct Outlook: Sendable { public var office: String; public var issuedMs: Int64?; public var text: String }

public enum NwsSource {
    static let S = "NWS"
    static let base = "https://api.weather.gov"

    public static func point(lat: Double, lon: Double) async throws -> NwsPoint {
        let root = try await Http.json(S, "\(base)/points/\(String(format: "%.4f,%.4f", lat, lon))")
        let p = root["properties"]
        guard let grid = p["gridId"].string else { throw SourceError(S, "No NWS grid for this location") }
        let rel = p["relativeLocation"]["properties"]
        let astro = p["astronomicalData"]
        let countyCode = p["county"].string?.split(separator: "/").last.map(String.init)
        var fips: String?
        if let c = countyCode, c.count == 6, c[c.index(c.startIndex, offsetBy: 2)] == "C" {
            fips = stateFips(String(c.prefix(2))) + String(c.suffix(3))
        }
        return NwsPoint(
            lat: lat, lon: lon, gridId: grid, gridX: p["gridX"].int ?? 0, gridY: p["gridY"].int ?? 0,
            forecastUrl: p["forecast"].string ?? "", hourlyUrl: p["forecastHourly"].string ?? "",
            gridDataUrl: p["forecastGridData"].string ?? "", stationsUrl: p["observationStations"].string ?? "",
            countyZoneId: countyCode, forecastZoneId: p["forecastZone"].string?.split(separator: "/").last.map(String.init),
            timeZone: p["timeZone"].string, radarStation: p["radarStation"].string,
            city: rel["city"].string, state: rel["state"].string,
            sunriseMs: Units.parseIso(astro["sunrise"].string), sunsetMs: Units.parseIso(astro["sunset"].string), countyFips: fips)
    }

    /// Full weather bundle for a place; falls back to `existing` pieces when a sub-request fails (never invents data).
    public static func weather(for place: Place, existing: WeatherData?) async throws -> (WeatherData, NwsPoint) {
        let pt = try await point(lat: place.lat, lon: place.lon)
        var daily: [DailyPoint]; var updated: Int64?
        do { (daily, updated) = try await Self.daily(pt.forecastUrl) }
        catch { guard let e = existing, !e.daily.isEmpty else { throw error }; daily = e.daily; updated = e.forecastUpdatedMs }
        var hourly: [HourlyPoint]
        do { hourly = try await Self.hourly(pt.hourlyUrl) }
        catch { guard let e = existing, !e.hourly.isEmpty else { throw error }; hourly = e.hourly }
        if let extra = try? await gridExtras(pt.gridDataUrl) { hourly = hourly.map { extra.apply($0) } }
        let cond = (try? await currentConditions(pt)) ?? nil
        var data = WeatherData(placeId: place.id, fetchedAtMs: Units.nowMs())
        data.gridId = pt.gridId; data.forecastOffice = pt.gridId; data.radarStation = pt.radarStation; data.forecastUpdatedMs = updated
        data.conditions = cond ?? existing?.conditions; data.hourly = hourly; data.daily = daily
        data.sun = SunTimes(sunriseMs: pt.sunriseMs, sunsetMs: pt.sunsetMs)
        return (data, pt)
    }

    static func periods(_ root: JSON) -> ([JSON], Int64?) {
        let p = root["properties"]
        return (p["periods"].array ?? [], Units.parseIso(p["updateTime"].string ?? p["generatedAt"].string))
    }

    static func tempF(_ o: JSON) -> Double? {
        guard let v = o["temperature"].double ?? o["temperature"]["value"].double else { return nil }
        return o["temperatureUnit"].string == "C" ? Units.cToF(v) : v
    }

    /// Highest number in strings like "5 to 10 mph".
    static func windMph(_ s: String?) -> Double? {
        guard let s else { return nil }
        let nums = s.split(whereSeparator: { !$0.isNumber }).compactMap { Double($0) }
        return nums.max()
    }

    public static func hourly(_ url: String) async throws -> [HourlyPoint] {
        let (periods, _) = periods(try await Http.json(S, url))
        var out: [HourlyPoint] = []
        for o in periods {
            guard let t = Units.parseIso(o["startTime"].string) else { continue }
            var h = HourlyPoint(timeMs: t, tempF: tempF(o))
            h.precipChance = o["probabilityOfPrecipitation"]["value"].double
            h.windMph = windMph(o["windSpeed"].string); h.windDir = o["windDirection"].string
            h.humidityPct = o["relativeHumidity"]["value"].double
            h.summary = o["shortForecast"].string; h.isDay = o["isDaytime"].bool ?? true
            out.append(h)
        }
        return Array(out.prefix(120))
    }

    public static func daily(_ url: String) async throws -> ([DailyPoint], Int64?) {
        let (periods, updated) = periods(try await Http.json(S, url))
        var out: [DailyPoint] = []
        for o in periods {
            guard let start = Units.parseIso(o["startTime"].string) else { continue }
            let isDay = o["isDaytime"].bool ?? true
            let t = tempF(o)
            let wind = [o["windDirection"].string, o["windSpeed"].string].compactMap { $0 }.joined(separator: " ")
            out.append(DailyPoint(name: o["name"].string ?? "", startMs: start, highF: isDay ? t : nil, lowF: isDay ? nil : t,
                                  summary: o["shortForecast"].string ?? "", detail: o["detailedForecast"].string ?? "",
                                  precipChance: o["probabilityOfPrecipitation"]["value"].double, windText: wind.isEmpty ? nil : wind))
        }
        return (out, updated)
    }

    // ---- gridpoint extras: feels-like, gusts, precip amount (series) ----
    public struct GridExtras: Sendable {
        let feels: [(Int64, Int64, Double)], gust: [(Int64, Int64, Double)], qpf: [(Int64, Int64, Double)]
        func at(_ list: [(Int64, Int64, Double)], _ t: Int64) -> Double? { list.first { t >= $0.0 && t < $0.1 }?.2 }
        func apply(_ h: HourlyPoint) -> HourlyPoint {
            var h = h
            if let f = at(feels, h.timeMs) { h.feelsLikeF = Units.cToF(f) }
            if let g = at(gust, h.timeMs) { h.gustMph = Units.kmhToMph(g) }
            if let q = qpf.first(where: { h.timeMs >= $0.0 && h.timeMs < $0.1 }) {
                let hours = max(1.0, Double(q.1 - q.0) / 3_600_000)
                h.precipIn = Units.mmToIn(q.2) / hours
            }
            return h
        }
    }

    /// ISO-8601 duration like PT1H, PT6H, P1DT6H → milliseconds.
    static func durationMs(_ s: String?) -> Int64 {
        guard var s, s.hasPrefix("P") else { return 3_600_000 }
        s.removeFirst()
        var total = 0.0, num = "", inTime = false
        for ch in s {
            if ch == "T" { inTime = true; continue }
            if ch.isNumber || ch == "." { num.append(ch); continue }
            let v = Double(num) ?? 0; num = ""
            switch (ch, inTime) {
            case ("D", _): total += v * 86400
            case ("H", true): total += v * 3600
            case ("M", true): total += v * 60
            case ("S", true): total += v
            default: break
            }
        }
        return total > 0 ? Int64(total * 1000) : 3_600_000
    }

    static func series(_ layer: JSON) -> [(Int64, Int64, Double)] {
        (layer["values"].array ?? []).compactMap { v in
            guard let value = v["value"].double, let vt = v["validTime"].string else { return nil }
            let parts = vt.split(separator: "/").map(String.init)
            guard let start = Units.parseIso(parts.first) else { return nil }
            return (start, start + durationMs(parts.count > 1 ? parts[1] : nil), value)
        }
    }

    public static func gridExtras(_ url: String) async throws -> GridExtras {
        let p = try await Http.json(S, url)["properties"]
        return GridExtras(feels: series(p["apparentTemperature"]), gust: series(p["windGust"]), qpf: series(p["quantitativePrecipitation"]))
    }

    // ---- current conditions from the nearest reporting station ----
    public static func currentConditions(_ pt: NwsPoint) async throws -> Conditions? {
        let stations = try await Http.json(S, pt.stationsUrl)["features"].array ?? []
        for f in stations.prefix(5) {
            let props = f["properties"]
            guard let id = props["stationIdentifier"].string else { continue }
            guard let obs = try? await Http.json(S, "\(base)/stations/\(id)/observations/latest?require_qc=false") else { continue }
            let p = obs["properties"]
            guard let t = p["temperature"]["value"].double else { continue }       // not reporting temperature: try next
            let observed = Units.parseIso(p["timestamp"].string)
            if let o = observed, Units.nowMs() - o > 3 * 3_600_000 { continue }    // stale: try next
            var c = Conditions()
            c.tempF = Units.cToF(t)
            c.feelsLikeF = (p["heatIndex"]["value"].double ?? p["windChill"]["value"].double).map(Units.cToF)
            c.summary = p["textDescription"].clean
            c.windMph = p["windSpeed"]["value"].double.map(Units.kmhToMph)
            c.gustMph = p["windGust"]["value"].double.map(Units.kmhToMph)
            c.windDirDeg = p["windDirection"]["value"].double
            c.humidityPct = p["relativeHumidity"]["value"].double
            c.dewpointF = p["dewpoint"]["value"].double.map(Units.cToF)
            c.pressureInHg = p["barometricPressure"]["value"].double.map(Units.paToInHg)
            c.visibilityMi = p["visibility"]["value"].double.map(Units.mToMiles)
            c.precipLastHourIn = p["precipitationLastHour"]["value"].double.map(Units.mmToIn)
            c.stationId = id; c.stationName = props["name"].string; c.observedAtMs = observed
            return c
        }
        return nil
    }

    // ---- Hazardous Weather Outlook ----
    public static func hazardousOutlook(office: String) async throws -> Outlook? {
        let list = try await Http.json(S, "\(base)/products/types/HWO/locations/\(office)")["@graph"].array ?? []
        guard let id = list.first?["id"].string else { return nil }
        let p = try await Http.json(S, "\(base)/products/\(id)")
        guard let text = p["productText"].string else { return nil }
        return Outlook(office: office, issuedMs: Units.parseIso(p["issuanceTime"].string), text: text)
    }

    /// First ".DAY ONE..." section (or the head of the product) as plain paragraphs.
    public static func outlookSummary(_ text: String, maxChars: Int = 900) -> String {
        var body = text
        if let r = text.range(of: #"\.DAY ONE[^\n]*\n"#, options: .regularExpression) {
            var rest = String(text[r.upperBound...])
            if let end = rest.range(of: #"\n\.[A-Z][A-Z ]+\.\.\.|\n\$\$"#, options: .regularExpression) { rest = String(rest[..<end.lowerBound]) }
            body = rest
        } else if let r = text.range(of: "\n\n") { body = String(text[r.upperBound...]) }
        body = body.replacingOccurrences(of: #"\n(?!\n)"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
        if body.count > maxChars {
            let cut = String(body.prefix(maxChars))
            return (cut.range(of: " ", options: .backwards).map { String(cut[..<$0.lowerBound]) } ?? cut) + "…"
        }
        return body
    }

    // MARK: Alerts

    public static func alertsForPoint(lat: Double, lon: Double) async throws -> [Alert] {
        parseAlerts(try await Http.json(S, "\(base)/alerts/active?point=\(String(format: "%.4f,%.4f", lat, lon))"))
    }
    public static func alertsForState(_ state: String) async throws -> [Alert] {
        parseAlerts(try await Http.json(S, "\(base)/alerts/active?area=\(state)"))
    }
    public static func alertsAll() async throws -> [Alert] { parseAlerts(try await Http.json(S, "\(base)/alerts/active?status=actual")) }

    public static func parseAlerts(_ root: JSON) -> [Alert] { (root["features"].array ?? []).compactMap(parseAlert) }

    public static func parseAlert(_ f: JSON) -> Alert? {
        let p = f["properties"]
        guard let id = p["id"].string ?? f["id"].string, let event = p["event"].string else { return nil }
        if let st = p["status"].string, st.caseInsensitiveCompare("Actual") != .orderedSame { return nil }   // exclude Test/Exercise
        let severity = p["severity"].string ?? "Unknown"
        let geocode = p["geocode"]
        let same = (geocode["SAME"].array ?? []).compactMap { $0.string }
        let ugc = (geocode["UGC"].array ?? []).compactMap { $0.string }
        var states: [String] = []
        for s in ugc.map({ String($0.prefix(2)) }) + same.compactMap({ fipsToState(String($0.dropFirst().prefix(2))) }) where !states.contains(s) { states.append(s) }
        var polygons: [[[Double]]] = []
        let geom = f["geometry"]
        func ring(_ r: JSON) -> [[Double]] { (r.array ?? []).compactMap { pt in let a = (pt.array ?? []).compactMap { $0.double }; return a.count >= 2 ? a : nil } }
        if geom["type"].string == "Polygon", let first = geom["coordinates"].array?.first { polygons.append(ring(first)) }
        if geom["type"].string == "MultiPolygon" { for poly in geom["coordinates"].array ?? [] { if let first = poly.array?.first { polygons.append(ring(first)) } } }
        let threat = p["parameters"]["tornadoDamageThreat"].array?.first?.string
        return Alert(
            id: id, event: event, level: levelOf(event), severity: severity, urgency: p["urgency"].string ?? "Unknown",
            certainty: p["certainty"].string ?? "Unknown", headline: p["headline"].string, description: p["description"].string ?? "",
            instruction: p["instruction"].string, areaDesc: p["areaDesc"].string ?? "", states: states, sameCodes: same,
            sentMs: Units.parseIso(p["sent"].string), effectiveMs: Units.parseIso(p["effective"].string), onsetMs: Units.parseIso(p["onset"].string),
            expiresMs: Units.parseIso(p["expires"].string), endsMs: Units.parseIso(p["ends"].string), senderName: p["senderName"].string ?? "",
            messageType: p["messageType"].string ?? "Alert", response: p["response"].string,
            lifeSafety: isLifeSafety(event: event, severity: severity, tornadoThreat: threat), polygons: polygons)
    }

    public static func levelOf(_ event: String) -> AlertLevel {
        let e = event.lowercased()
        if e.hasSuffix("warning") || e.contains("emergency") { return .warning }
        if e.hasSuffix("watch") { return .watch }
        if e.hasSuffix("advisory") { return .advisory }
        return .statement
    }

    static let lifeSafetyEvents: Set<String> = [
        "Tornado Warning", "Flash Flood Warning", "Hurricane Warning", "Storm Surge Warning", "Extreme Wind Warning",
        "Tsunami Warning", "Tornado Emergency", "Flash Flood Emergency", "Typhoon Warning", "Dust Storm Warning",
        "Severe Thunderstorm Warning", "Blizzard Warning", "Ice Storm Warning", "Civil Emergency Message", "Evacuation - Immediate",
        "Shelter In Place Warning", "Earthquake Warning", "Volcano Warning", "Avalanche Warning",
    ]

    /// "Urgent life-safety" alerts: based on the event name and official severity, never invented.
    public static func isLifeSafety(event: String, severity: String, tornadoThreat: String?) -> Bool {
        severity.caseInsensitiveCompare("Extreme") == .orderedSame || lifeSafetyEvents.contains(event) || tornadoThreat?.uppercased() == "CATASTROPHIC"
    }

    // MARK: States

    static let stateFipsMap: [String: String] = [
        "AL": "01","AK": "02","AZ": "04","AR": "05","CA": "06","CO": "08","CT": "09","DE": "10","DC": "11","FL": "12",
        "GA": "13","HI": "15","ID": "16","IL": "17","IN": "18","IA": "19","KS": "20","KY": "21","LA": "22","ME": "23",
        "MD": "24","MA": "25","MI": "26","MN": "27","MS": "28","MO": "29","MT": "30","NE": "31","NV": "32","NH": "33",
        "NJ": "34","NM": "35","NY": "36","NC": "37","ND": "38","OH": "39","OK": "40","OR": "41","PA": "42","RI": "44",
        "SC": "45","SD": "46","TN": "47","TX": "48","UT": "49","VT": "50","VA": "51","WA": "53","WV": "54","WI": "55",
        "WY": "56","AS": "60","GU": "66","MP": "69","PR": "72","VI": "78",
    ]
    static let fipsStateMap: [String: String] = Dictionary(uniqueKeysWithValues: stateFipsMap.map { ($1, $0) })
    public static func stateFips(_ abbr: String) -> String { stateFipsMap[abbr] ?? "" }
    public static func fipsToState(_ fips: String) -> String? { fipsStateMap[fips] }
    public static func stateName(_ abbr: String) -> String { stateNames[abbr] ?? abbr }
    public static var allStates: [(abbr: String, name: String)] { stateNames.map { ($0.key, $0.value) }.sorted { $0.1 < $1.1 } }
    static let stateNames: [String: String] = [
        "AL": "Alabama","AK": "Alaska","AZ": "Arizona","AR": "Arkansas","CA": "California","CO": "Colorado","CT": "Connecticut",
        "DE": "Delaware","DC": "District of Columbia","FL": "Florida","GA": "Georgia","HI": "Hawaii","ID": "Idaho","IL": "Illinois",
        "IN": "Indiana","IA": "Iowa","KS": "Kansas","KY": "Kentucky","LA": "Louisiana","ME": "Maine","MD": "Maryland",
        "MA": "Massachusetts","MI": "Michigan","MN": "Minnesota","MS": "Mississippi","MO": "Missouri","MT": "Montana","NE": "Nebraska",
        "NV": "Nevada","NH": "New Hampshire","NJ": "New Jersey","NM": "New Mexico","NY": "New York","NC": "North Carolina",
        "ND": "North Dakota","OH": "Ohio","OK": "Oklahoma","OR": "Oregon","PA": "Pennsylvania","RI": "Rhode Island","SC": "South Carolina",
        "SD": "South Dakota","TN": "Tennessee","TX": "Texas","UT": "Utah","VT": "Vermont","VA": "Virginia","WA": "Washington",
        "WV": "West Virginia","WI": "Wisconsin","WY": "Wyoming","PR": "Puerto Rico","GU": "Guam","VI": "U.S. Virgin Islands",
        "AS": "American Samoa","MP": "Northern Mariana Islands",
    ]
}
