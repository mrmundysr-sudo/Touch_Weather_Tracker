import SwiftUI
import TWCore

struct StormsView: View {
    @EnvironmentObject var model: AppModel
    @State private var details = false

    var body: some View {
        let _ = model.prefsTick
        let st = model.storms
        let storms = st.storms?.value ?? []
        let active = model.prefs.eventModeActiveStorm
        let tz = model.selected?.timeZone
        ScrollView {
            VStack(spacing: 0) {
                Text("Tropical cyclones from the National Hurricane Center.").font(.subheadline).foregroundStyle(Tw.onSurfaceMuted).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
                if let s = st.storms, s.stale { StaleBanner(message: s.error ?? "Couldn't refresh NHC data.", fetchedAtMs: s.fetchedAtMs, tz: tz) { model.loadStorms(force: true) } }
                eventCard(st, storms, active)

                if st.storms == nil && st.loading { LoadingView(label: "Checking the National Hurricane Center…") }
                else if st.storms?.value == nil { ErrorState(message: st.storms?.error ?? "Storm data is unavailable.") { model.loadStorms(force: true) } }
                else if storms.isEmpty {
                    EmptyState(icon: "sun.max", title: "No active tropical cyclones", message: "The National Hurricane Center is not currently issuing advisories for any tropical storm or hurricane. This does not mean other hazards are absent — check Alerts.")
                    Section_(title: "Outlook") { LinkButton(title: "NHC Tropical Weather Outlook", url: "https://www.nhc.noaa.gov/gtwo.php"); LinkButton(title: "Hurricane preparedness (ready.gov)", url: "https://www.ready.gov/hurricanes") }
                } else {
                    ForEach(storms.sorted { ($0.intensityKt ?? 0) > ($1.intensityKt ?? 0) }) { s in
                        Section_(title: "\(s.classLabel) \(s.name)") {
                            NavigationLink(value: Route.storm(s.id)) {
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack(spacing: 12) {
                                        Image(systemName: "hurricane").font(.largeTitle).foregroundStyle(Tw.watch)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text([ActiveStorm.saffir(s.intensityKt), s.intensityKt.map { "\(StormReportBuilder.ktToMph($0)) mph winds" }, s.pressureMb.map { "\($0) mb" }].compactMap { $0 }.joined(separator: " · ")).font(.subheadline.weight(.semibold))
                                            if let p = model.selected { Text("\(Int(Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).rounded())) mi \(Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))) of \(p.cityState)").font(.subheadline) }
                                            if let d = s.moveDirDeg, let sp = s.moveSpeedMph { Text("Moving \(Geo.compass(Double(d))) at \(sp) mph").font(.caption) }
                                            Text("Advisory \(s.advisoryNumber ?? "—") · \(s.advisoryIssuedMs.map { Units.dayClock($0, tz) } ?? "time unknown")").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                                        }
                                        Spacer(); Image(systemName: "chevron.right")
                                    }
                                    Text("Open the storm report for \(model.selected?.cityState ?? "your place")").font(.subheadline.weight(.semibold)).foregroundStyle(Tw.primary)
                                }.card()
                            }.buttonStyle(.plain)
                        }
                    }
                }
                if let f = st.storms?.fetchedAtMs { SourceLine(source: "National Hurricane Center (CurrentStorms.json)", updatedMs: f, tz: tz, stale: st.storms?.stale == true).padding(.horizontal, 16) }
            }.padding(.bottom, 24)
        }
        .navigationTitle("Storms")
        .refreshable { model.loadStorms(force: true) }
        .task { model.loadStorms() }
    }

    @ViewBuilder private func eventCard(_ st: StormsState, _ storms: [ActiveStorm], _ active: String?) -> some View {
        let ev = st.event, eligible = ev?.eligibleStorm
        if let active {
            let s = storms.first { $0.id == active }
            if eligible?.id == active || ev?.hurricaneWarningInEffect == true {
                Section_(title: "Event mode") { VStack(alignment: .leading, spacing: 6) {
                    Text("Event mode is on for \(s?.name ?? active.uppercased())").font(.subheadline.weight(.semibold))
                    Text("Your saved places' storm information and urgent app alerts are refreshed more often (iOS decides when background refresh runs). Wireless Emergency Alerts are separate and still the most reliable way to be warned.").font(.subheadline)
                    Button("Return to normal") { model.eventAction(.end, stormId: active) }
                }.card(Tw.secondaryContainer) }
            } else if !model.prefs.eventModeEndPromptDismissed.contains(active) {
                Section_(title: "Event mode") { VStack(alignment: .leading, spacing: 6) {
                    Text("\(s?.name ?? "The storm") is no longer expected to affect your places").font(.subheadline.weight(.semibold))
                    Text("Official forecasts no longer show a U.S. hurricane landfall and related warnings have ended or aren't in effect for your places. Return to normal refresh settings, or keep event mode on?").font(.subheadline)
                    HStack { Button("Return to normal") { model.eventAction(.end, stormId: active) }; Button("Keep event mode") { model.eventAction(.keep, stormId: active) } }.buttonStyle(.bordered)
                }.card(Tw.tertiaryContainer) }
            } else { Section_(title: "Event mode") { VStack(alignment: .leading) { Text("Event mode is on for \(s?.name ?? active.uppercased()).").font(.subheadline.weight(.semibold)); Button("Return to normal") { model.eventAction(.end, stormId: active) } }.card() } }
        } else if let e = eligible, model.prefs.eventModeOffers {
            let declined = model.prefs.eventModeDeclined.contains(e.id)
            Section_(title: "Hurricane event mode") { VStack(alignment: .leading, spacing: 8) {
                Label("\(e.name) may make U.S. landfall as a hurricane", systemImage: "hurricane").font(.subheadline.weight(.semibold))
                Text(ev?.reason ?? "").font(.subheadline)
                Text("Event mode helps the app refresh your saved places' storm information and urgent app alerts more often during this event. iOS controls background work, so this is never instant or guaranteed.").font(.caption)
                if details { Text("• Uses your saved places and official NHC/NWS products — no continuous location tracking.\n• Requests background refresh more often than normal; iOS may delay or skip it.\n• Needs notification permission to alert you.\n• You'll be asked again when the storm no longer threatens your places. You can turn it off in Settings any time.").font(.caption) }
                HStack {
                    Button("Enable for this event") { model.eventAction(.enable, stormId: e.id); Task { if !(await Notifier.canPost()) { await Notifier.requestPermission() } } }.buttonStyle(.borderedProminent)
                    Button(declined ? "Declined" : "Not now") { model.eventAction(.decline, stormId: e.id) }.buttonStyle(.bordered)
                    Button(details ? "Hide" : "Details") { details.toggle() }.buttonStyle(.plain).foregroundStyle(Tw.primary)
                }
            }.card(Tw.tertiaryContainer) }
        }
    }
}

