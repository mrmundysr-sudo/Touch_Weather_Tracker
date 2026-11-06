import Foundation
import SwiftUI
import TWCore
#if canImport(WidgetKit)
import WidgetKit
#endif

/// SwiftUI also defines `Alert`; make the weather alert model win inside this module.
typealias Alert = TWCore.Alert

struct TodayState { var weather: Loaded<WeatherData>?; var alerts: Loaded<AlertSet>?; var outlook: Outlook?; var loading = false }
struct FeedState { var scope: FeedScope = .home; var data: Loaded<[AlertTag]>?; var loading = false }
struct SearchState { var query = ""; var results: [GeoResult] = []; var loading = false; var error: String?; var searched = false }
struct StormsState { var storms: Loaded<[ActiveStorm]>?; var loading = false; var event: EventSignal? }
struct ResourcesState { var bundle: ResourceBundle?; var blueRoof: [BlueRoofMission]?; var blueRoofError: String?; var loading = false }
struct PowerState { var bundle: PowerBundle?; var national: Loaded<OutageNational>?; var loading = false }

/// Shared UI state: the Swift counterpart of the Android `AppViewModel`. Places are read synchronously from the repository so a Home change is visible at once.
@MainActor
final class AppModel: ObservableObject {
    let repo: Repository
    let monetization: Monetization
    let location = LocationProvider()

    @Published private(set) var places: [Place]
    @Published private(set) var favorites: [FavoriteArea]
    @Published private(set) var selectedId: String?
    @Published var today = TodayState()
    @Published var feed = FeedState()
    @Published var search = SearchState()
    @Published var storms = StormsState()
    @Published var bundles: [String: StormBundle] = [:]
    @Published var bundleLoading: Set<String> = []
    @Published var reports: [String: StormReportData] = [:]
    @Published var resources = ResourcesState()
    @Published var power = PowerState()
    @Published var message: String?
    @Published var alertIndex: [String: Alert] = [:]
    @Published var prefsTick = 0
    @Published var launchRequest: LaunchRequest?
    @Published var tab: AppTab = .today
    @Published var mapFocus: MapFocus?
    @Published var path: [Route] = []
    @Published var pendingAlertId: String?
    @Published var mapAlerts: [Alert] = []

    private var todayTask: Task<Void, Never>?
    private var feedTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?

    var prefs: Prefs { repo.prefs }
    var home: Place? { repo.home() }
    var selected: Place? { repo.selected() }

    init(repo: Repository = AppEnvironment.makeRepository()) {
        self.repo = repo
        #if TWC_UITEST
        // CI simulator UI test only (separate "UITest" build configuration): unlock so the test can reach the main tabs. Not compiled into installable builds.
        repo.prefs.unlockUntilMs = Units.nowMs() + 6 * 3_600_000
        #endif
        self.monetization = Monetization(prefs: repo.prefs)
        places = repo.places; favorites = repo.favorites; selectedId = repo.selectedId
        repo.onChange = { [weak self] in Task { @MainActor in self?.syncFromRepo() } }
    }

    private func syncFromRepo() { places = repo.places; favorites = repo.favorites; selectedId = repo.selectedId }
    func bumpPrefs() { prefsTick += 1 }
    func toast(_ m: String?) { message = m }
    private func index(_ alerts: [Alert]) { for a in alerts { alertIndex[a.id] = a } }

    // MARK: Today
    func refreshToday(force: Bool = false) {
        guard let place = repo.selected() else { return }       // read the repository directly: always the newest selection
        todayTask?.cancel()
        let cachedW = repo.cachedWeather(place.id)
        if today.weather?.value?.placeId != place.id { today.weather = cachedW.map { Loaded(value: $0.value, fetchedAtMs: $0.fetchedAtMs) } }
        today.loading = true
        todayTask = Task { [repo, self] in
            async let w = repo.weather(place, force: force)
            async let a = repo.alertsFor(place, force: force)
            let wr = await w, ar = await a
            let outlook: Outlook? = await {
                guard let office = repo.cachedWeather(place.id)?.value.forecastOffice else { return nil }
                return (try? await NwsSource.hazardousOutlook(office: office)) ?? nil
            }()
            if Task.isCancelled { return }
            if let al = ar.value?.alerts { index(al) }
            today = TodayState(weather: wr, alerts: ar, outlook: outlook, loading: false)
            reloadWidgets()
        }
    }

