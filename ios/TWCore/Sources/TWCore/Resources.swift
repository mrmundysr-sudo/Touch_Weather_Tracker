import Foundation

public enum PetStatus: String, Codable, Sendable { case allowed, notAllowed, unknown }

public struct Shelter: Codable, Equatable, Identifiable, Sendable {
    public var id: String, name: String, address: String, city: String, state: String, zip: String
    public var lat: Double, lon: Double, distanceMi: Double, status: String
    public var phone: String?, org: String?, pet: PetStatus, petText: String?
    public var population: Int?, capacity: Int?, wheelchair: Bool?
}
public struct RecoveryCenter: Codable, Equatable, Identifiable, Sendable {
    public var id: String, name: String, address: String, city: String, state: String
    public var lat: Double, lon: Double, distanceMi: Double, status: String
    public var hours: String?, disasterNumber: Int?, updatedMs: Int64?, notes: String?
}
public struct Declaration: Codable, Equatable, Sendable {
    public var disasterNumber: Int, declarationString: String, title: String, incidentType: String, state: String, area: String
    public var declaredMs: Int64?, incidentBeginMs: Int64?, incidentEndMs: Int64?
    public var individualAssistance: Bool, householdProgram: Bool, publicAssistance: Bool, hazardMitigation: Bool
    public var iaDeadlineMs: Int64?, closedOut: Bool
}
public struct BlueRoofMission: Codable, Equatable, Sendable {
    public var disasterNumber: Int, incident: String, state: String, agency: String
    public var receivedMs: Int64?, endMs: Int64?, summary: String
}

/// FEMA ESF-6 shelter feed (NSS), Disaster Recovery Centers, and OpenFEMA declarations. All public FEMA services.
public enum FemaSource {
    static let S = "FEMA"
    static let gis = "https://gis.fema.gov/arcgis/rest/services"
    static let openfema = "https://www.fema.gov/api/open/v2"

