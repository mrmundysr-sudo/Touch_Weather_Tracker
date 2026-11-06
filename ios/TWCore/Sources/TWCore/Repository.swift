import Foundation

public enum FeedScope: String, Sendable, CaseIterable { case home, saved, favorites, all }

public struct AlertTag: Codable, Sendable { public var alert: Alert; public var label: String }

public struct StormBundle: Sendable {
    public var storm: ActiveStorm
    public var official: TrackLine?, gfs: TrackLine?, ecmwf: TrackLine?
    public var best: [AtcfFix]
    public var graphics: ForecastGraphics?
    public var advisoryText: String?
    public var highlights: [(String, String)]
    public var fetchedAtMs: Int64
}
public struct ResourceBundle: Sendable {
    public var shelters: [Shelter], centers: [RecoveryCenter], declarations: [Declaration]
    public var sheltersError: String?, centersError: String?, declarationsError: String?
    public var fetchedAtMs: Int64
}
public struct PowerBundle: Sendable {
    public var utilities: [UtilityChoice]; public var county: OutageCounty?
    public var countyError: String?, utilitiesError: String?
    public var fetchedAtMs: Int64
}
struct PowerCache: Codable { var utilities: [UtilityChoice]; var county: OutageCounty? }

/// ECMWF guidance provider. No free, machine-readable ECMWF track decoder exists yet (see docs/FEATURE_CHECKLIST.md) — never estimated.
public enum ModelTracks { public static func ecmwf(_ stormId: String) async -> TrackLine? { nil } }

/// Places, caching and honest stale-state handling. Reads of places are synchronous (so a just-made change is visible at once);
/// network calls are async. Mirrors the Android `Repository`.
public final class Repository: @unchecked Sendable {
    public let store: Store
    public let prefs: Prefs
    private let lock = NSLock()
    private var _places: [Place]
    private var _favorites: [FavoriteArea]
    private var _selectedId: String?
    /// Called (on any thread) after places/favorites/selection change.
    public var onChange: (@Sendable () -> Void)?

    public init(store: Store, prefs: Prefs) {
        self.store = store; self.prefs = prefs
        _places = store.read("places") ?? []
        _favorites = store.read("favorites") ?? []
        _selectedId = store.read("selected")
    }

    // MARK: places
    public var places: [Place] { lock.lock(); defer { lock.unlock() }; return _places }
    public var favorites: [FavoriteArea] { lock.lock(); defer { lock.unlock() }; return _favorites }
    public var selectedId: String? { lock.lock(); defer { lock.unlock() }; return _selectedId }
    public func home() -> Place? { places.first { $0.isHome } }
    public func place(_ id: String) -> Place? { places.first { $0.id == id } }
    public func selected() -> Place? {
        let l = places
        return l.first { $0.id == selectedId } ?? l.first { $0.isDefault } ?? l.first { $0.isHome } ?? l.first
    }

    private func savePlaces(_ list: [Place]) { lock.lock(); _places = list; lock.unlock(); store.write("places", list); onChange?() }
    public func select(_ id: String) { lock.lock(); _selectedId = id; lock.unlock(); store.write("selected", id); onChange?() }

    /// Create a place from a search result, pin or device location; enriches with NWS county/time zone/city. The first place becomes Home.
    @discardableResult
    public func addPlace(name: String, lat: Double, lon: Double, subtitle: String?, asHome: Bool = false, device: Bool = false) async -> Place {
        let enriched = try? await NwsSource.point(lat: lat, lon: lon)
        return addEnriched(name: name, lat: lat, lon: lon, subtitle: subtitle, asHome: asHome, device: device, point: enriched)
    }

