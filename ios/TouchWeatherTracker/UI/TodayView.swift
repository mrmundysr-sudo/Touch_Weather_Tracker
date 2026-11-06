import SwiftUI
import TWCore

struct TodayView: View {
    @EnvironmentObject var model: AppModel
    @State private var expanded: Set<Int64> = []
    @State private var scrolled = false
    /// Status-bar / Dynamic Island height (read from UIKit so it is valid before layout).
    static var topInset: CGFloat {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.keyWindow?.safeAreaInsets.top ?? 54
    }

    var body: some View {
        Group {
            if let p = model.selected {
                // Once scrolled, a blurred scrim keeps content from running under the status bar.
                content(p, topInset: Self.topInset).overlay(alignment: .top) { StatusBarScrim(visible: scrolled, height: Self.topInset) }
            }
            else { EmptyState(icon: "mappin.circle", title: "Add a place", message: "Search by address, ZIP code or city to get your forecast.") }
        }
        .navigationBarHidden(true)
        .task(id: model.selected?.id) { model.refreshToday(); model.loadStorms() }
        .task(id: model.pendingAlertId) { if let a = model.pendingAlertId { model.pendingAlertId = nil; model.tab = .alerts; _ = a } }
    }

    @ViewBuilder private func content(_ p: Place, topInset: CGFloat) -> some View {
        let st = model.today
        let w = st.weather?.value
        let alerts = (st.alerts?.value?.alerts ?? []).sorted { ($0.level, $0.endMs ?? .max) < ($1.level, $1.endMs ?? .max) }
        let urgent = alerts.contains { $0.level == .warning }
        let activeStorm = model.storms.storms?.value?.max { ($0.intensityKt ?? 0) < ($1.intensityKt ?? 0) }
        ScrollView {
            VStack(spacing: 0) {
                header(p, w, urgent, top: topInset)
                    .background(GeometryReader { Color.clear.preference(key: ScrollOffsetKey.self, value: $0.frame(in: .named("todayScroll")).minY) })
                if let wl = st.weather, wl.stale { StaleBanner(message: wl.error ?? "Couldn't refresh.", fetchedAtMs: wl.fetchedAtMs, tz: p.timeZone) { model.refreshToday(force: true) } }
                else if let wl = st.weather, wl.value == nil { ErrorState(message: wl.error ?? "Weather is unavailable.") { model.refreshToday(force: true) } }
                if let al = st.alerts, al.stale { StaleBanner(message: "Alerts could not be refreshed. Do not assume there are no warnings.", fetchedAtMs: al.fetchedAtMs, tz: p.timeZone) { model.refreshToday(force: true) } }

                if !alerts.isEmpty {
                    Section_(title: "Active alerts at \(p.cityState)", trailing: AnyView(Button("All alerts") { model.tab = .alerts }.font(.subheadline))) {
                        ForEach(alerts.prefix(4)) { a in NavigationLink(value: Route.alert(a.id)) { AlertRow(alert: a, tz: p.timeZone) }.buttonStyle(.plain) }
                        if alerts.count > 4 { Text("+\(alerts.count - 4) more").font(.caption) }
                    }
                } else if st.alerts?.value != nil {
                    Section_(title: "Alerts") {
                        Label("No active alerts found for this location. Conditions can change quickly — this is not a guarantee that no warning will be issued.", systemImage: "checkmark.circle").font(.subheadline).foregroundStyle(Tw.onSurface)
                    }
                }

                if let s = activeStorm {
                    let d = Int(Geo.distanceMiles(p.lat, p.lon, s.lat, s.lon).rounded())
                    Section_(title: "Tropical activity") {
                        Button { model.tab = .storms } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "hurricane").font(.title).foregroundStyle(Tw.watch)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(s.classLabel) \(s.name)").font(.headline)
                                    Text("About \(d) mi \(Geo.compass(Geo.bearing(p.lat, p.lon, s.lat, s.lon))) of \(p.cityState). Open the plain-language storm report.").font(.subheadline)
                                }
                                Spacer(); Image(systemName: "chevron.right")
                            }.card(d < 800 ? Tw.tertiaryContainer : Tw.surface)
                        }.buttonStyle(.plain)
                    }
                }

                let now = Units.nowMs()
                let hours = Array((w?.hourly ?? []).filter { $0.timeMs >= now - 30 * 60_000 }.prefix(24))
                if !hours.isEmpty {
                    Section_(title: "Next 24 hours") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) { ForEach(hours, id: \.timeMs) { h in hourCell(h, p) } }
                        }
                        SourceLine(source: "NWS hourly forecast", updatedMs: w?.forecastUpdatedMs ?? w?.fetchedAtMs, tz: p.timeZone, stale: st.weather?.stale == true)
                    }
                }

                if let c = w?.conditions { Section_(title: "Current conditions") { conditions(c, w, p) } }

                let days = groupDays(w?.daily ?? [])
                if !days.isEmpty {
                    Section_(title: "Forecast") {
                        VStack(spacing: 0) {
                            ForEach(Array(days.enumerated()), id: \.element.id) { i, d in
                                Button { if expanded.contains(d.startMs) { expanded.remove(d.startMs) } else { expanded.insert(d.startMs) } } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        HStack {
                                            Text(i == 0 && !d.name.isEmpty ? d.name : Units.dayName(d.startMs, p.timeZone)).font(.subheadline.weight(.semibold)).frame(width: 92, alignment: .leading)
                                            WxIcon(summary: d.summary, size: 20).frame(width: 28)
                                            VStack(alignment: .leading) {
                                                Text(d.summary).font(.subheadline).lineLimit(1)
                                                if let pr = d.precip, pr > 0 { Text("\(Int(pr.rounded()))% precip").font(.caption2).foregroundStyle(Tw.forecast) }
                                            }
                                            Spacer()
                                            Text("\(Units.temp(d.high)) / \(Units.temp(d.low))").font(.subheadline.weight(.semibold))
                                        }
                                        if expanded.contains(d.startMs) { Text(d.detail).font(.subheadline).foregroundStyle(Tw.onSurfaceMuted) }
                                    }.padding(.vertical, 8).contentShape(Rectangle())
                                }.buttonStyle(.plain).accessibilityElement(children: .combine)
                                if i < days.count - 1 { Divider() }
                            }
                        }.card()
                        SourceLine(source: "NWS \(w?.forecastOffice ?? "") forecast", updatedMs: w?.forecastUpdatedMs ?? w?.fetchedAtMs, tz: p.timeZone, stale: st.weather?.stale == true)
                    }
                }

                if let o = st.outlook {
                    Section_(title: "Severe-weather outlook") {
                        VStack(alignment: .leading, spacing: 6) { Text(NwsSource.outlookSummary(o.text)).font(.subheadline); SourceLine(source: "NWS \(o.office) Hazardous Weather Outlook", updatedMs: o.issuedMs, tz: p.timeZone) }.card()
                    }
                }

                Text("Data from the National Weather Service and other U.S. government sources. Not an endorsement by any agency. For emergencies, follow official instructions and keep Wireless Emergency Alerts enabled.")
                    .font(.caption).foregroundStyle(Tw.onSurfaceMuted).padding(16)
            }
        }
        .coordinateSpace(name: "todayScroll")
        .onPreferenceChange(ScrollOffsetKey.self) { scrolled = $0 < -24 }
        .modifier(ScrollTracker { scrolled = $0 > 24 })
        .refreshable { model.refreshToday(force: true); model.loadStorms(force: true) }
        .ignoresSafeArea(edges: .top)
    }

    // MARK: header (turns urgent when a warning is active)
    private func header(_ p: Place, _ w: WeatherData?, _ urgent: Bool, top: CGFloat) -> some View {
        let cond = w?.conditions, hour0 = w?.hourly.first
        let temp = cond?.tempF ?? hour0?.tempF
        let day = groupDays(w?.daily ?? []).first
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Menu {
                    ForEach(model.places) { pl in Button { model.selectPlace(pl.id) } label: { Label(pl.cityState, systemImage: pl.isHome ? "house.fill" : "mappin") } }
                    Divider()
                    Button { model.path.append(.places) } label: { Label("Add or manage places", systemImage: "plus") }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: p.usesDeviceLocation ? "location.fill" : p.isHome ? "house.fill" : "mappin")
                        VStack(alignment: .leading, spacing: 0) {
                            Text(p.displayTitle).font(.title3.weight(.semibold)).lineLimit(1)
                            Text([p.isHome ? "Home" : nil, p.city != nil ? p.cityState : (p.state ?? p.subtitle)].compactMap { $0 }.joined(separator: " · ")).font(.caption).opacity(0.85)
                        }
                        Image(systemName: "chevron.down").font(.caption)
                    }.foregroundStyle(.white).frame(minHeight: 44)
                }.accessibilityLabel("Switch place. Current place \(p.name)")
                Spacer()
                Button { model.path.append(.places) } label: { Image(systemName: "mappin.and.ellipse").foregroundStyle(.white).frame(width: 44, height: 44) }.accessibilityLabel("Manage places")
                Button { model.path.append(.settings) } label: { Image(systemName: "gearshape").foregroundStyle(.white).frame(width: 44, height: 44) }.accessibilityLabel("Settings")
            }
            HStack(spacing: 14) {
                Image(systemName: weatherSymbol(cond?.summary ?? hour0?.summary, day: hour0?.isDay ?? true)).symbolRenderingMode(.multicolor).font(.system(size: 60))
                VStack(alignment: .leading) {
                    Text(Units.temp(temp)).font(.system(size: 72, weight: .light)).foregroundStyle(.white).accessibilityLabel("Temperature \(temp.map { String(Int($0.rounded())) } ?? "unknown") degrees Fahrenheit")
                    Text(cond?.summary ?? hour0?.summary ?? (model.today.loading ? "Loading…" : "Conditions unavailable")).font(.headline).foregroundStyle(.white)
                }
            }
            Text([cond?.feelsLikeF.map { "Feels like \(Units.temp($0))" }, day?.high.map { "H \(Units.temp($0))" }, day?.low.map { "L \(Units.temp($0))" }].compactMap { $0 }.joined(separator: "   "))
                .font(.body).foregroundStyle(.white.opacity(0.92))
        }
        .padding(.horizontal, 16).padding(.top, top + 8).padding(.bottom, 24)
        .background(LinearGradient(colors: urgent ? [Tw.warning.opacity(0.95), Tw.warning.opacity(0.6), Tw.background] : [Tw.skyDeep, Color(hex: 0x1B5B94).opacity(0.9), Tw.background], startPoint: .top, endPoint: .bottom))
    }

    private func hourCell(_ h: HourlyPoint, _ p: Place) -> some View {
        VStack(spacing: 4) {
            Text(Units.clock(h.timeMs, p.timeZone).replacingOccurrences(of: ":00", with: "")).font(.caption.weight(.semibold)).foregroundStyle(Tw.onSurfaceMuted)
            WxIcon(summary: h.summary, day: h.isDay, size: 22).frame(height: 28)
            Text(Units.temp(h.tempF)).font(.headline)
            Text((h.precipChance ?? 0) >= 10 ? "\(Int(h.precipChance!.rounded()))%" : " ").font(.caption2).foregroundStyle(Tw.forecast)
            Text(h.windMph.map { "\(Int($0.rounded())) mph" } ?? "").font(.caption2).foregroundStyle(Tw.onSurfaceMuted)
        }.frame(width: 72).padding(.vertical, 10).background(Tw.surface, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Tw.outline))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(Units.clock(h.timeMs, p.timeZone)), \(h.tempF.map { String(Int($0.rounded())) } ?? "unknown") degrees, \(h.summary ?? ""), \(Int((h.precipChance ?? 0).rounded())) percent chance of precipitation")
    }

    private func conditions(_ c: Conditions, _ w: WeatherData?, _ p: Place) -> some View {
        let rows: [(String, String, String)] = [
            ("Wind", c.windMph.map { "\(Units.mph($0))\(c.windDirDeg.map { " " + Geo.compass($0) } ?? "")" } ?? "Calm / not reported", "wind"),
            ("Gusts", c.gustMph.map { Units.mph($0) } ?? "None reported", "tornado"),
            ("Humidity", Units.pct(c.humidityPct), "humidity"),
            ("Dew point", Units.temp(c.dewpointF), "thermometer.medium"),
            ("Visibility", Units.miles(c.visibilityMi), "eye"),
            ("Pressure", c.pressureInHg.map { String(format: "%.2f inHg", $0) } ?? "—", "gauge"),
            ("Rain, last hour", c.precipLastHourIn.map { Units.inches($0) } ?? "None reported", "drop"),
            ("Feels like", Units.temp(c.feelsLikeF ?? c.tempF), "thermometer.sun"),
        ]
        return VStack(alignment: .leading, spacing: 8) {
            VStack(alignment: .leading, spacing: 12) {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 14) {
                    ForEach(rows, id: \.0) { r in
                        HStack(spacing: 8) { Image(systemName: r.2).foregroundStyle(Tw.primary).frame(width: 24); VStack(alignment: .leading) { Text(r.0).font(.caption2).foregroundStyle(Tw.onSurfaceMuted); Text(r.1).font(.subheadline.weight(.semibold)) } }
                            .accessibilityElement(children: .combine)
                    }
                }
                if let s = w?.sun, let a = s.sunriseMs, let b = s.sunsetMs { Label("Sunrise \(Units.clock(a, p.timeZone))  ·  Sunset \(Units.clock(b, p.timeZone))", systemImage: "sunrise").font(.subheadline) }
            }.card()
            SourceLine(source: "NWS station \(c.stationId ?? "")\(c.stationName.map { " (\($0))" } ?? "")", updatedMs: c.observedAtMs, tz: p.timeZone)
        }
    }
}