    /// Pet policy is only reported when the feed says so — never inferred from missing or ambiguous data.
    public static func petStatus(code: String?, desc: String?) -> PetStatus {
        let t = [code, desc].compactMap { $0 }.joined(separator: " ").trimmingCharacters(in: .whitespaces).uppercased()
        if t.isEmpty { return .unknown }
        if t.range(of: #"\b(NOT ALLOWED|NO PETS|NONE|NOT ACCEPT|NOT PERMITTED|PROHIBIT)"#, options: .regularExpression) != nil || t == "N" || t == "NO" { return .notAllowed }
        if t.range(of: #"\b(ALLOWED|ACCEPT|PETS OK|YES|SERVICE ANIMALS?|CO-?LOCAT|PET FRIENDLY)"#, options: .regularExpression) != nil || t == "Y" { return .allowed }
        return .unknown
    }

    static func spatial(_ layer: String, lat: Double, lon: Double, radiusMi: Double) async throws -> [JSON] {
        let url = "\(gis)/\(layer)/query?where=\(urlEncode("1=1"))&geometry=\(lon),\(lat)&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects&distance=\(radiusMi)&units=esriSRUnit_StatuteMile&outFields=*&returnGeometry=true&outSR=4326&resultRecordCount=200&f=json"
        let root = try await Http.json(S, url)
        if !root["error"].isNull { throw SourceError(S, "FEMA service error") }
        return root["features"].array ?? []
    }

    public static func shelters(lat: Double, lon: Double, radiusMi: Double = 75) async throws -> [Shelter] {
        let open: Set<String> = ["OPEN", "OPEN/ACTIVE", "STANDBY", "ACTIVE", "FULL"]
        return try await spatial("NSS/FEMA_NSS/FeatureServer/0", lat: lat, lon: lon, radiusMi: radiusMi).compactMap { f -> Shelter? in
            let a = f["attributes"], g = f["geometry"]
            guard let sLat = g["y"].double ?? a["latitude"].double, let sLon = g["x"].double ?? a["longitude"].double, let id = a["shelter_id"].clean else { return nil }
            let status = a["shelter_status"].clean ?? "OPEN"
            guard open.contains(status.uppercased()) else { return nil }
            let petCode = a["pet_accommodations_code"].clean, petDesc = a["pet_accommodations_desc"].clean
            let wheel = a["wheelchair_accessible"].clean.map { $0.uppercased() == "Y" || $0.uppercased() == "YES" }
            return Shelter(id: id, name: a["shelter_name"].clean ?? "Shelter", address: a["address_1"].clean ?? a["address"].clean ?? "", city: a["city"].clean ?? "",
                           state: a["state"].clean ?? "", zip: a["zip"].clean ?? "", lat: sLat, lon: sLon, distanceMi: Geo.distanceMiles(lat, lon, sLat, sLon), status: status,
                           phone: a["org_main_phone"].clean ?? a["org_hotline_phone"].clean ?? a["org_other_phone"].clean, org: a["org_organization_name"].clean ?? a["org_name"].clean,
                           pet: petStatus(code: petCode, desc: petDesc), petText: [petCode, petDesc].compactMap { $0 }.joined(separator: " – ").nilIfEmpty,
                           population: a["total_population"].int, capacity: a["evacuation_capacity"].int ?? a["post_impact_capacity"].int, wheelchair: wheel)
        }.sorted { $0.distanceMi < $1.distanceMi }.prefix(40).map { $0 }
    }

    public static func recoveryCenters(lat: Double, lon: Double, radiusMi: Double = 100) async throws -> [RecoveryCenter] {
        try await spatial("FEMA/DRC/FeatureServer/0", lat: lat, lon: lon, radiusMi: radiusMi).compactMap { f -> RecoveryCenter? in
            let a = f["attributes"], g = f["geometry"]
            guard let cLat = g["y"].double ?? a["latitude"].double, let cLon = g["x"].double ?? a["longitude"].double, let id = a["drc_id"].clean ?? a["objectid"].clean else { return nil }
            let city = (a["city"].clean ?? "").lowercased().split(separator: " ").map { $0.prefix(1).uppercased() + $0.dropFirst() }.joined(separator: " ")
            return RecoveryCenter(id: id, name: a["drc_name"].clean ?? "Disaster Recovery Center",
                                  address: [a["street_1"].clean, a["street_2"].clean].compactMap { $0 }.joined(separator: ", ").replacingOccurrences(of: "&#43;", with: "+"),
                                  city: city, state: a["state"].clean ?? "", lat: cLat, lon: cLon, distanceMi: Geo.distanceMiles(lat, lon, cLat, cLon),
                                  status: a["status"].clean ?? "Unknown", hours: [a["days_open"].clean, a["hours"].clean].compactMap { $0 }.joined(separator: " · ").nilIfEmpty,
                                  disasterNumber: a["primary_disaster"].int, updatedMs: a["last_report_date"].double.map { Int64($0) }, notes: a["notes"].clean)
        }.sorted { $0.distanceMi < $1.distanceMi }.prefix(30).map { $0 }
    }

    /// Declarations touching a county (by FIPS), most recent first, closed-out ones excluded.
    public static func declarations(state: String, countyFips: String?) async throws -> [Declaration] {
        var filter = "state eq '\(state)'"
        if let c = countyFips, c.count == 5 { filter += " and (fipsCountyCode eq '\(c.suffix(3))' or fipsCountyCode eq '000')" }
        let since = Calendar(identifier: .gregorian).date(byAdding: .year, value: -2, to: Date()) ?? Date()
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        filter += " and declarationDate ge '\(f.string(from: since))T00:00:00.000z'"
        let url = "\(openfema)/DisasterDeclarationsSummaries?$filter=\(urlEncode(filter))&$orderby=declarationDate%20desc&$top=60"
        var seen = Set<String>()
        return (try await Http.json(S, url)["DisasterDeclarationsSummaries"].array ?? []).compactMap { o -> Declaration? in
            guard let n = o["disasterNumber"].int else { return nil }
            let ds = o["femaDeclarationString"].clean ?? ""
            let closed = o["disasterCloseoutDate"].clean != nil
            guard !closed, ds.hasPrefix("DR") || ds.hasPrefix("EM") else { return nil }
            let area = o["designatedArea"].clean ?? ""
            guard seen.insert("\(n)|\(area)").inserted else { return nil }
            return Declaration(disasterNumber: n, declarationString: ds, title: o["declarationTitle"].clean ?? "", incidentType: o["incidentType"].clean ?? "", state: o["state"].clean ?? "",
                               area: area, declaredMs: Units.parseIso(o["declarationDate"].clean), incidentBeginMs: Units.parseIso(o["incidentBeginDate"].clean),
                               incidentEndMs: Units.parseIso(o["incidentEndDate"].clean), individualAssistance: o["iaProgramDeclared"].bool == true,
                               householdProgram: o["ihProgramDeclared"].bool == true, publicAssistance: o["paProgramDeclared"].bool == true,
                               hazardMitigation: o["hmProgramDeclared"].bool == true, iaDeadlineMs: Units.parseIso(o["lastIAFilingDate"].clean), closedOut: closed)
        }.prefix(20).map { $0 }
    }
}

/// Operation Blue Roof status. The USACE site blocks automated access, so OpenFEMA mission assignments are used: a USACE (COE-*)
/// assignment mentioning roofing with a current period of performance. This shows a mission EXISTS — county eligibility is confirmed with USACE.
public enum BlueRoofSource {
    public static func activeMissions(state: String?) async throws -> [BlueRoofMission] {
        let since = Calendar(identifier: .gregorian).date(byAdding: .day, value: -180, to: Date()) ?? Date()
        let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX")
        var filter = "startswith(agencyId,'COE') and dateReceived ge '\(f.string(from: since))T00:00:00.000z'"
        if let s = state { filter += " and stt eq '\(s)'" }
        let url = "\(FemaSource.openfema)/MissionAssignments?$filter=\(urlEncode(filter))&$orderby=dateReceived%20desc&$top=400"
        let now = Units.nowMs()
        var seen = Set<String>()
        return (try await Http.json("OpenFEMA", url)["MissionAssignments"].array ?? []).compactMap { o -> BlueRoofMission? in
            let text = (o["statementOfWork"].clean ?? "") + " " + (o["assistanceRequested"].clean ?? "")
            guard text.range(of: "roof", options: .caseInsensitive) != nil, let n = o["disasterNumber"].int else { return nil }
            let end = Units.parseIso(o["maPopEndDate"].clean ?? o["popEndDate"].clean)
            if let e = end, e < now - 14 * 86_400_000 { return nil }
            let st = o["stt"].clean ?? ""
            guard seen.insert("\(n)|\(st)").inserted else { return nil }
            return BlueRoofMission(disasterNumber: n, incident: o["incidentName"].clean ?? "", state: st, agency: o["agency"].clean ?? "",
                                   receivedMs: Units.parseIso(o["dateReceived"].clean), endMs: end, summary: String((o["statementOfWork"].clean ?? "").prefix(260)))
        }
    }
}

/// Likely local electric utility from public electric retail service-territory polygons (HIFLD data, public domain; third-party-hosted mirror).
public enum UtilitySource {
    static let S = "Utility territories"
    static let layer = "https://services3.arcgis.com/OYP7N6mAJJCyH6hd/arcgis/rest/services/Electric_Retail_Service_Territories_HIFLD/FeatureServer/0"

    public static func at(lat: Double, lon: Double) async throws -> [UtilityChoice] {
        let url = "\(layer)/query?geometry=\(lon),\(lat)&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects&outFields=NAME,STATE,TELEPHONE,WEBSITE,TYPE,CUSTOMERS&returnGeometry=false&f=json"
        let rows = (try await Http.json(S, url)["features"].array ?? []).compactMap { e -> (UtilityChoice, Double)? in
            let a = e["attributes"]
            guard let name = a["NAME"].clean else { return nil }
            let notAvail = { (s: String?) -> String? in s.flatMap { $0.uppercased() == "NOT AVAILABLE" ? nil : $0 } }
            let web = notAvail(a["WEBSITE"].clean).map { $0.lowercased().hasPrefix("http") ? $0 : "https://\($0)" }
            let pretty = name.lowercased().split(separator: " ").map { w -> String in
                (w.count <= 3 && w.allSatisfy(\.isLetter) && w != "of" && w != "the") ? w.uppercased() : w.prefix(1).uppercased() + w.dropFirst()
            }.joined(separator: " ")
            return (UtilityChoice(name: pretty, phone: notAvail(a["TELEPHONE"].clean), website: web, state: a["STATE"].clean), a["CUSTOMERS"].double ?? 0)
        }
        return rows.sorted { $0.1 > $1.1 }.map { $0.0 }
    }
}

public struct OutageCounty: Codable, Equatable, Sendable {
    public var fips: String, customersOut: Int, customersTracked: Int, pct: Double, incidents: Int
    public var utilities: [OutageUtility], updatedMs: Int64?, asOfMs: Int64?, liveStatus: String?, behindMin: Int?
}
public struct OutageUtility: Codable, Equatable, Sendable { public var name: String; public var customersOut: Int }
public struct OutageState: Codable, Equatable, Sendable { public var abbr: String, name: String; public var customersOut: Int, countiesAffected: Int }
public struct OutageNational: Codable, Equatable, Sendable {
    public var customersOut: Int, incidents: Int, counties: Int, updatedMs: Int64?, asOfMs: Int64?, stale: Bool
    public var sourcesOk: Int, sources: Int, trackedCustomers: Int64, notLocalizedOut: Int, states: [OutageState]
}

/// MassOutage API (CC BY 4.0, unofficial & supplemental). Attribution: "MassOutage (massoutage.com)".
public enum MassOutageSource {
    static let S = "MassOutage"
    static let base = "https://massoutage.com/api/v1"

    public static func county(_ fips: String) async throws -> OutageCounty? {
        let root = try await Http.json(S, "\(base)/live/counties/\(fips)")
        let d = root["data"]
        guard !d.isNull else { return nil }
        let live = root["meta"]["live"]
        return OutageCounty(fips: fips, customersOut: d["customers_out"].int ?? 0, customersTracked: d["customers_tracked"].int ?? 0, pct: d["pct"].double ?? 0,
                            incidents: d["incident_count"].int ?? 0,
                            utilities: (d["utilities"].array ?? []).compactMap { u in u["name"].clean.map { OutageUtility(name: $0, customersOut: u["customers_out"].int ?? 0) } },
                            updatedMs: Units.parseIso(d["updated_at"].clean), asOfMs: Units.parseIso(d["as_of"].clean), liveStatus: live["status"].clean, behindMin: live["behind_min"].int)
    }

    public static func national() async throws -> OutageNational? {
        let d = try await Http.json(S, "\(base)/live/summary")["data"]
        let n = d["national"], c = d["coverage"]
        guard !n.isNull else { return nil }
        return OutageNational(customersOut: n["customers_out"].int ?? 0, incidents: n["incident_count"].int ?? 0, counties: n["county_count"].int ?? 0,
                              updatedMs: Units.parseIso(n["updated_at"].clean), asOfMs: Units.parseIso(n["as_of"].clean), stale: n["stale"].bool == true,
                              sourcesOk: c["sources_ok"].int ?? 0, sources: c["sources"].int ?? 0, trackedCustomers: Int64(c["tracked_customers"].double ?? 0),
                              notLocalizedOut: n["unmapped_out"].int ?? 0,
                              states: (d["states"].array ?? []).compactMap { s in s["abbr"].clean.map { OutageState(abbr: $0, name: s["name"].clean ?? "", customersOut: s["customers_out"].int ?? 0, countiesAffected: s["county_count"].int ?? 0) } })
    }
}

extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
