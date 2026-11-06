import XCTest
@testable import TWCore

final class ParserTests: XCTestCase {
    func testAtcfCoordinates() {
        XCTAssertEqual(Atcf.coord("342N")!, 34.2, accuracy: 1e-9)
        XCTAssertEqual(Atcf.coord("713W")!, -71.3, accuracy: 1e-9)
        XCTAssertEqual(Atcf.coord("125S")!, -12.5, accuracy: 1e-9)
        XCTAssertNil(Atcf.coord(""))
    }

    func testAtcfLatestTrack() {
        let txt = """
        AL, 10, 2026102000, 03, AVNO,   0, 342N,  713W,  40, 1005, XX,  34, NEQ,  215,    0,  107,  206, 1013,  161,  74,   0,   0,
        AL, 10, 2026102000, 03, AVNO,   6, 349N,  709W,  39, 1002, XX,  34, NEQ,  173,    0,  129,  171, 1010,  134,  79,   0,   0,
        AL, 10, 2026101918, 03, AVNO,   0, 330N,  720W,  35, 1008, XX,  34, NEQ,  100,    0,  100,  100, 1013,  100,  70,   0,   0,
        """
        let t = Atcf.latestTrack(Atcf.parse(txt), tech: "AVNO")!
        XCTAssertEqual(t.initDtg, "2026102000"); XCTAssertEqual(t.points.count, 2); XCTAssertEqual(t.points[1].lat, 34.9, accuracy: 1e-9)
    }

    func testGzipInflatesRealAtcfFile() throws {
        let gz = Data(base64Encoded: AtcfFixture.gzBase64)!
        let out = String(decoding: try Gzip.decompress(gz), as: UTF8.self)
        XCTAssertEqual(out.trimmingCharacters(in: .newlines), AtcfFixture.text.trimmingCharacters(in: .newlines))   // multi-line literals drop the final newline
        let fixes = Atcf.parse(out)
        XCTAssertGreaterThan(fixes.count, 100)
        XCTAssertNotNil(Atcf.latestTrack(fixes, tech: "CARQ"))
    }

    func testGzipRejectsGarbage() { XCTAssertThrowsError(try Gzip.decompress(Data([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20]))) }