    /// Synchronous core of `addPlace` (used by tests and after enrichment).
    @discardableResult
    public func addEnriched(name: String, lat: Double, lon: Double, subtitle: String?, asHome: Bool = false, device: Bool = false, point: NwsPoint? = nil) -> Place {
        let first = places.isEmpty
        let makeHome = asHome || first
        let p = Place(name: name, lat: lat, lon: lon, isHome: makeHome, isDefault: makeHome, subtitle: subtitle, state: point?.state, city: point?.city,
                      countyFips: point?.countyFips, timeZone: point?.timeZone, usesDeviceLocation: device,
                      alertPrefs: makeHome ? AlertPrefs(notify: true, warnings: true) : AlertPrefs())
        var list = places
        if makeHome { list = list.map { var x = $0; x.isHome = false; x.isDefault = false; return x } }   // keep the old Home's alert settings: never silently drop warnings
        savePlaces(list + [p])
        if makeHome || first { select(p.id) }
        return p
    }

    public func updatePlace(_ p: Place) { savePlaces(places.map { $0.id == p.id ? p : $0 }) }
    public func renamePlace(_ id: String, _ name: String) { if var p = place(id) { p.name = name; updatePlace(p) } }

    public func setHome(_ id: String) {
        savePlaces(places.map { var x = $0
            if x.id == id { x.isHome = true; x.isDefault = true; x.alertPrefs.notify = true }
            else if x.isHome { x.isHome = false; x.isDefault = false }
            return x })
    }
    public func setDefault(_ id: String) { savePlaces(places.map { var x = $0; x.isDefault = x.id == id; return x }) }

    public func removePlace(_ id: String) {
        var list = places.filter { $0.id != id }
        if !list.isEmpty && !list.contains(where: \.isHome) { list[0].isHome = true; list[0].isDefault = true; list[0].alertPrefs.notify = true }
        savePlaces(list)
        for k in ["weather_\(id)", "alerts_\(id)", "power_\(id)"] { store.delete(k) }
        if selectedId == id, let f = list.first { select(f.id) }
    }

    public func addFavorite(_ f: FavoriteArea) { if !favorites.contains(where: { $0.id == f.id }) { saveFavs(favorites + [f]) } }
    public func updateFavorite(_ f: FavoriteArea) { saveFavs(favorites.map { $0.id == f.id ? f : $0 }) }
    public func removeFavorite(_ id: String) { saveFavs(favorites.filter { $0.id != id }) }
    private func saveFavs(_ l: [FavoriteArea]) { lock.lock(); _favorites = l; lock.unlock(); store.write("favorites", l); onChange?() }

    // MARK: weather
    private func fresh<T>(_ c: Cached<T>?, ttlMs: Int64) -> Bool { c != nil && Units.nowMs() - c!.fetchedAtMs < ttlMs }
    public func cachedWeather(_ placeId: String) -> Cached<WeatherData>? { store.read("weather_\(placeId)") }

    public func weather(_ place: Place, force: Bool = false) async -> Loaded<WeatherData> {
        let cached = cachedWeather(place.id)
        if !force, fresh(cached, ttlMs: 10 * 60_000) { return Loaded(value: cached!.value, fetchedAtMs: cached!.fetchedAtMs) }
        do {
            let (data, pt) = try await NwsSource.weather(for: place, existing: cached?.value)
            store.write("weather_\(place.id)", Cached(value: data, fetchedAtMs: data.fetchedAtMs))
            // Keep place metadata (time zone, county, city) current without clobbering user edits.
            if var cur = self.place(place.id), cur.timeZone != pt.timeZone || cur.countyFips != pt.countyFips || cur.state != pt.state || cur.city != pt.city {
                cur.timeZone = pt.timeZone ?? cur.timeZone; cur.countyFips = pt.countyFips ?? cur.countyFips; cur.state = pt.state ?? cur.state; cur.city = pt.city ?? cur.city
                updatePlace(cur)
            }
            return Loaded(value: data, fetchedAtMs: data.fetchedAtMs)
        } catch {
            if let c = cached { return Loaded(value: c.value, fetchedAtMs: c.fetchedAtMs, error: error.localizedDescription, stale: true) }
            return Loaded(value: nil, fetchedAtMs: nil, error: error.localizedDescription)
        }
    }

