import Foundation

// MARK: Places

public enum AlertLevel: String, Codable, CaseIterable, Sendable, Comparable {
    case warning = "WARNING", watch = "WATCH", advisory = "ADVISORY", statement = "STATEMENT"
    private var order: Int { switch self { case .warning: return 0; case .watch: return 1; case .advisory: return 2; case .statement: return 3 } }
    public static func < (a: AlertLevel, b: AlertLevel) -> Bool { a.order < b.order }
    public var label: String { rawValue }
}

/// Per-place notification preferences. Defaults follow the brief: only Home gets warnings by default.
public struct AlertPrefs: Codable, Equatable, Sendable {
    public var notify = false
    public var warnings = true
    public var watches = false
    public var advisories = false
    public var statements = false
    public init(notify: Bool = false, warnings: Bool = true, watches: Bool = false, advisories: Bool = false, statements: Bool = false) {
        self.notify = notify; self.warnings = warnings; self.watches = watches; self.advisories = advisories; self.statements = statements
    }
}

public struct UtilityChoice: Codable, Equatable, Sendable {
    public var name: String
    public var phone: String?
    public var website: String?
    public var state: String?
    public var manual = false
    public init(name: String, phone: String? = nil, website: String? = nil, state: String? = nil, manual: Bool = false) {
        self.name = name; self.phone = phone; self.website = website; self.state = state; self.manual = manual
    }
}

public struct Place: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var lat: Double
    public var lon: Double
    public var isHome = false
    public var isDefault = false
    public var subtitle: String?
    public var state: String?            // USPS abbreviation
    public var city: String?             // nearest NWS city, for compact "City, ST" labels
    public var countyName: String?
    public var countyFips: String?       // 5-digit state+county FIPS
    public var timeZone: String?
    public var usesDeviceLocation = false
    public var alertPrefs = AlertPrefs()
    public var utility: UtilityChoice?

    public init(id: String = UUID().uuidString, name: String, lat: Double, lon: Double, isHome: Bool = false, isDefault: Bool = false,
                subtitle: String? = nil, state: String? = nil, city: String? = nil, countyName: String? = nil, countyFips: String? = nil,
                timeZone: String? = nil, usesDeviceLocation: Bool = false, alertPrefs: AlertPrefs = AlertPrefs(), utility: UtilityChoice? = nil) {
        self.id = id; self.name = name; self.lat = lat; self.lon = lon; self.isHome = isHome; self.isDefault = isDefault
        self.subtitle = subtitle; self.state = state; self.city = city; self.countyName = countyName; self.countyFips = countyFips
        self.timeZone = timeZone; self.usesDeviceLocation = usesDeviceLocation; self.alertPrefs = alertPrefs; self.utility = utility
    }

    /// Title for screens: a place saved from a ZIP search shows its city too ("Montpelier · ZIP 05602").
    public var displayTitle: String { if name.hasPrefix("ZIP "), let city { return "\(city) · \(name)" }; return name }

    /// "City, ST" for compact displays; falls back to the saved name until the city is known.
    public var cityState: String {
        if let city, let state { return "\(city), \(state)" }
        return city ?? name
    }
}

/// A favorite state or county followed as a broader area of interest.
public struct FavoriteArea: Codable, Equatable, Identifiable, Sendable {
    public var id: String          // "ST:TX" or "CO:48201"
    public var label: String
    public var state: String
    public var countyFips: String?
    public var alertPrefs = AlertPrefs()
    public init(id: String, label: String, state: String, countyFips: String? = nil, alertPrefs: AlertPrefs = AlertPrefs()) {
        self.id = id; self.label = label; self.state = state; self.countyFips = countyFips; self.alertPrefs = alertPrefs
    }
}

// MARK: Weather

public struct Conditions: Codable, Equatable, Sendable {
    public var tempF: Double?
    public var feelsLikeF: Double?
    public var summary: String?
    public var windMph: Double?
    public var gustMph: Double?
    public var windDirDeg: Double?
    public var humidityPct: Double?
    public var dewpointF: Double?
    public var pressureInHg: Double?
    public var visibilityMi: Double?
    public var precipLastHourIn: Double?
    public var stationId: String?
    public var stationName: String?
    public var observedAtMs: Int64?
    public init() {}
}