struct AlertRow: View {
    let alert: Alert; let tz: String?; var area: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack { SeverityChip(level: alert.level); Text(alert.event).font(.subheadline.weight(.semibold)); Spacer(); Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary) }
            Text(alert.headline ?? alert.areaDesc).font(.subheadline).lineLimit(3).multilineTextAlignment(.leading)
            Text([area.flatMap { $0.isEmpty ? nil : $0 }, alert.endMs.map { "Until \(Units.dayClock($0, tz))" }, alert.sentMs.map { "Issued \(Units.ago($0))" }].compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(Tw.onSurfaceMuted)
        }.card(alert.level == .warning ? Tw.errorContainer.opacity(0.55) : Tw.surface).accessibilityElement(children: .combine)
    }
}

struct ScrollOffsetKey: PreferenceKey { static var defaultValue: CGFloat = 0; static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() } }

struct StatusBarScrim: View {
    let visible: Bool
    let height: CGFloat
    var body: some View { Rectangle().fill(.regularMaterial).frame(height: height).offset(y: -height).opacity(visible ? 1 : 0).allowsHitTesting(false).animation(.easeInOut(duration: 0.15), value: visible) }
}

/// iOS 18+: direct scroll-offset callback (the preference-based tracker above covers iOS 17).
struct ScrollTracker: ViewModifier {
    let onOffset: (CGFloat) -> Void
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) { content.onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y } action: { _, new in onOffset(new) } }
        else { content }
    }
}