struct StormDetailView: View {
    let stormId: String
    @EnvironmentObject var model: AppModel
    @State private var reportPlaceId: String?
    @State private var showDetails = false

    var body: some View {
        let storm = model.storms.storms?.value?.first { $0.id == stormId }
        let place = reportPlaceId.flatMap { model.repo.place($0) } ?? model.selected
        let bundle = model.bundles[stormId]
        let report = place.flatMap { model.reports["\(stormId)|\($0.id)"] }
        ScrollView {
            if let storm {
                let tz = place?.timeZone
                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text([ActiveStorm.saffir(storm.intensityKt), storm.intensityKt.map { "\(StormReportBuilder.ktToMph($0)) mph max winds" }, storm.pressureMb.map { "\($0) mb" }].compactMap { $0 }.joined(separator: " · ")).font(.headline)
                        Text(String(format: "Center: %.1f°, %.1f°", storm.lat, storm.lon)).font(.subheadline)
                        if let d = storm.moveDirDeg, let s = storm.moveSpeedMph { Text("Moving \(Geo.compass(Double(d))) at \(s) mph").font(.subheadline) }
                        SourceLine(source: "NHC advisory \(storm.advisoryNumber ?? "")", updatedMs: storm.advisoryIssuedMs, tz: tz)
                    }.card()

                    HStack {
                        Text("Storm report for").font(.subheadline.weight(.semibold))
                        Menu { ForEach(model.places) { p in Button(p.cityState) { reportPlaceId = p.id } } } label: { Label(place?.cityState ?? "Choose a place", systemImage: "chevron.down").padding(.horizontal, 12).padding(.vertical, 8).background(Tw.surfaceVariant, in: Capsule()) }.frame(minHeight: 44)
                    }

                    if place == nil { EmptyState(icon: "mappin.circle", title: "Add a place", message: "Storm reports are written for a specific place.") }
                    else if let report {
                        Text(report.headline).font(.title2.bold())
                        if !report.threatens { Text("Based on the official forecast, \(storm.name) is not currently expected to affect \(place!.cityState). Conditions can change; check each advisory.").font(.subheadline) }
                        ForEach(report.sections) { sec in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(sec.title).font(.headline).accessibilityAddTraits(.isHeader)
                                Text(sec.question).font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                                if sec.items.isEmpty { Text(sec.emptyNote ?? "Nothing to report.").font(.subheadline) }
                                ForEach(sec.items) { reportItem($0, tz) }
                            }
                        }
                        DisclosureGroup("Details & sources", isExpanded: $showDetails) {
                            VStack(alignment: .leading) { ForEach(report.details) { DetailRowView(label: $0.label, value: $0.value) }; SourceLine(source: "Report generated on this device", updatedMs: report.generatedMs, tz: tz) }
                        }.card()
                    } else { LoadingView(label: bundle == nil ? "Gathering NHC products…" : "Checking buoys, water gauges and aircraft data…") }

                    if let t = bundle?.official {
                        Text("NHC official forecast points").font(.headline)
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(t.points, id: \.tauHours) { p in
                                HStack { Text("+\(p.tauHours) h").font(.subheadline.weight(.semibold)).frame(width: 56, alignment: .leading); Text(Units.dayClock(t.initMs + Int64(p.tauHours) * 3_600_000, tz)).font(.subheadline); Spacer(); Text(p.vmaxKt.map { "\(StormReportBuilder.ktToMph($0)) mph" } ?? "—").font(.subheadline) }
                            }
                            SourceLine(source: "NHC official forecast (initialized \(Units.stamp(t.initMs, tz)))", updatedMs: t.initMs, tz: tz)
                        }.card()
                    }