public struct HourlyPoint: Codable, Equatable, Sendable {
    public var timeMs: Int64
    public var tempF: Double?
    public var feelsLikeF: Double?
    public var precipChance: Double?
    public var precipIn: Double?
    public var windMph: Double?
    public var gustMph: Double?
    public var windDir: String?
    public var humidityPct: Double?
    public var summary: String?
    public var isDay = true
    public init(timeMs: Int64, tempF: Double?) { self.timeMs = timeMs; self.tempF = tempF }
}

public struct DailyPoint: Codable, Equatable, Sendable {
    public var name: String
    public var startMs: Int64
    public var highF: Double?
    public var lowF: Double?
    public var summary: String
    public var detail: String
    public var precipChance: Double?
    public var windText: String?
    public init(name: String, startMs: Int64, highF: Double?, lowF: Double?, summary: String, detail: String, precipChance: Double? = nil, windText: String? = nil) {
        self.name = name; self.startMs = startMs; self.highF = highF; self.lowF = lowF; self.summary = summary; self.detail = detail
        self.precipChance = precipChance; self.windText = windText
    }
}

public struct SunTimes: Codable, Equatable, Sendable { public var sunriseMs: Int64?; public var sunsetMs: Int64?
    public init(sunriseMs: Int64?, sunsetMs: Int64?) { self.sunriseMs = sunriseMs; self.sunsetMs = sunsetMs } }

public struct WeatherData: Codable, Equatable, Sendable {
    public var placeId: String
    public var fetchedAtMs: Int64
    public var gridId: String?
    public var forecastOffice: String?
    public var radarStation: String?
    public var forecastUpdatedMs: Int64?
    public var conditions: Conditions?
    public var hourly: [HourlyPoint] = []
    public var daily: [DailyPoint] = []
    public var sun: SunTimes?
    public init(placeId: String, fetchedAtMs: Int64) { self.placeId = placeId; self.fetchedAtMs = fetchedAtMs }
}

// MARK: Alerts

public struct Alert: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var event: String
    public var level: AlertLevel
    public var severity: String
    public var urgency: String
    public var certainty: String
    public var headline: String?
    public var description: String
    public var instruction: String?
    public var areaDesc: String
    public var states: [String]
    public var sameCodes: [String]        // 6-digit SAME (0 + state fips + county fips)
    public var sentMs: Int64?
    public var effectiveMs: Int64?
    public var onsetMs: Int64?
    public var expiresMs: Int64?
    public var endsMs: Int64?
    public var senderName: String
    public var messageType: String        // Alert, Update, Cancel
    public var response: String?
    public var lifeSafety: Bool
    /// Outer rings of polygon geometry as [lon, lat] pairs.
    public var polygons: [[[Double]]] = []
    public var endMs: Int64? { endsMs ?? expiresMs }
}

public struct AlertSet: Codable, Equatable, Sendable { public var fetchedAtMs: Int64; public var alerts: [Alert]
    public init(fetchedAtMs: Int64, alerts: [Alert]) { self.fetchedAtMs = fetchedAtMs; self.alerts = alerts } }

/// A value plus how it got here. `stale` is true when a refresh failed and the last good copy is being shown.
public struct Loaded<T: Sendable>: Sendable {
    public var value: T?
    public var fetchedAtMs: Int64?
    public var error: String?
    public var stale: Bool
    public init(value: T?, fetchedAtMs: Int64?, error: String? = nil, stale: Bool = false) { self.value = value; self.fetchedAtMs = fetchedAtMs; self.error = error; self.stale = stale }
}

public struct Cached<T: Codable & Sendable>: Codable, Sendable { public var value: T; public var fetchedAtMs: Int64
    public init(value: T, fetchedAtMs: Int64) { self.value = value; self.fetchedAtMs = fetchedAtMs } }
