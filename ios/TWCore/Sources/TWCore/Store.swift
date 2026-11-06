import Foundation

/// Tiny atomic JSON file store for places, caches and small state (no database required). Safe to share between the app and its widgets via an App Group directory.
public final class Store: @unchecked Sendable {
    public let directory: URL
    private let lock = NSLock()
    public init(directory: URL) {
        self.directory = directory
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    private func file(_ name: String) -> URL {
        let safe = name.map { $0.isLetter || $0.isNumber || "._-".contains($0) ? String($0) : "_" }.joined()
        return directory.appendingPathComponent(safe + ".json")
    }
    public func read<T: Decodable>(_ name: String, as type: T.Type = T.self) -> T? {
        lock.lock(); defer { lock.unlock() }
        guard let d = try? Data(contentsOf: file(name)) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }
    public func write<T: Encodable>(_ name: String, _ value: T) {
        lock.lock(); defer { lock.unlock() }
        guard let d = try? JSONEncoder().encode(value) else { return }
        try? d.write(to: file(name), options: .atomic)
    }
    public func delete(_ name: String) { lock.lock(); defer { lock.unlock() }; try? FileManager.default.removeItem(at: file(name)) }
}

/// App-wide small settings and entitlement state (UserDefaults; pass an App Group suite to share with widgets).
public final class Prefs: @unchecked Sendable {
    private let d: UserDefaults
    public init(suiteName: String? = nil) { d = suiteName.flatMap { UserDefaults(suiteName: $0) } ?? .standard }
    public static func ephemeral() -> Prefs { Prefs(suiteName: "twc.test.\(UUID().uuidString)") }

    private func bool(_ k: String, _ def: Bool = false) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }

    public var onboarded: Bool { get { bool("onboarded") } set { d.set(newValue, forKey: "onboarded") } }
    public var notificationsAsked: Bool { get { bool("notif_asked") } set { d.set(newValue, forKey: "notif_asked") } }

    // map layers
    public var mapRadar: Bool { get { bool("map_radar", true) } set { d.set(newValue, forKey: "map_radar") } }
    public var mapSatellite: String { get { d.string(forKey: "map_sat") ?? "off" } set { d.set(newValue, forKey: "map_sat") } }   // off | visible | infrared | watervapor
    public var mapWarnings: Bool { get { bool("map_warn", true) } set { d.set(newValue, forKey: "map_warn") } }
    public var mapTracks: Bool { get { bool("map_tracks", true) } set { d.set(newValue, forKey: "map_tracks") } }
    public var mapObservations: Bool { get { bool("map_obs") } set { d.set(newValue, forKey: "map_obs") } }
    public var mapGfs: Bool { get { bool("map_gfs") } set { d.set(newValue, forKey: "map_gfs") } }
    public var mapEcmwf: Bool { get { bool("map_ecmwf") } set { d.set(newValue, forKey: "map_ecmwf") } }
    public var mapImagery: Bool { get { bool("map_imagery") } set { d.set(newValue, forKey: "map_imagery") } }

    // event mode
    public var eventModeActiveStorm: String? { get { d.string(forKey: "event_storm") } set { d.set(newValue, forKey: "event_storm") } }
    private func set(_ k: String) -> Set<String> { Set(d.stringArray(forKey: k) ?? []) }
    public var eventModeDeclined: Set<String> { get { set("event_declined") } set { d.set(Array(newValue), forKey: "event_declined") } }
    public var eventModeEndPromptDismissed: Set<String> { get { set("event_end_dismissed") } set { d.set(Array(newValue), forKey: "event_end_dismissed") } }
    public var eventModePromptedFor: Set<String> { get { set("event_prompted") } set { d.set(Array(newValue), forKey: "event_prompted") } }
    public var eventModeOffers: Bool { get { bool("event_offers", true) } set { d.set(newValue, forKey: "event_offers") } }

    // monetization (the whole app is locked until an entitlement exists)
    public var adViews: Int { get { d.integer(forKey: "ad_views") } set { d.set(newValue, forKey: "ad_views") } }
    public var unlockUntilMs: Int64 { get { Int64(d.double(forKey: "unlock_until")) } set { d.set(Double(newValue), forKey: "unlock_until") } }
    public var ownsAdFree: Bool { get { bool("own_adfree") } set { d.set(newValue, forKey: "own_adfree") } }
    public var hasSubscription: Bool { get { bool("own_sub") } set { d.set(newValue, forKey: "own_sub") } }
    /// True when the app may be used. Evaluated against the clock so a 24-hour unlock expires on time.
    public func premiumActive(now: Int64 = Units.nowMs()) -> Bool { ownsAdFree || hasSubscription || unlockUntilMs > now }

    // notification de-dup: alertId → "signature#endMs#event#label"
    public func notifiedValue(_ id: String) -> String? { d.string(forKey: "n_\(id)") }
    public func setNotified(_ id: String, _ v: String) { d.set(v, forKey: "n_\(id)") }
    public func forgetNotified(_ id: String) { d.removeObject(forKey: "n_\(id)") }
    public func notifiedAll() -> [String: String] {
        var out: [String: String] = [:]
        for (k, v) in d.dictionaryRepresentation() where k.hasPrefix("n_") { if let s = v as? String { out[String(k.dropFirst(2))] = s } }
        return out
    }
}
