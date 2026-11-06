import SwiftUI
import TWCore

struct AlertsView: View {
    @EnvironmentObject var model: AppModel
    @State private var query = ""
    @State private var level: AlertLevel?

    var body: some View {
        let st = model.feed
        let tz = model.selected?.timeZone
        List {
            Section {
                Picker("Scope", selection: Binding(get: { st.scope }, set: { model.loadFeed($0) })) {
                    Text("Home").tag(FeedScope.home); Text("Saved").tag(FeedScope.saved); Text("Favorites").tag(FeedScope.favorites); Text("All active").tag(FeedScope.all)
                }.pickerStyle(.segmented)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack { ForEach(AlertLevel.allCases, id: \.self) { l in
                        Button { level = level == l ? nil : l } label: { Label(l.rawValue.capitalized, systemImage: Tw.icon(l)).font(.footnote.weight(.semibold)).padding(.horizontal, 12).padding(.vertical, 8)
                            .background(level == l ? Tw.color(l).opacity(0.2) : Tw.surfaceVariant, in: Capsule()).overlay(Capsule().stroke(level == l ? Tw.color(l) : .clear)) }.buttonStyle(.plain).frame(minHeight: 44)
                    } }
                }
            }.listRowBackground(Color.clear).listRowSeparator(.hidden)

            if st.data == nil && st.loading { LoadingView().listRowSeparator(.hidden) }
            else if let d = st.data, d.value == nil { ErrorState(message: d.error ?? "Alerts are unavailable.") { model.loadFeed(force: true) }.listRowSeparator(.hidden) }
            else if let d = st.data, let all = d.value {
                if d.stale { StaleBanner(message: d.error ?? "Couldn't refresh alerts.", fetchedAtMs: d.fetchedAtMs, tz: tz) { model.loadFeed(force: true) }.listRowInsets(EdgeInsets()).listRowSeparator(.hidden) }
                let list = all.filter { t in
                    (level == nil || t.alert.level == level) && (query.isEmpty || [t.alert.event, t.alert.areaDesc, t.label, t.alert.headline ?? ""].contains { $0.localizedCaseInsensitiveContains(query) })
                }.sorted { ($0.alert.level, $0.alert.endMs ?? .max) < ($1.alert.level, $1.alert.endMs ?? .max) }
                if st.scope == .favorites && model.favorites.isEmpty {
                    VStack(spacing: 10) {
                        EmptyState(icon: "star", title: "No favorite areas yet", message: "Follow a state or county — useful for keeping an eye on family and friends.")
                        Button("Add a favorite area") { model.tab = .today; model.path.append(.favorites) }.buttonStyle(.borderedProminent)
                    }.listRowSeparator(.hidden)
                } else if list.isEmpty {
                    EmptyState(icon: "checkmark.circle", title: all.isEmpty ? "No active alerts found" : "Nothing matches your filters",
                               message: all.isEmpty ? "The National Weather Service has no active alerts for this selection right now. That does not mean a warning can never be issued — keep Wireless Emergency Alerts on." : "Clear the filters to see all \(all.count) alerts.").listRowSeparator(.hidden)
                }
                ForEach(list, id: \.alert.id) { t in
                    NavigationLink(value: Route.alert(t.alert.id)) { AlertRow(alert: t.alert, tz: tz, area: st.scope == .all ? t.alert.states.joined(separator: ", ") : t.label) }
                        .buttonStyle(.plain).listRowSeparator(.hidden).listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16)).listRowBackground(Color.clear)
                }
                SourceLine(source: "National Weather Service", updatedMs: d.fetchedAtMs, tz: tz, stale: d.stale).listRowSeparator(.hidden).listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain).scrollContentBackground(.hidden)
        .searchable(text: $query, prompt: "Filter by place, county or hazard")
        .navigationTitle("Alerts")
        .refreshable { model.loadFeed(force: true) }
        .task { model.loadFeed() }
    }
}

struct AlertDetailView: View {
    let id: String
    @EnvironmentObject var model: AppModel
    var body: some View {
        let tz = model.selected?.timeZone
        ScrollView {
            if let a = model.alertIndex[id] {
                VStack(alignment: .leading, spacing: 12) {
                    HStack { SeverityChip(level: a.level); if a.lifeSafety { Label("Life-safety", systemImage: "heart.text.square").font(.footnote.weight(.semibold)).foregroundStyle(Tw.warning) } }
                    Text(a.event).font(.title2.bold())
                    if let h = a.headline { Text(h).font(.headline) }
                    VStack(alignment: .leading, spacing: 4) {
                        DetailRowView(label: "Area", value: a.areaDesc)
                        DetailRowView(label: "Issued", value: a.sentMs.map { "\(Units.stamp($0, tz)) (\(Units.ago($0)))" } ?? "—")
                        if let o = a.onsetMs { DetailRowView(label: "Begins", value: Units.stamp(o, tz)) }
                        DetailRowView(label: "Expires", value: a.endMs.map { "\(Units.stamp($0, tz)) (\(Units.until($0)))" } ?? "Not specified")
                        DetailRowView(label: "Severity", value: "\(a.severity) · urgency \(a.urgency.lowercased()) · certainty \(a.certainty.lowercased())")
                        if let r = a.response, r != "None" { DetailRowView(label: "Recommended response", value: r) }
                        DetailRowView(label: "Status", value: a.messageType == "Update" ? "Updated message" : a.messageType)
                        DetailRowView(label: "Issued by", value: a.senderName)
                    }.card()
                    if let i = a.instruction { Text("What to do").font(.headline); Text(i).card(Tw.tertiaryContainer) }
                    Text("Official description").font(.headline)
                    Text(a.description.replacingOccurrences(of: #"(?<!\n)\n(?!\n)"#, with: " ", options: .regularExpression))
                    if let ring = a.polygons.first, !ring.isEmpty {
                        Button {
                            model.mapFocus = MapFocus(lat: ring.map { $0[1] }.reduce(0, +) / Double(ring.count), lon: ring.map { $0[0] }.reduce(0, +) / Double(ring.count), spanDegrees: 1.5)
                            model.tab = .map
                        } label: { Label("Show warned area on map", systemImage: "map").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
                    }
                    SourceLine(source: "National Weather Service (api.weather.gov)", updatedMs: a.sentMs, tz: tz)
                }.padding(16)
            } else {
                EmptyState(icon: "calendar.badge.exclamationmark", title: "Alert no longer available", message: "This alert has expired, been cancelled, or replaced. Check the Alerts tab for current alerts.").padding(.top, 60)
            }
        }.navigationTitle("Alert detail").navigationBarTitleDisplayMode(.inline)
    }
}
