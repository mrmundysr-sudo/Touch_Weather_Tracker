import XCTest
@testable import TWCore

/// Integration checks against the real public services. They are skipped (not failed) when api.weather.gov is unreachable.
final class LiveSourceTests: XCTestCase {
    override func setUp() async throws {
        do { _ = try await Http.data("NWS", "https://api.weather.gov/") } catch { throw XCTSkip("network unavailable: \(error)") }
    }

    func testNwsPointForecastAndConditions() async throws {
        let (w, pt) = try await NwsSource.weather(for: Place(name: "Houston", lat: 29.76, lon: -95.37), existing: nil)
        print("NWS \(pt.gridId) \(pt.state ?? "") \(pt.city ?? "") county=\(pt.countyFips ?? "") tz=\(pt.timeZone ?? "") hourly=\(w.hourly.count) daily=\(w.daily.count) temp=\(w.conditions?.tempF ?? -999)")
        XCTAssertGreaterThan(w.hourly.count, 24); XCTAssertGreaterThanOrEqual(w.daily.count, 6); XCTAssertEqual(pt.countyFips, "48201")
    }

    func testNwsAlertsNationwide() async throws {
        let a = try await NwsSource.alertsAll()
        print("alerts nationwide=\(a.count) sample=\(a.prefix(3).map { "\($0.event)/\($0.level)/\($0.states)" })")
        XCTAssertTrue(a.allSatisfy { !$0.id.isEmpty })
    }

    func testCensus() async throws {
        let z = try await CensusSource.search("77002"); print("zip=\(z)"); XCTAssertFalse(z.isEmpty)
        let c = try await CensusSource.search("Houston, TX"); print("city=\(c.prefix(2))"); XCTAssertEqual(c.first?.label, "Houston")
        let a = try await CensusSource.search("1600 Pennsylvania Ave NW Washington DC"); print("addr=\(a.prefix(1))"); XCTAssertFalse(a.isEmpty)
        let co = try await CensusSource.counties("FL"); print("fl counties=\(co.count)"); XCTAssertGreaterThan(co.count, 60)
    }

    func testNhcAtcfOnArchivedStormUsesOwnGunzip() async throws {
        let fixes = try await NhcSource.aDeck("al102026")
        let ofcl = Atcf.latestTrack(fixes, tech: "OFCL"), gfs = Atcf.latestTrack(fixes, tech: "AVNO")
        print("rows=\(fixes.count) OFCL=\(ofcl?.initDtg ?? "-") pts=\(ofcl?.points.count ?? 0)  GFS=\(gfs?.initDtg ?? "-") pts=\(gfs?.points.count ?? 0)")
        XCTAssertNotNil(ofcl); XCTAssertNotNil(gfs)
        let best = try await NhcSource.bestTrack("al102026"); XCTAssertGreaterThan(best.count, 5)
    }

    func testNhcActive() async throws { print("active storms=\(try await NhcSource.activeStorms().count)") }

    func testBuoysAndWaterLevels() async throws {
        let b = try await NdbcSource.nearby(lat: 29.2, lon: -94.9, radiusMi: 250, maxAgeMin: 720, limit: 3)
        print("buoys=\(b.map { "\($0.id) \(Int($0.distanceMi))mi" })"); XCTAssertFalse(b.isEmpty)
        let w = await CoopsSource.nearby(lat: 29.31, lon: -94.79, radiusMi: 60, limit: 3, maxAgeMin: 180)
        print("water=\(w.map { "\($0.name) \($0.levelFtMllw) dep=\($0.departureFt ?? .nan)" })")
    }

    func testFema() async throws {
        let sh = try await FemaSource.shelters(lat: 36.79, lon: -108.69, radiusMi: 150); print("NM shelters=\(sh.count) pets=\(sh.prefix(2).map(\.pet))"); XCTAssertFalse(sh.isEmpty)
        let drc = try await FemaSource.recoveryCenters(lat: 30.4, lon: -86.5, radiusMi: 300); print("drc=\(drc.count)")
        let d = try await FemaSource.declarations(state: "FL", countyFips: "12005"); print("decl=\(d.prefix(2).map(\.declarationString))"); XCTAssertFalse(d.isEmpty)
        let m = try await BlueRoofSource.activeMissions(state: nil); print("blue roof missions=\(m.count)")
    }

    func testUtilityAndOutage() async throws {
        let u = try await UtilitySource.at(lat: 29.76, lon: -95.37); print("utility=\(u.map(\.name))"); XCTAssertFalse(u.isEmpty)
        let o = try await MassOutageSource.county("48201"); print("outage=\(String(describing: o?.customersOut))"); XCTAssertNotNil(o)
    }

    func testReconProducts() async throws {
        let r = await ReconSource.summary(stormId: "al092026", centerLat: 29.8, centerLon: -86.8, hours: 24 * 400, radiusKm: 5000)
        print("recon hdob=\(r?.hdobCount ?? 0) vortex=\(String(describing: r?.vortex?.minSlpMb)) drops=\(r?.dropsondes.count ?? 0)")
    }

    func testStatesCrossed() async throws {
        let over = try await CensusSource.statesCrossed([(25, -90), (27, -90)]), landfall = try await CensusSource.statesCrossed([(27, -90), (31, -91)])
        print("gulf-only=\(over) landfall=\(landfall)"); XCTAssertTrue(over.isEmpty); XCTAssertTrue(landfall.contains("LA"))
    }
}
