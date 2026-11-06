import SwiftUI
import TWCore

struct ResourcesView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let st = model.resources
        ScrollView {
            if let p = model.selected {
                VStack(spacing: 0) {
                    Text("Help near \(p.cityState). Listings come from official sources and are only shown as open when the source says so — always call to confirm.").font(.subheadline).foregroundStyle(Tw.onSurfaceMuted).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)

                    Section_(title: "Power outage") {
                        NavigationLink(value: Route.power) {
                            HStack(spacing: 12) {
                                Image(systemName: "bolt.slash").font(.title2).foregroundStyle(Tw.primary)
                                VStack(alignment: .leading) { Text("Your electric utility, outage map and report-an-outage").font(.subheadline.weight(.semibold)); Text(p.utility.map { "Using \($0.name)" } ?? "We'll identify the likely utility for \(p.cityState).").font(.caption) }
                                Spacer(); Image(systemName: "chevron.right")
                            }.card()
                        }.buttonStyle(.plain)
                    }

                    if st.loading && st.bundle == nil { LoadingView(label: "Looking up FEMA and shelter information…") }
                    let b = st.bundle

                    Section_(title: "FEMA disaster assistance") {
                        if let b {
                            if let e = b.declarationsError { ErrorState(message: e) { model.loadResources() } }
                            else if b.declarations.isEmpty {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("No open federal disaster declaration found for \(p.countyName ?? p.cityState).").font(.subheadline.weight(.semibold))
                                    Text("FEMA assistance requires a presidential declaration for your area. If you were affected by a recent disaster, check with your state or local emergency management and your insurer.").font(.subheadline)
                                }.card()
                            } else { ForEach(Array(b.declarations.prefix(6).enumerated()), id: \.offset) { _, d in declarationCard(d) } }
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text("How to apply").font(.subheadline.weight(.semibold))
                            Text("Individuals and households can apply for FEMA assistance when their county is designated for Individual Assistance: online at DisasterAssistance.gov, by phone at 1-800-621-3362, or at a Disaster Recovery Center. Eligibility, programs and deadlines depend on the declaration and your circumstances — FEMA makes the determination.").font(.subheadline)
                            LinkButton(title: "DisasterAssistance.gov", url: "https://www.disasterassistance.gov")
                            Button { dial("18006213362") } label: { Label("Call FEMA", systemImage: "phone").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
                        }.card()
                        if let b { SourceLine(source: "OpenFEMA declarations", updatedMs: b.fetchedAtMs, tz: p.timeZone) }
                    }

                    Section_(title: "Shelters") {
                        if let b {
                            if let e = b.sheltersError { ErrorState(message: e) { model.loadResources() } }
                            else if b.shelters.isEmpty {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("No open shelters reported within 75 miles.").font(.subheadline.weight(.semibold))
                                    Text("This feed lists shelters opened through FEMA/American Red Cross reporting. Local and state shelters may not be listed — call 211 or your county emergency management office.").font(.subheadline)
                                    Button { dial("211") } label: { Label("Call 211", systemImage: "phone").frame(minHeight: 44) }.buttonStyle(.bordered)
                                }.card()
                            } else {
                                petLegend
                                ForEach(b.shelters.prefix(15)) { shelterCard($0) }
                                if b.shelters.count > 15 { Text("Showing the nearest 15 of \(b.shelters.count).").font(.caption) }
                            }
                            SourceLine(source: "FEMA ESF-6 shelter feed (reported status; list may be incomplete)", updatedMs: b.fetchedAtMs, tz: p.timeZone)
                        }
                    }

                    Section_(title: "Disaster Recovery Centers") {
                        if let b {
                            if let e = b.centersError { ErrorState(message: e) { model.loadResources() } }
                            else if b.centers.isEmpty { VStack(alignment: .leading, spacing: 4) { Text("No open Disaster Recovery Centers within 100 miles.").font(.subheadline.weight(.semibold)); Text("Recovery centers open only after a declaration. Check DisasterAssistance.gov for new locations.").font(.subheadline) }.card() }
                            else { ForEach(b.centers.prefix(10)) { centerCard($0) } }
                            SourceLine(source: "FEMA Disaster Recovery Center feed", updatedMs: b.fetchedAtMs, tz: p.timeZone)
                        }
                    }

                    Section_(title: "Food, water & charging") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Not verified in this app").font(.subheadline.weight(.semibold))
                            Text("There is no single official, up-to-date nationwide feed of distribution points, so Touch Weather does not list individual sites as open. Shelters and Disaster Recovery Centers above often provide supplies and charging. To find current sites, use these and call ahead:").font(.subheadline)
                            Button { dial("211") } label: { Label("Dial 211 — local help line", systemImage: "phone").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
                            LinkButton(title: "Find a food bank (Feeding America)", url: "https://www.feedingamerica.org/find-your-local-foodbank")
                            LinkButton(title: "American Red Cross: find help", url: "https://www.redcross.org/get-help.html")
                            LinkButton(title: "Ready.gov: after a disaster", url: "https://www.ready.gov")
                        }.card()
                    }

                    Section_(title: "USACE Operation Blue Roof") { blueRoofCard(p, st) }
                    Spacer(minLength: 24)
                }
            } else { EmptyState(icon: "mappin.circle", title: "Add a place", message: "Resources are shown for the place you select.").padding(.top, 60) }
        }
        .navigationTitle("Resources")
        .refreshable { model.loadResources() }
        .task(id: model.selected?.id) { model.loadResources() }
    }

    private var petLegend: some View {
        HStack(spacing: 14) {
            HStack { PetBadge(status: .allowed); Text("Pets allowed").font(.caption2) }
            HStack { PetBadge(status: .notAllowed); Text("Not allowed").font(.caption2) }
            HStack { PetBadge(status: .unknown); Text("Not reported").font(.caption2) }
        }
    }

    private func shelterCard(_ s: Shelter) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.name).font(.subheadline.weight(.semibold))
                    Text([s.address, s.city, s.state, s.zip].filter { !$0.isEmpty }.joined(separator: ", ")).font(.subheadline)
                    Text(String(format: "%.1f mi · Reported status: %@", s.distanceMi, s.status.capitalized)).font(.caption)
                    if let o = s.org { Text(o).font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
                    if s.population != nil || s.capacity != nil { Text([s.population.map { "Occupants: \($0)" }, s.capacity.map { "Capacity: \($0)" }].compactMap { $0 }.joined(separator: " · ")).font(.caption) }
                    if let w = s.wheelchair { Text(w ? "Wheelchair accessible (reported)" : "Wheelchair access not reported as accessible").font(.caption) }
                }
                Spacer()
                VStack { PetBadge(status: s.pet); Text(s.pet == .allowed ? "Pets OK" : s.pet == .notAllowed ? "No pets" : "Pets: not reported").font(.caption2) }
            }
            Text("Availability can change quickly — call to confirm before traveling. Shelter hours and capacity are as last reported to FEMA.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
            HStack {
                Button { openDirections(lat: s.lat, lon: s.lon, label: s.name) } label: { Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond").frame(minHeight: 44) }.buttonStyle(.bordered)
                if let ph = s.phone { Button { dial(ph) } label: { Label("Call", systemImage: "phone").frame(minHeight: 44) }.buttonStyle(.bordered) }
                else { Text("No phone listed — call 211").font(.caption) }
            }
        }.card()
    }

    private func centerCard(_ c: RecoveryCenter) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(c.name).font(.subheadline.weight(.semibold))
            Text([c.address, c.city, c.state].filter { !$0.isEmpty }.joined(separator: ", ")).font(.subheadline)
            Text(String(format: "%.1f mi · Reported status: %@", c.distanceMi, c.status) + (c.disasterNumber.map { " · DR-\($0)" } ?? "")).font(.caption)
            if let h = c.hours { Text("Hours: \(h)").font(.caption) }
            if let u = c.updatedMs { Text("Last reported \(Units.ago(u))").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
            HStack {
                Button { openDirections(lat: c.lat, lon: c.lon, label: c.name) } label: { Label("Directions", systemImage: "arrow.triangle.turn.up.right.diamond").frame(minHeight: 44) }.buttonStyle(.bordered)
                Button { dial("18006213362") } label: { Label("Call FEMA to confirm", systemImage: "phone").frame(minHeight: 44) }.buttonStyle(.bordered)
            }
        }.card()
    }

    private func declarationCard(_ d: Declaration) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(d.declarationString) · \(d.title.capitalized)").font(.subheadline.weight(.semibold))
            Text("\(d.area) · declared \(d.declaredMs.map { Units.shortDate($0) } ?? "—")").font(.caption)
            let programs = [d.individualAssistance ? "Individual Assistance" : nil, d.householdProgram ? "Individuals & Households Program" : nil, d.publicAssistance ? "Public Assistance (governments & nonprofits)" : nil, d.hazardMitigation ? "Hazard Mitigation" : nil].compactMap { $0 }
            Text(programs.isEmpty ? "Programs for this area haven't been announced yet." : "Declared programs: \(programs.joined(separator: " · "))").font(.subheadline)
            if let dl = d.iaDeadlineMs, d.individualAssistance || d.householdProgram { Text("Registration deadline: \(Units.shortDate(dl))").font(.subheadline.weight(.semibold)).foregroundStyle(Tw.watch) }
            Text("Eligibility is decided by FEMA and varies by declaration, county and your situation.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
        }.card()
    }

    private func blueRoofCard(_ p: Place, _ st: ResourcesState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if st.blueRoofError != nil { Text("Couldn't check for active missions right now.").font(.subheadline.weight(.semibold)) }
            else if let ms = st.blueRoof {
                if ms.isEmpty {
                    Text("No active Blue Roof mission found for \(p.state ?? "your state").").font(.subheadline.weight(.semibold))
                    Text("Operation Blue Roof installs free temporary plastic sheeting on storm-damaged roofs, but only after FEMA assigns the U.S. Army Corps of Engineers a mission for a declared disaster. No crew will respond unless a mission is active for your county.").font(.subheadline)
                } else {
                    Text("A FEMA mission for temporary roofing exists in \(ms[0].state).").font(.subheadline.weight(.semibold))
                    ForEach(Array(ms.prefix(3).enumerated()), id: \.offset) { _, m in Text("DR-\(m.disasterNumber) \(m.incident)" + (m.endMs.map { " · period through \(Units.shortDate($0))" } ?? "")).font(.subheadline) }
                    Text("This shows a mission assignment exists — not that your county or home is eligible. Confirm eligibility and sign up with USACE; apply for FEMA assistance too.").font(.subheadline)
                }
            } else { Text("Checking for an active mission…").font(.subheadline.weight(.semibold)) }
            LinkButton(title: "USACE Blue Roof", url: "https://www.usace.army.mil/Missions/Emergency-Operations/Blue-Roof/")
            Button { dial("18886978322") } label: { Label("Call 1-888-ROOF-BLU", systemImage: "phone").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
            Text("Source: OpenFEMA mission assignments (USACE). The USACE site is the authority on eligibility and the sign-up process.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
        }.card()
    }
}