    // MARK: alerts
    public func alertsFor(_ place: Place, force: Bool = false) async -> Loaded<AlertSet> {
        let key = "alerts_\(place.id)"
        let cached: AlertSet? = store.read(key)
        if !force, let c = cached, Units.nowMs() - c.fetchedAtMs < 90_000 { return Loaded(value: c, fetchedAtMs: c.fetchedAtMs) }
        do {
            let set = AlertSet(fetchedAtMs: Units.nowMs(), alerts: try await NwsSource.alertsForPoint(lat: place.lat, lon: place.lon))
            store.write(key, set)
            return Loaded(value: set, fetchedAtMs: set.fetchedAtMs)
        } catch {
            if let c = cached { return Loaded(value: c, fetchedAtMs: c.fetchedAtMs, error: error.localizedDescription, stale: true) }
            return Loaded(value: nil, fetchedAtMs: nil, error: error.localizedDescription)
        }
    }

    public func alertsForFavorite(_ f: FavoriteArea) async throws -> [Alert] {
        let all = try await NwsSource.alertsForState(f.state)
        guard let c = f.countyFips else { return all }
        return all.filter { a in a.sameCodes.contains { $0.hasSuffix(c) } }
    }

    /// Alerts feed for a scope; each alert tagged with the place/area that matched it.
    public func feed(_ scope: FeedScope, force: Bool = false) async -> Loaded<[AlertTag]> {
        let key = "feed_\(scope.rawValue)"
        let cached: Cached<[AlertTag]>? = store.read(key)
        if !force, fresh(cached, ttlMs: 90_000) { return Loaded(value: cached!.value, fetchedAtMs: cached!.fetchedAtMs) }
        do {
            var out: [String: AlertTag] = [:], order: [String] = []
            func add(_ alerts: [Alert], _ label: String) { for a in alerts where out[a.id] == nil { out[a.id] = AlertTag(alert: a, label: label); order.append(a.id) } }
            switch scope {
            case .home: if let h = home() { add(try await NwsSource.alertsForPoint(lat: h.lat, lon: h.lon), h.name) }
            case .saved:
                let results = await withTaskGroup(of: (Place, [Alert]?).self) { g -> [(Place, [Alert]?)] in
                    for p in places { g.addTask { (p, try? await NwsSource.alertsForPoint(lat: p.lat, lon: p.lon)) } }
                    var r: [(Place, [Alert]?)] = []; for await x in g { r.append(x) }; return r
                }
                if !results.isEmpty && results.allSatisfy({ $0.1 == nil }) { throw SourceError("NWS", "Alerts are unavailable.") }
                for p in places { if let alerts = results.first(where: { $0.0.id == p.id })?.1 { add(alerts, p.name) } }
            case .favorites:
                var anyOk = false
                for f in favorites { if let a = try? await alertsForFavorite(f) { anyOk = true; add(a, f.label) } }
                if !favorites.isEmpty && !anyOk { throw SourceError("NWS", "Alerts are unavailable.") }
            case .all: add(try await NwsSource.alertsAll(), "")
            }
            let list = order.compactMap { out[$0] }
            store.write(key, Cached(value: list, fetchedAtMs: Units.nowMs()))
            return Loaded(value: list, fetchedAtMs: Units.nowMs())
        } catch {
            if let c = cached { return Loaded(value: c.value, fetchedAtMs: c.fetchedAtMs, error: error.localizedDescription, stale: true) }
            return Loaded(value: nil, fetchedAtMs: nil, error: error.localizedDescription)
        }
    }

    // MARK: storms
    public func storms(force: Bool = false) async -> Loaded<[ActiveStorm]> {
        let cached: Cached<[ActiveStorm]>? = store.read("storms")
        if !force, fresh(cached, ttlMs: 4 * 60_000) { return Loaded(value: cached!.value, fetchedAtMs: cached!.fetchedAtMs) }
        do {
            let list = try await NhcSource.activeStorms()
            store.write("storms", Cached(value: list, fetchedAtMs: Units.nowMs()))
            return Loaded(value: list, fetchedAtMs: Units.nowMs())
        } catch {
            if let c = cached { return Loaded(value: c.value, fetchedAtMs: c.fetchedAtMs, error: error.localizedDescription, stale: true) }
            return Loaded(value: nil, fetchedAtMs: nil, error: error.localizedDescription)
        }
    }
    public func cachedStorms() -> Cached<[ActiveStorm]>? { store.read("storms") }

