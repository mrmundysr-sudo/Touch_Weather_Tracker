import XCTest
@testable import TWCore

final class RepositoryTests: XCTestCase {
    func makeRepo() -> (Repository, URL, Prefs) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("twc-\(UUID().uuidString)")
        let prefs = Prefs.ephemeral()
        return (Repository(store: Store(directory: dir), prefs: prefs), dir, prefs)
    }

    func testFirstPlaceBecomesHomeWithNotificationsOn() {
        let (r, _, _) = makeRepo()
        let a = r.addEnriched(name: "A", lat: 44.26, lon: -72.57, subtitle: nil)
        XCTAssertTrue(a.isHome); XCTAssertTrue(a.alertPrefs.notify); XCTAssertEqual(r.selected()?.id, a.id)
        let b = r.addEnriched(name: "B", lat: 29.76, lon: -95.37, subtitle: nil)
        XCTAssertFalse(b.isHome); XCTAssertFalse(b.alertPrefs.notify)        // other places default to no notifications
    }

    func testSwitchingHomeBackAndForthKeepsExactlyOneHomeAndPersists() {
        let (r, dir, prefs) = makeRepo()
        let a = r.addEnriched(name: "A", lat: 44.26, lon: -72.57, subtitle: nil), b = r.addEnriched(name: "B", lat: 29.76, lon: -95.37, subtitle: nil)
        func assertHome(_ id: String, line: UInt = #line) {
            XCTAssertEqual(r.home()?.id, id, line: line); XCTAssertEqual(r.selected()?.id, id, line: line)
            XCTAssertEqual(r.places.filter(\.isHome).count, 1, line: line)
            XCTAssertEqual(Repository(store: Store(directory: dir), prefs: prefs).home()?.id, id, "persisted", line: line)
        }
        r.setHome(b.id); r.select(b.id); assertHome(b.id)
        r.setHome(a.id); r.select(a.id); assertHome(a.id)
        r.setHome(b.id); r.select(b.id); assertHome(b.id)
        XCTAssertTrue(r.place(a.id)!.alertPrefs.notify, "the old Home keeps its alert settings")
    }

    func testBrowsingAnotherPlaceDoesNotMoveHome() {
        let (r, _, _) = makeRepo()
        let a = r.addEnriched(name: "A", lat: 1, lon: 1, subtitle: nil), b = r.addEnriched(name: "B", lat: 2, lon: 2, subtitle: nil)
        r.select(b.id)
        XCTAssertEqual(r.selected()?.id, b.id); XCTAssertEqual(r.home()?.id, a.id)
    }

    func testRemovingHomePromotesAnotherAndSelection() {
        let (r, _, _) = makeRepo()
        let a = r.addEnriched(name: "A", lat: 1, lon: 1, subtitle: nil), b = r.addEnriched(name: "B", lat: 2, lon: 2, subtitle: nil)
        r.removePlace(a.id)
        XCTAssertEqual(r.home()?.id, b.id); XCTAssertTrue(r.home()!.alertPrefs.notify); XCTAssertEqual(r.selected()?.id, b.id)
    }

    func testOnChangeFiresOnHomeChange() {
        let (r, _, _) = makeRepo()
        let a = r.addEnriched(name: "A", lat: 1, lon: 1, subtitle: nil); _ = r.addEnriched(name: "B", lat: 2, lon: 2, subtitle: nil)
        let count = LockedCounter(); r.onChange = { count.bump() }
        r.setHome(r.places.first { $0.id != a.id }!.id)
        XCTAssertGreaterThanOrEqual(count.value, 1)
    }

    func testFavoritesAddRemoveNoDuplicates() {
        let (r, _, _) = makeRepo()
        r.addFavorite(FavoriteArea(id: "ST:TX", label: "Texas", state: "TX")); r.addFavorite(FavoriteArea(id: "ST:TX", label: "Texas", state: "TX"))
        XCTAssertEqual(r.favorites.count, 1); r.removeFavorite("ST:TX"); XCTAssertTrue(r.favorites.isEmpty)
    }

    func testPremiumUnlockExpiresOnTime() {
        let p = Prefs.ephemeral()
        XCTAssertFalse(p.premiumActive())
        p.unlockUntilMs = 10_000
        XCTAssertTrue(p.premiumActive(now: 9_999)); XCTAssertFalse(p.premiumActive(now: 10_001))
        p.ownsAdFree = true; XCTAssertTrue(p.premiumActive(now: 10_001))
        p.ownsAdFree = false; p.hasSubscription = true; XCTAssertTrue(p.premiumActive(now: 10_001))
    }

    // MARK: alert planner
    func alert(_ id: String, _ event: String, ends: Int64? = nil, headline: String = "h", life: Bool = false) -> Alert {
        Alert(id: id, event: event, level: NwsSource.levelOf(event), severity: "Severe", urgency: "Immediate", certainty: "Observed", headline: headline, description: "d", instruction: nil,
              areaDesc: "Area", states: ["TX"], sameCodes: [], sentMs: 1, effectiveMs: nil, onsetMs: nil, expiresMs: ends, endsMs: nil, senderName: "NWS", messageType: "Alert", response: nil, lifeSafety: life)
    }

    func testPlannerNotifiesOnceThenOnUpdateThenOnEarlyEnd() {
        let prefs = Prefs.ephemeral()
        let home = AlertPrefs(notify: true, warnings: true)
        let now: Int64 = 1_000_000
        let tw = alert("a1", "Tornado Warning", ends: now + 3_600_000, life: true)
        var plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: home, alerts: [tw])], prefs: prefs, now: now)
        XCTAssertEqual(plan.notify.count, 1); XCTAssertFalse(plan.notify[0].isUpdate)
        plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: home, alerts: [tw])], prefs: prefs, now: now + 60_000)
        XCTAssertTrue(plan.notify.isEmpty, "an unchanged alert is not re-notified on every poll")
        let upd = alert("a1", "Tornado Warning", ends: now + 3_600_000, headline: "changed", life: true)
        plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: home, alerts: [upd])], prefs: prefs, now: now + 120_000)
        XCTAssertEqual(plan.notify.count, 1); XCTAssertTrue(plan.notify[0].isUpdate)
        plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: home, alerts: [])], prefs: prefs, now: now + 180_000)
        XCTAssertEqual(plan.ended.map(\.event), ["Tornado Warning"])            // disappeared well before its own end time
        plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: home, alerts: [])], prefs: prefs, now: now + 240_000)
        XCTAssertTrue(plan.ended.isEmpty)
    }

    func testPlannerRespectsPerPlacePreferences() {
        let prefs = Prefs.ephemeral()
        let warnOnly = AlertPrefs(notify: true, warnings: true), watches = AlertPrefs(notify: true, warnings: true, watches: true), off = AlertPrefs(notify: false)
        let watch = alert("w1", "Hurricane Watch"), warn = alert("w2", "Flash Flood Warning", life: true), adv = alert("w3", "Wind Advisory")
        XCTAssertFalse(AlertEngine.wants(warnOnly, watch)); XCTAssertTrue(AlertEngine.wants(warnOnly, warn)); XCTAssertFalse(AlertEngine.wants(warnOnly, adv))
        XCTAssertTrue(AlertEngine.wants(watches, watch)); XCTAssertFalse(AlertEngine.wants(off, warn))
        let plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: warnOnly, alerts: [watch, warn, adv])], prefs: prefs)
        XCTAssertEqual(plan.notify.map(\.alert.id), ["w2"])
    }

    func testOverlappingTargetsDedupeSameAlert() {
        let prefs = Prefs.ephemeral(), p = AlertPrefs(notify: true, warnings: true)
        let a = alert("same", "Tornado Warning")
        let plan = AlertEngine.plan(targets: [.init(label: "Home", prefs: p, alerts: [a]), .init(label: "Texas", prefs: p, alerts: [a])], prefs: prefs)
        XCTAssertEqual(plan.notify.count, 1); XCTAssertEqual(plan.notify[0].label, "Home")
    }
}

final class LockedCounter: @unchecked Sendable { private let l = NSLock(); private var n = 0
    func bump() { l.lock(); n += 1; l.unlock() }; var value: Int { l.lock(); defer { l.unlock() }; return n } }
