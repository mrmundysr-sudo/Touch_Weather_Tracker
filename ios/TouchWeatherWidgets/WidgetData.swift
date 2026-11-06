import Foundation
import SwiftUI
import WidgetKit
import AppIntents
import TWCore

/// Everything a widget needs, read from the shared on-device store (widgets refresh stale data themselves within a short time limit).
struct WxEntry: TimelineEntry {
    var date: Date
    var locked: Bool
    var hasSharedContainer: Bool
    var place: Place?
    var label: String
    var weather: WeatherData?
    var alerts: [Alert]?
    var storms: [ActiveStorm]?
    var stormsFetchedMs: Int64?

    var tz: String? { place?.timeZone }
    var isDay: Bool { weather?.hourly.first?.isDay ?? true }
    var temp: Double? { weather?.conditions?.tempF ?? weather?.hourly.first?.tempF }
    var summary: String? { weather?.conditions?.summary ?? weather?.hourly.first?.summary }
    var topAlert: Alert? { alerts?.min { $0.level < $1.level } }
    var warningActive: Bool { alerts?.contains { $0.level == .warning } == true }
    var updatedMs: Int64? { weather?.fetchedAtMs }
    var background: [Color] {
        if warningActive { return [Color(hex: 0xC8352B), Color(hex: 0x5A0F0B)] }
        return isDay ? [Color(hex: 0x2A7BC4), Color(hex: 0x0B2A4A)] : [Color(hex: 0x34457A), Color(hex: 0x0A1020)]
    }
    static func placeholder() -> WxEntry { WxEntry(date: Date(), locked: false, hasSharedContainer: true, place: nil, label: "Your city, ST", weather: nil, alerts: nil, storms: nil, stormsFetchedMs: nil) }
}


enum WidgetEnvironment {
    static let appGroup = "group.com.mundymorning.touchweathertracker"
    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) }
    static func repository() -> Repository {
        let base = container?.appendingPathComponent("store", isDirectory: true)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("store", isDirectory: true)
        return Repository(store: Store(directory: base), prefs: Prefs(suiteName: container != nil ? appGroup : nil))
    }
}

enum WidgetLoader {
    /// `placeId == nil` → Home (falling back to the selected place). A missing/removed chosen place yields an entry with no place.
    static func load(placeId: String?, follow: Bool, refreshStale: Bool = true) async -> WxEntry {
        let repo = WidgetEnvironment.repository()
        let locked = !repo.prefs.premiumActive()
        let place: Place? = follow ? (repo.home() ?? repo.selected()) : placeId.flatMap { repo.place($0) }
        if !locked, refreshStale, let p = place {
            let cached = repo.cachedWeather(p.id)
            if cached == nil || Units.nowMs() - cached!.fetchedAtMs > 15 * 60_000 { await withTimeout(10) { _ = await repo.weather(p); _ = await repo.alertsFor(p) } }
            let st: Cached<[ActiveStorm]>? = repo.cachedStorms()
            if st == nil || Units.nowMs() - st!.fetchedAtMs > 15 * 60_000 { await withTimeout(8) { _ = await repo.storms() } }
        }
        let alerts: AlertSet? = place.flatMap { repo.store.read("alerts_\($0.id)") }
        let storms = repo.cachedStorms()
        return WxEntry(date: Date(), locked: locked, hasSharedContainer: WidgetEnvironment.container != nil, place: place,
                       label: place.map { widgetLabel(for: $0, among: repo.places) } ?? "", weather: place.flatMap { repo.cachedWeather($0.id)?.value },
                       alerts: alerts?.alerts, storms: storms?.value, stormsFetchedMs: storms?.fetchedAtMs)
    }
    static var nextRefresh: Date { Date().addingTimeInterval(30 * 60) }
}

// MARK: Saved Place configuration (App Intents)

struct PlaceEntity: AppEntity {
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Place"
    static var defaultQuery = PlaceQuery()
    var id: String
    var name: String
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
}

struct PlaceQuery: EntityQuery {
    func entities(for identifiers: [String]) async throws -> [PlaceEntity] {
        let repo = WidgetEnvironment.repository()
        return identifiers.compactMap { repo.place($0) }.map { PlaceEntity(id: $0.id, name: $0.cityState) }
    }
    func suggestedEntities() async throws -> [PlaceEntity] { WidgetEnvironment.repository().places.map { PlaceEntity(id: $0.id, name: $0.cityState) } }
    func defaultResult() async -> PlaceEntity? { WidgetEnvironment.repository().home().map { PlaceEntity(id: $0.id, name: $0.cityState) } }
}

struct SavedPlaceIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Saved Place"
    static var description = IntentDescription("Weather and the top active alert for a place you choose.")
    @Parameter(title: "Place") var place: PlaceEntity?
}