    public func stormBundle(_ storm: ActiveStorm) async -> StormBundle {
        async let fixes = (try? await NhcSource.aDeck(storm.id)) ?? []
        async let best = (try? await NhcSource.bestTrack(storm.id)) ?? []
        async let gfx = NhcSource.graphics(storm)
        async let text: String? = { if let u = storm.publicAdvisoryUrl { return try? await NhcSource.productText(u) } else { return nil } }()
        let f = await fixes, t = await text
        return StormBundle(storm: storm, official: Atcf.latestTrack(f, tech: "OFCL"), gfs: Atcf.latestTrack(f, tech: "AVNO"), ecmwf: await ModelTracks.ecmwf(storm.id),
                           best: await best, graphics: await gfx, advisoryText: t, highlights: t.map(NhcSource.advisoryHighlights) ?? [], fetchedAtMs: Units.nowMs())
    }

    public func stormReport(for place: Place, bundle: StormBundle) async -> StormReportData {
        async let buoys = (try? await NdbcSource.nearby(lat: place.lat, lon: place.lon, radiusMi: 200, maxAgeMin: 240, limit: 3)) ?? []
        async let gauges = CoopsSource.nearby(lat: place.lat, lon: place.lon, radiusMi: 60, limit: 2, maxAgeMin: 120)
        async let recon = ReconSource.summary(stormId: bundle.storm.id, centerLat: bundle.storm.lat, centerLon: bundle.storm.lon)
        async let alerts = alertsFor(place)
        var wx = cachedWeather(place.id)?.value
        if wx == nil { wx = (await weather(place)).value }
        return StormReportBuilder.build(StormInputs(place: place, storm: bundle.storm, officialTrack: bundle.official, coneRings: bundle.graphics?.cone.flatMap(\.rings) ?? [],
                                                    advisoryHighlights: bundle.highlights, placeAlerts: (await alerts).value?.alerts ?? [], buoys: await buoys,
                                                    waterLevels: await gauges, recon: await recon, weather: wx))
    }

    // MARK: resources & power
    public func resources(_ place: Place) async -> ResourceBundle {
        async let sh: Result<[Shelter], Error> = Result { try await FemaSource.shelters(lat: place.lat, lon: place.lon) }
        async let dr: Result<[RecoveryCenter], Error> = Result { try await FemaSource.recoveryCenters(lat: place.lat, lon: place.lon) }
        async let de: Result<[Declaration], Error> = Result { place.state != nil ? try await FemaSource.declarations(state: place.state!, countyFips: place.countyFips) : [] }
        let s = await sh, d = await dr, c = await de
        return ResourceBundle(shelters: (try? s.get()) ?? [], centers: (try? d.get()) ?? [], declarations: (try? c.get()) ?? [],
                              sheltersError: s.failureMessage, centersError: d.failureMessage, declarationsError: c.failureMessage, fetchedAtMs: Units.nowMs())
    }

    public func power(_ place: Place, force: Bool = false) async -> PowerBundle {
        let cached: Cached<PowerCache>? = store.read("power_\(place.id)")
        if !force, fresh(cached, ttlMs: 11 * 60_000), let c = cached { return PowerBundle(utilities: c.value.utilities, county: c.value.county, countyError: nil, utilitiesError: nil, fetchedAtMs: c.fetchedAtMs) }
        async let u: Result<[UtilityChoice], Error> = Result { try await UtilitySource.at(lat: place.lat, lon: place.lon) }
        async let o: Result<OutageCounty?, Error> = Result { if let f = place.countyFips { return try await MassOutageSource.county(f) } else { return nil } }
        let ur = await u, or = await o
        let b = PowerBundle(utilities: (try? ur.get()) ?? [], county: (try? or.get()) ?? nil, countyError: or.failureMessage, utilitiesError: ur.failureMessage, fetchedAtMs: Units.nowMs())
        if case .success = ur { store.write("power_\(place.id)", Cached(value: PowerCache(utilities: b.utilities, county: b.county), fetchedAtMs: b.fetchedAtMs)) }
        else if case .success = or { store.write("power_\(place.id)", Cached(value: PowerCache(utilities: b.utilities, county: b.county), fetchedAtMs: b.fetchedAtMs)) }
        return b
    }