struct PowerView: View {
    @EnvironmentObject var model: AppModel
    @State private var picking = false
    @State private var manual = false
    @State private var name = ""
    @State private var phone = ""
    @State private var web = ""

    var body: some View {
        let st = model.power
        ScrollView {
            if let p = model.selected {
                let b = st.bundle
                let util = p.utility ?? b?.utilities.first
                VStack(alignment: .leading, spacing: 14) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your electric utility").font(.title3.bold())
                        if st.loading && b == nil { LoadingView() }
                        else if let util {
                            Text(util.name).font(.title2)
                            Text(util.manual ? "Chosen by you." : "Likely provider based on public service-area data. Territory boundaries are approximate — if this looks wrong, correct it.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                            if let w = util.website { LinkButton(title: "Utility website / outage map", url: w) }
                            if let ph = util.phone { Button { dial(ph) } label: { Label("Call \(ph)", systemImage: "phone").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered) }
                            LinkButton(title: "Search “\(util.name) report outage”", url: "https://www.google.com/search?q=" + (("\(util.name) report power outage").addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""), icon: "magnifyingglass")
                            if util.website == nil || util.phone == nil { Text("Some contact details aren't published in the service-area data. Check your latest electric bill for your utility's outage line.").font(.caption) }
                        } else {
                            Text(b?.utilitiesError != nil ? "Couldn't look up the utility for \(p.cityState)." : "We couldn't identify a utility for \(p.cityState).").font(.subheadline)
                            Text("Enter it yourself so you can reach the right outage map and phone number.").font(.caption)
                        }
                        HStack {
                            if (b?.utilities.count ?? 0) > 1 { Button("Not right? Choose another") { picking = true } }
                            Button("Enter manually") { manual = true }
                            if p.utility != nil { Button("Reset") { model.repo.setUtility(p, nil) } }
                        }.font(.subheadline)
                        Text("Always report outages and get restoration estimates from your utility — it is the primary source.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                        if let b { SourceLine(source: "Electric retail service territories (HIFLD)", updatedMs: b.fetchedAtMs, tz: p.timeZone) }
                    }.card()

                    VStack(alignment: .leading, spacing: 6) {
                        Text("County outage overview (unofficial)").font(.title3.bold())
                        if b == nil { LoadingView() }
                        else if let c = b?.county {
                            if c.customersTracked <= 0 { Text("No utility feeds are tracked for this county — outages can't be shown here. This is missing coverage, not zero outages.").font(.subheadline) }
                            else {
                                Text("\(c.customersOut.formatted()) customers out in \(p.countyName ?? "this county")").font(.title3)
                                Text(String(format: "%.2f%% of %@ tracked customers · %d incident%@", c.pct, c.customersTracked.formatted(), c.incidents, c.incidents == 1 ? "" : "s")).font(.subheadline)
                                ForEach(c.utilities, id: \.name) { Text("• \($0.name): \($0.customersOut.formatted()) out").font(.caption) }
                            }
                            let ok = c.liveStatus == nil || c.liveStatus == "ok"
                            Text("Data as of \(c.asOfMs.map { Units.stamp($0, p.timeZone) } ?? "unknown")\(c.behindMin.map { " · feed ~\($0) min behind" } ?? "")\(ok ? "" : " · feed status: \(c.liveStatus ?? "") — may be stale")").font(.caption).foregroundStyle(ok ? Tw.onSurfaceMuted : Tw.warning)
                        } else { Text(b?.countyError != nil ? "Couldn't reach MassOutage. Use your utility's outage map above." : "MassOutage doesn't have county coverage for this location. That doesn't mean there are no outages — use your utility's outage map.").font(.subheadline) }
                        Text("Unofficial and supplemental. Not a substitute for your utility.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                        Link("MassOutage (massoutage.com) · CC BY 4.0", destination: URL(string: "https://massoutage.com")!).font(.caption)
                    }.card()

                    VStack(alignment: .leading, spacing: 6) {
                        Text("Nationwide overview (unofficial)").font(.title3.bold())
                        if let n = st.national {
                            if let v = n.value {
                                if n.stale { Text("Showing saved data — not current.").font(.caption.weight(.semibold)).foregroundStyle(Tw.warning) }
                                Text("\(v.customersOut.formatted()) customers out across \(v.counties) counties").font(.title3)
                                Text("Tracking \(v.sourcesOk) of \(v.sources) utility feeds (\(v.trackedCustomers.formatted()) customers). \(v.notLocalizedOut.formatted()) outages can't be assigned to a county.").font(.caption)
                                ForEach(v.states.prefix(8), id: \.abbr) { s in HStack { Text(s.name).font(.subheadline); Spacer(); Text(s.customersOut.formatted()).font(.subheadline.weight(.semibold)) } }
                                Text("Top 8 states by customers out. States with no tracked feeds are not shown as zero.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                                SourceLine(source: "MassOutage (massoutage.com)", updatedMs: v.asOfMs, tz: p.timeZone, stale: v.stale)
                            } else { Text("Couldn't load the national overview.").font(.subheadline) }
                        } else { LoadingView() }
                    }.card()

                    if p.state == "TX" {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Texas grid conditions (ERCOT)").font(.title3.bold())
                            Text("ERCOT reports statewide power-grid supply and demand. It is not a source for household outages — use your utility for those.").font(.subheadline)
                            LinkButton(title: "ERCOT grid and market conditions", url: "https://www.ercot.com/gridmktinfo/dashboards")
                        }.card()
                    }
                }.padding(16)
                .confirmationDialog("Choose your utility", isPresented: $picking, titleVisibility: .visible) {
                    ForEach(b?.utilities ?? [], id: \.name) { u in Button(u.name) { var c = u; c.manual = true; model.repo.setUtility(p, c) } }
                }
                .alert("Enter your utility", isPresented: $manual) {
                    TextField("Utility name", text: $name); TextField("Outage phone (optional)", text: $phone).keyboardType(.phonePad); TextField("Website (optional)", text: $web).keyboardType(.URL).textInputAutocapitalization(.never)
                    Button("Save") {
                        let n = name.trimmingCharacters(in: .whitespaces)
                        if !n.isEmpty { model.repo.setUtility(p, UtilityChoice(name: n, phone: phone.isEmpty ? nil : phone, website: web.isEmpty ? nil : (web.hasPrefix("http") ? web : "https://\(web)"), state: p.state, manual: true)) }
                        name = ""; phone = ""; web = ""
                    }
                    Button("Cancel", role: .cancel) {}
                }
            } else { EmptyState(icon: "mappin.circle", title: "Add a place", message: "Power information is shown for a place.").padding(.top, 60) }
        }
        .navigationTitle("Power outages").navigationBarTitleDisplayMode(.inline)
        .refreshable { model.loadPower(force: true) }
        .task(id: model.selected?.id) { model.loadPower() }
    }
}