                    Text("Model guidance — not the official forecast").font(.headline)
                    modelCard("GFS (NOAA)", bundle?.gfs, bundle?.official, tz)
                    if let e = bundle?.ecmwf { modelCard("ECMWF (European)", e, bundle?.official, tz) }
                    else { VStack(alignment: .leading, spacing: 4) { Text("ECMWF (European)").font(.subheadline.weight(.semibold)); Text("No ECMWF track is available for this storm from the free open-data source right now, so none is shown. Nothing is estimated.").font(.subheadline) }.card() }
                    Text("Model tracks are single computer runs. They can differ from each other and from NHC's forecast, and they don't indicate consensus or certainty. Use the NHC forecast and your local NWS office for decisions.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)

                    if let hl = bundle?.highlights, !hl.isEmpty {
                        Text("From the NHC public advisory").font(.headline)
                        ForEach(Array(hl.prefix(5).enumerated()), id: \.offset) { _, h in VStack(alignment: .leading, spacing: 4) { Text(h.0).font(.subheadline.weight(.semibold)); Text(h.1).font(.subheadline) }.card() }
                    }
                    Text("Official NHC products").font(.headline)
                    VStack(spacing: 8) {
                        if let u = storm.publicAdvisoryUrl { LinkButton(title: "Public advisory", url: u) }
                        if let u = storm.discussionUrl { LinkButton(title: "Forecast discussion", url: u) }
                        if let u = storm.graphicsUrl { LinkButton(title: "Forecast graphics", url: u) }
                        if let u = storm.windProbUrl { LinkButton(title: "Wind speed probabilities", url: u) }
                        LinkButton(title: "Peak storm surge forecast", url: "https://www.nhc.noaa.gov/surge/")
                    }
                    VStack(alignment: .leading, spacing: 4) { Text("About the cone").font(.subheadline.weight(.semibold)); Text("The NHC cone shows the probable track of the storm's CENTER. It does not show the storm's size, or where wind, rain, flooding and storm surge can occur — those can extend hundreds of miles from the center.").font(.subheadline) }.card()
                }.padding(16)
                .task(id: storm.id) { model.loadBundle(storm) }
                .task(id: "\(bundle?.fetchedAtMs ?? 0)|\(place?.id ?? "")") { if let b = bundle, let p = place { model.loadReport(place: p, bundle: b) } }
            } else if model.storms.loading || model.storms.storms == nil { LoadingView().padding(.top, 60) }
            else { EmptyState(icon: "sun.max", title: "This storm is no longer active", message: "NHC has stopped issuing advisories for it.").padding(.top, 60) }
        }
        .navigationTitle(storm.map { "\($0.classLabel) \($0.name)" } ?? "Storm").navigationBarTitleDisplayMode(.inline)
        .toolbar { if let s = storm { ToolbarItem(placement: .primaryAction) { Button { model.mapFocus = MapFocus(lat: s.lat, lon: s.lon, spanDegrees: 14); model.tab = .map } label: { Image(systemName: "map") }.accessibilityLabel("Show on map") } } }
        .task { if model.storms.storms == nil { model.loadStorms() } }
    }

    private func reportItem(_ item: ReportItem, _ tz: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack { KindChip(kind: item.kind); Text(item.source).font(.caption.weight(.semibold)).foregroundStyle(Tw.onSurfaceMuted) }
            Text(item.text).font(.body)
            if let t = item.timeMs { Text("As of \(Units.stamp(t, tz)) (\(Units.ago(t)))").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
            if let l = item.link, let u = URL(string: l) { Link("Open official product", destination: u).font(.subheadline) }
        }.card()
    }

    private func modelCard(_ title: String, _ track: TrackLine?, _ official: TrackLine?, _ tz: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack { KindChip(kind: .model); Text(title).font(.subheadline.weight(.semibold)) }
            if let track {
                Text("Run initialized \(Units.stamp(track.initMs, tz)) (\(Units.ago(track.initMs))).").font(.caption)
                if let o = official, let last = track.points.last(where: { $0.tauHours <= (o.points.last?.tauHours ?? 0) }), let o2 = o.points.first(where: { $0.tauHours == last.tauHours }) {
                    Text("At +\(last.tauHours) h this model's center is about \(Int(Geo.distanceMiles(last.lat, last.lon, o2.lat, o2.lon).rounded())) mi from NHC's official forecast point.").font(.subheadline)
                }
                ForEach(track.points.filter { $0.tauHours % 24 == 0 }, id: \.tauHours) { p in Text(String(format: "+%d h · %.1f°, %.1f°", p.tauHours, p.lat, p.lon) + (p.vmaxKt.map { " · \(StormReportBuilder.ktToMph($0)) mph" } ?? "")).font(.caption) }
            } else { Text("No track from this model is available for this storm right now.").font(.subheadline) }
        }.card()
    }
}