    public func nationalOutages() async -> Loaded<OutageNational> {
        let cached: Cached<OutageNational>? = store.read("outage_national")
        if fresh(cached, ttlMs: 11 * 60_000), let c = cached { return Loaded(value: c.value, fetchedAtMs: c.fetchedAtMs) }
        do {
            guard let n = try await MassOutageSource.national() else { throw SourceError("MassOutage", "No data") }
            store.write("outage_national", Cached(value: n, fetchedAtMs: Units.nowMs()))
            return Loaded(value: n, fetchedAtMs: Units.nowMs())
        } catch {
            if let c = cached { return Loaded(value: c.value, fetchedAtMs: c.fetchedAtMs, error: error.localizedDescription, stale: true) }
            return Loaded(value: nil, fetchedAtMs: nil, error: error.localizedDescription)
        }
    }

    public func setUtility(_ place: Place, _ u: UtilityChoice?) { var p = place; p.utility = u; updatePlace(p) }
}

extension Result {
    init(_ body: () async throws -> Success) async where Failure == Error { do { self = .success(try await body()) } catch { self = .failure(error) } }
    var failureMessage: String? { if case .failure(let e) = self { return e.localizedDescription }; return nil }
}

/// Decides which alerts to notify about. Notifies for NEW alerts, material updates (different expiry/text/type) and early endings — never on every poll,
/// and never applies quiet hours to a new severe alert. Mirrors the Android `AlertEngine`.
public enum AlertEngine {
    public struct Planned: Sendable { public var alert: Alert; public var label: String; public var isUpdate: Bool }
    public struct Ended: Sendable { public var event: String; public var label: String }
    public struct Target: Sendable { public var label: String; public var prefs: AlertPrefs; public var alerts: [Alert]
        public init(label: String, prefs: AlertPrefs, alerts: [Alert]) { self.label = label; self.prefs = prefs; self.alerts = alerts } }

    public static func wants(_ prefs: AlertPrefs, _ a: Alert) -> Bool {
        guard prefs.notify else { return false }
        switch a.level {
        case .warning: return prefs.warnings
        case .watch: return prefs.watches
        case .advisory: return prefs.advisories
        case .statement: return prefs.statements || (a.lifeSafety && prefs.warnings)
        }
    }

    public static func signature(_ a: Alert) -> String {
        [a.messageType, a.endMs.map(String.init) ?? "", String((a.headline ?? "").hashValue), String(a.description.hashValue), String((a.instruction ?? "").hashValue)].joined(separator: "|")
    }

    /// Compute what to notify and record the new state in `prefs`. `now` is injectable for tests.
    public static func plan(targets: [Target], prefs: Prefs, now: Int64 = Units.nowMs()) -> (notify: [Planned], ended: [Ended]) {
        var notify: [Planned] = [], seen = Set<String>()
        for t in targets { for a in t.alerts {
            guard wants(t.prefs, a) else { continue }
            if let e = a.endMs, e < now { continue }
            guard seen.insert(a.id).inserted else { continue }
            let sig = signature(a)
            let prev = prefs.notifiedValue(a.id)
            let stored = "\(sig)#\(a.endMs ?? 0)#\(a.event)#\(t.label)"
            if prev == nil { notify.append(Planned(alert: a, label: t.label, isUpdate: false)) }
            else if prev!.components(separatedBy: "#").first != sig { notify.append(Planned(alert: a, label: t.label, isUpdate: true)) }
            prefs.setNotified(a.id, stored)
        } }
        var ended: [Ended] = []
        for (id, v) in prefs.notifiedAll() where !seen.contains(id) {
            let parts = v.components(separatedBy: "#")
            if (Int64(parts.count > 1 ? parts[1] : "") ?? 0) > now + 5 * 60_000 { ended.append(Ended(event: parts.count > 2 ? parts[2] : "", label: parts.count > 3 ? parts[3] : "")) }
            prefs.forgetNotified(id)
        }
        return (notify, ended)
    }
}