    func testHdob() {
        let body = """
        URNT15 KWBC 061646
        NOAA3 WXWXA TRAIN              HDOB 12 20261106
        163700 2830N 08205W 6961 03195 0117 +082 -185 257007 010 /// /// 03
        163730 2829N 08205W 6961 03195 9985 +082 -185 257045 052 048 012 03
        """
        let issued = Int64(DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(identifier: "UTC"), year: 2026, month: 11, day: 6, hour: 16, minute: 46).date!.timeIntervalSince1970 * 1000)
        let h = ReconSource.parseHdob(body, issuedMs: issued)
        XCTAssertEqual(h.count, 2)
        XCTAssertEqual(h[0].peakFlWindKt, 10); XCTAssertNil(h[0].sfmrWindKt)
        XCTAssertEqual(h[0].extrapSlpMb!, 1011.7, accuracy: 0.01)
        XCTAssertEqual(h[1].sfmrWindKt, 48); XCTAssertEqual(h[1].extrapSlpMb!, 998.5, accuracy: 0.01)
        XCTAssertEqual(h[0].lat, 28.5, accuracy: 0.01); XCTAssertEqual(h[0].lon, -82.0833, accuracy: 0.01)
    }

    func testVdm() {
        let txt = "URNT12 KNHC 092326\nVORTEX DATA MESSAGE   AL092026\nA. 09/22:47:30Z\nB. 29.79 deg N 086.76 deg W\nC. 700 mb 2861 m\nD. 972 mb\nE. 050 deg 18 kt\nMAX FL WIND 128 KT 068 / 16 NM 18:37:00Z\n"
        let now = Int64(DateComponents(calendar: Calendar(identifier: .gregorian), timeZone: TimeZone(identifier: "UTC"), year: 2026, month: 10, day: 10, hour: 1).date!.timeIntervalSince1970 * 1000)
        let v = ReconSource.parseVdm(txt, nowMs: now)!
        XCTAssertEqual(v.stormId, "al092026"); XCTAssertEqual(v.minSlpMb, 972); XCTAssertEqual(v.maxFlWindKt, 128); XCTAssertEqual(v.lon, -86.76, accuracy: 1e-9)
    }

    func testNdbcMissingValuesAreNil() {
        let txt = "#STN LAT LON YYYY MM DD hh mm WDIR WSPD GST WVHT DPD APD MWD PRES PTDY ATMP WTMP DEWP VIS TIDE\n" +
            "42019 27.913 -95.352 2026 11 06 17 50 90 7.0 9.0 1.2 6.0 4.8 MM 1018.2 MM 24.1 25.0 MM MM MM\n" +
            "ZZZZ1 10.0 -50.0 2026 11 06 17 50 MM MM MM MM MM MM MM MM MM MM MM MM MM MM\n"
        let b = NdbcSource.parse(txt)
        XCTAssertEqual(b.count, 2); XCTAssertEqual(b[0].windMph!, 15.66, accuracy: 0.05); XCTAssertEqual(b[0].waterTempF!, 77.0, accuracy: 0.2)
        XCTAssertNil(b[1].windMph); XCTAssertNil(b[1].pressureInHg)
    }

    func testPetStatusNeverInferredFromMissing() {
        XCTAssertEqual(FemaSource.petStatus(code: nil, desc: nil), .unknown)
        XCTAssertEqual(FemaSource.petStatus(code: " ", desc: nil), .unknown)
        XCTAssertEqual(FemaSource.petStatus(code: "Y", desc: nil), .allowed)
        XCTAssertEqual(FemaSource.petStatus(code: "N", desc: nil), .notAllowed)
        XCTAssertEqual(FemaSource.petStatus(code: nil, desc: "Pets not allowed"), .notAllowed)
        XCTAssertEqual(FemaSource.petStatus(code: "X", desc: "see staff"), .unknown)
    }

    func testAlertLevelsAndLifeSafety() {
        XCTAssertEqual(NwsSource.levelOf("Tornado Warning"), .warning); XCTAssertEqual(NwsSource.levelOf("Hurricane Watch"), .watch)
        XCTAssertEqual(NwsSource.levelOf("Wind Advisory"), .advisory); XCTAssertEqual(NwsSource.levelOf("Rip Current Statement"), .statement)
        XCTAssertTrue(NwsSource.isLifeSafety(event: "Flash Flood Warning", severity: "Severe", tornadoThreat: nil))
        XCTAssertFalse(NwsSource.isLifeSafety(event: "Wind Advisory", severity: "Minor", tornadoThreat: nil))
    }

    func testAlertParsingFromNwsShapedJSON() throws {
        let json = """
        {"features":[{"id":"x1","geometry":{"type":"Polygon","coordinates":[[[-95,29],[-94,29],[-94,30],[-95,29]]]},"properties":{"id":"urn:x1","event":"Tornado Warning","status":"Actual","severity":"Extreme","urgency":"Immediate","certainty":"Observed","headline":"H","description":"D","areaDesc":"Harris, TX","sent":"2026-11-06T17:00:00-06:00","expires":"2026-11-06T17:45:00-06:00","senderName":"NWS Houston","messageType":"Alert","geocode":{"SAME":["048201"],"UGC":["TXC201"]}}},
        {"properties":{"id":"urn:test","event":"Tornado Warning","status":"Test"}}]}
        """
        let alerts = NwsSource.parseAlerts(try JSON.parse(json))
        XCTAssertEqual(alerts.count, 1)                       // Test messages are excluded
        XCTAssertEqual(alerts[0].level, .warning); XCTAssertTrue(alerts[0].lifeSafety); XCTAssertEqual(alerts[0].states, ["TX"]); XCTAssertEqual(alerts[0].polygons.count, 1)
    }

    func testGeometry() {
        let ring: [[Double]] = [[-96, 29], [-94, 29], [-94, 31], [-96, 31], [-96, 29]]
        XCTAssertTrue(Geo.inRing(30, -95, ring)); XCTAssertFalse(Geo.inRing(32, -95, ring))
        XCTAssertLessThan(Geo.minDistanceToPath(30, -95, [(25, -95), (35, -95)])!.miles, 1)
    }

    func testIsoDurationsAndTime() {
        XCTAssertEqual(NwsSource.durationMs("PT6H"), 6 * 3_600_000); XCTAssertEqual(NwsSource.durationMs("P1DT6H"), 30 * 3_600_000); XCTAssertEqual(NwsSource.durationMs("PT30M"), 1_800_000)
        XCTAssertEqual(Units.parseIso("2026-11-06T17:00:00+00:00"), Units.parseIso("2026-11-06T12:00:00-05:00"))
        XCTAssertNotNil(Units.parseIso("2026-11-06T17:55:18.347Z"))
    }

    func testPlaceCityState() {
        var p = Place(name: "162 N Main St", lat: 1, lon: 2)
        XCTAssertEqual(p.cityState, "162 N Main St"); p.city = "Montpelier"; XCTAssertEqual(p.cityState, "Montpelier"); p.state = "VT"; XCTAssertEqual(p.cityState, "Montpelier, VT")
    }

    func testCensusHelpers() {
        XCTAssertEqual(CensusSource.classify("77002"), "zip"); XCTAssertEqual(CensusSource.classify("1600 Pennsylvania Ave NW"), "address"); XCTAssertEqual(CensusSource.classify("Houston, TX"), "place")
        XCTAssertEqual(CensusSource.cleanPlaceName("Houston city"), "Houston"); XCTAssertEqual(CensusSource.cleanPlaceName("Spring CDP"), "Spring")
    }

    func testStormReportOmitsMissingAndLabelsKinds() {
        var place = Place(name: "Pensacola", lat: 30.42, lon: -87.22); place.timeZone = "America/Chicago"
        let storm = ActiveStorm(id: "al092026", bin: "AT4", name: "Isaias", classification: "HU", intensityKt: 100, pressureMb: 960, lat: 28.5, lon: -87.0, moveDirDeg: 350, moveSpeedMph: 10,
                                lastUpdateMs: 1, advisoryNumber: "20", advisoryIssuedMs: 1, publicAdvisoryUrl: "https://www.nhc.noaa.gov/text/MIATCPAT4.shtml")
        let fixes = [AtcfFix(basin: "AL", cyclone: 9, dtg: "2026100900", tech: "OFCL", tauHours: 0, lat: 28.5, lon: -87.0, vmaxKt: 100, mslpMb: 960, type: nil),
                     AtcfFix(basin: "AL", cyclone: 9, dtg: "2026100900", tech: "OFCL", tauHours: 24, lat: 30.4, lon: -87.5, vmaxKt: 90, mslpMb: 965, type: nil)]
        let rep = StormReportBuilder.build(StormInputs(place: place, storm: storm, officialTrack: Atcf.latestTrack(fixes, tech: "OFCL"), coneRings: [], advisoryHighlights: [], placeAlerts: [], buoys: [], waterLevels: [], recon: nil, weather: nil))
        let all = rep.sections.flatMap(\.items)
        XCTAssertTrue(all.contains { $0.kind == .observed && $0.source == "NHC advisory" })
        XCTAssertTrue(all.contains { $0.kind == .forecast && $0.text.contains("closest approach") })
        XCTAssertTrue(rep.sections.first { $0.title.hasPrefix("What nearby") }!.items.isEmpty)     // no invented observations
        XCTAssertTrue(rep.threatens); XCTAssertEqual(StormReportBuilder.ktToMph(100), 115)
    }

    func testOutlookSummaryExtractsDayOne() {
        let t = "Hazardous Weather Outlook\nNational Weather Service Burlington VT\n\n.DAY ONE...Today and Tonight\n\nNo hazardous weather is expected at this time.\n\n.DAYS TWO THROUGH SEVEN...Saturday through Thursday\n\nRain.\n\n$$"
        XCTAssertEqual(NwsSource.outlookSummary(t), "No hazardous weather is expected at this time.")
    }
}