    func selectPlace(_ id: String) { repo.select(id); resources = ResourcesState(); power = PowerState(); refreshToday() }

    /// Make a place Home. Today and the widgets switch immediately (widgets redraw from cache at once, and again after fresh data arrives).
    func makeHome(_ id: String) {
        repo.setHome(id); repo.select(id)
        resources = ResourcesState(); power = PowerState()
        refreshToday(force: true)
        reloadWidgets()
        Task { [repo, self] in
            if let h = repo.home() { await withTimeout(25) { _ = await repo.weather(h, force: true); _ = await repo.alertsFor(h, force: true) } }
            reloadWidgets()
        }
    }

    func removePlace(_ id: String) {
        let wasHome = repo.place(id)?.isHome == true
        repo.removePlace(id)
        resources = ResourcesState(); power = PowerState()
        refreshToday(force: true)
        if wasHome { reloadWidgets() }
    }

    func reloadWidgets() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }

    // MARK: Alerts feed
    func loadFeed(_ scope: FeedScope? = nil, force: Bool = false) {
        let s = scope ?? feed.scope
        feedTask?.cancel()
        feed.scope = s; feed.loading = true
        feedTask = Task { [repo, self] in
            let r = await repo.feed(s, force: force)
            if Task.isCancelled { return }
            if let v = r.value { index(v.map(\.alert)) }
            feed = FeedState(scope: s, data: r, loading: false)
        }
    }

    /// Nationwide active alerts for the map's warning polygons (kept separate from the Alerts tab's chosen scope).
    func loadMapAlerts() {
        Task { [repo, self] in
            let r = await repo.feed(.all)
            if let v = r.value { mapAlerts = v.map(\.alert); index(mapAlerts) }
        }
    }

    // MARK: Places & search
    func onSearchQuery(_ q: String) {
        search.query = q; search.error = nil
        searchTask?.cancel()
        if q.trimmingCharacters(in: .whitespaces).count < 3 { search.results = []; search.searched = false; search.loading = false; return }
        searchTask = Task { [self] in
            try? await Task.sleep(nanoseconds: 450_000_000)
            if Task.isCancelled { return }
            search.loading = true
            do {
                let r = try await CensusSource.search(q)
                if Task.isCancelled { return }
                search.results = r.filter { Geo.inUsCoverage($0.lat, $0.lon) }; search.loading = false; search.searched = true
            } catch {
                if Task.isCancelled { return }
                search.loading = false; search.error = error.localizedDescription; search.searched = true
            }
        }
    }
    func clearSearch() { searchTask?.cancel(); search = SearchState() }

    func addFromResult(_ g: GeoResult, asHome: Bool = false) async -> Place {
        let p = await repo.addPlace(name: g.label, lat: g.lat, lon: g.lon, subtitle: g.subtitle ?? g.kind, asHome: asHome)
        refreshToday(); reloadWidgets(); return p
    }

    func addPinned(lat: Double, lon: Double, asHome: Bool = false) async -> Place? {
        guard let rev = try? await CensusSource.reverse(lat: lat, lon: lon) else {
            message = "That spot is outside NWS coverage. Touch Weather covers the United States."; return nil
        }
        let p = await repo.addPlace(name: rev.name, lat: lat, lon: lon, subtitle: rev.state, asHome: asHome)
        refreshToday(); reloadWidgets(); return p
    }

    func addDeviceLocation(asHome: Bool) async -> Place? {
        guard let loc = await location.requestCurrent() else {
            message = "Couldn't get your location. Check that Location Services are on and allowed for Touch Weather, or search for an address instead."; return nil
        }
        guard let rev = try? await CensusSource.reverse(lat: loc.coordinate.latitude, lon: loc.coordinate.longitude) else {
            message = "Your device location is outside NWS coverage."; return nil
        }
        let p = await repo.addPlace(name: rev.name.isEmpty ? "My location" : rev.name, lat: loc.coordinate.latitude, lon: loc.coordinate.longitude,
                                    subtitle: "Device location" + (rev.state.map { " · \($0)" } ?? ""), asHome: asHome, device: true)
        refreshToday(); reloadWidgets(); return p
    }

    // MARK: Storms
    func loadStorms(force: Bool = false) {
        storms.loading = true
        Task { [repo, self] in
            let r = await repo.storms(force: force)
            let hurr = ((try? await NwsSource.alertsAll()) ?? []).filter { $0.event.hasPrefix("Hurricane") }
            var ev: EventSignal?
            if let list = r.value { ev = await EventMode.evaluate(storms: list, hurricaneAlerts: hurr) }
            storms = StormsState(storms: r, loading: false, event: ev)
        }
    }

    func loadBundle(_ storm: ActiveStorm, force: Bool = false) {
        if !force, let b = bundles[storm.id], Units.nowMs() - b.fetchedAtMs < 5 * 60_000 { return }
        if bundleLoading.contains(storm.id) { return }
        bundleLoading.insert(storm.id)
        Task { [repo, self] in
            let b = await repo.stormBundle(storm)
            bundles[storm.id] = b; bundleLoading.remove(storm.id)
        }
    }

    func loadReport(place: Place, bundle: StormBundle) {
        Task { [repo, self] in
            let r = await repo.stormReport(for: place, bundle: bundle)
            reports["\(bundle.storm.id)|\(place.id)"] = r
        }
    }

    func eventAction(_ action: EventAction, stormId: String) {
        switch action {
        case .enable: prefs.eventModeActiveStorm = stormId; prefs.eventModeDeclined.remove(stormId)
        case .decline: prefs.eventModeDeclined.insert(stormId)
        case .end: prefs.eventModeActiveStorm = nil
        case .keep: prefs.eventModeEndPromptDismissed.insert(stormId)
        }
        BackgroundRefresh.schedule(eventMode: prefs.eventModeActiveStorm != nil)
        bumpPrefs(); loadStorms()
    }

    // MARK: Resources & power
    func loadResources() {
        guard let place = repo.selected() else { return }
        resources.loading = true
        Task { [repo, self] in
            let b = await repo.resources(place)
            let br: Result<[BlueRoofMission], Error> = await Result { try await BlueRoofSource.activeMissions(state: place.state) }
            resources = ResourcesState(bundle: b, blueRoof: try? br.get(), blueRoofError: br.failureMessage, loading: false)
        }
    }

    func loadPower(force: Bool = false) {
        guard let place = repo.selected() else { return }
        power.loading = true
        Task { [repo, self] in
            let b = await repo.power(place, force: force)
            let n = await repo.nationalOutages()
            power = PowerState(bundle: b, national: n, loading: false)
        }
    }
}

enum EventAction { case enable, decline, end, keep }

/// One-shot request from outside the UI (notification / widget taps).
struct LaunchRequest: Equatable { var tab: AppTab?; var alertId: String?; var placeId: String?; var stormId: String?; var nonce = UUID() }
enum AppTab: String, CaseIterable, Identifiable { case today, map, alerts, storms, resources; var id: String { rawValue } }

/// Runs `body` but gives up waiting after `seconds`.
func withTimeout(_ seconds: Double, _ body: @escaping @Sendable () async -> Void) async {
    await withTaskGroup(of: Void.self) { g in
        g.addTask { await body() }
        g.addTask { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
        _ = await g.next(); g.cancelAll()
    }
}

struct MapFocus: Equatable { var lat: Double; var lon: Double; var spanDegrees: Double = 6; var nonce = UUID() }

/// Pushed destinations (value-based navigation).
enum Route: Hashable {
    case alert(String), places, search(home: Bool), favorites, settings, unlockStatus, sources, power, storm(String)
}
