import SwiftUI
import TWCore

struct PlacesView: View {
    @EnvironmentObject var model: AppModel
    @State private var editPrefs: Place?
    @State private var renaming: Place?
    @State private var newName = ""
    @State private var removing: Place?

    var body: some View {
        List {
            Section {
                Text("Tap the home icon on any place to make it your Home. Your forecast, alerts and Home widgets switch to it right away.").font(.footnote).foregroundStyle(Tw.onSurfaceMuted)
            }
            Section("Home and places of interest") {
                ForEach(model.places) { p in
                    HStack(alignment: .top, spacing: 8) {
                        // Empty home = not Home (tap to make it Home); filled home = this is Home.
                        Button { if !p.isHome { model.makeHome(p.id); model.toast("\(p.cityState) is now your Home") } } label: {
                            Image(systemName: p.isHome ? "house.fill" : "house").font(.title2).foregroundStyle(p.isHome ? Tw.primary : Tw.onSurfaceMuted).frame(width: 44, height: 44)
                        }.buttonStyle(.plain).accessibilityLabel(p.isHome ? "\(p.name) is your Home" : "Make \(p.name) your Home")
                        VStack(alignment: .leading, spacing: 4) {
                            Text(p.name).font(.headline)
                            Text([p.subtitle, p.countyName].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                            HStack(spacing: 6) {
                                if p.isHome { chip("Home") }; if p.isDefault && !p.isHome { chip("Default") }
                                if p.usesDeviceLocation { chip("Device location", "location.fill") }
                                if p.alertPrefs.notify { chip("Notifications on", "bell.badge.fill") }
                            }
                        }
                        Spacer()
                    }
                    .contentShape(Rectangle())
                    .onTapGesture { model.selectPlace(p.id); model.tab = .today; model.path = [] }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) { removing = p } label: { Label("Remove", systemImage: "trash") }
                        Button { renaming = p; newName = p.name } label: { Label("Rename", systemImage: "pencil") }.tint(.orange)
                        Button { editPrefs = p } label: { Label("Alerts", systemImage: "bell") }.tint(.blue)
                    }
                    .contextMenu {
                        Button { editPrefs = p } label: { Label("Alert settings", systemImage: "bell") }
                        Button { renaming = p; newName = p.name } label: { Label("Rename", systemImage: "pencil") }
                        if !p.isDefault && !p.isHome { Button { model.repo.setDefault(p.id) } label: { Label("Make default", systemImage: "star") } }
                        Button(role: .destructive) { removing = p } label: { Label("Remove", systemImage: "trash") }
                    }
                }
                Button { model.path.append(.search(home: false)) } label: { Label("Add place", systemImage: "plus") }.frame(minHeight: 44)
            }
            Section {
                ForEach(model.favorites) { f in
                    HStack {
                        Image(systemName: "star.fill").foregroundStyle(Tw.watch)
                        Text(f.label)
                        Spacer()
                        Toggle("Notify me", isOn: Binding(get: { f.alertPrefs.notify }, set: { v in var x = f; x.alertPrefs.notify = v; model.repo.updateFavorite(x) })).labelsHidden()
                    }.swipeActions { Button(role: .destructive) { model.repo.removeFavorite(f.id) } label: { Label("Remove", systemImage: "trash") } }
                }
                Button { model.path.append(.favorites) } label: { Label("Add a state or county", systemImage: "star") }.frame(minHeight: 44)
            } header: { Text("Favorite states & counties") } footer: { Text("Follow broader areas — handy for keeping an eye on family and friends.") }
        }
        .navigationTitle("Places")
        .sheet(item: $editPrefs) { p in AlertPrefsSheet(place: p) }
        .alert("Rename place", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $newName)
            Button("Save") { if let r = renaming, !newName.trimmingCharacters(in: .whitespaces).isEmpty { model.repo.renamePlace(r.id, newName.trimmingCharacters(in: .whitespaces)) }; renaming = nil }
            Button("Cancel", role: .cancel) { renaming = nil }
        }
        .confirmationDialog("Remove \(removing?.name ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }), titleVisibility: .visible) {
            Button("Remove", role: .destructive) { if let r = removing { model.removePlace(r.id) }; removing = nil }
        } message: { Text("Its saved forecast and notification settings will be deleted from this device.") }
    }

    private func chip(_ t: String, _ icon: String? = nil) -> some View {
        HStack(spacing: 3) { if let icon { Image(systemName: icon).font(.system(size: 9)) }; Text(t) }.font(.caption2.weight(.semibold)).padding(.horizontal, 8).padding(.vertical, 3).background(Tw.surfaceVariant, in: Capsule())
    }
}

struct AlertPrefsSheet: View {
    let place: Place
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var prefs: AlertPrefs
    init(place: Place) { self.place = place; _prefs = State(initialValue: place.alertPrefs) }

    var body: some View {
        NavigationStack {
            Form {
                Section { Toggle("Send notifications", isOn: $prefs.notify) } footer: { Text("Choose which official NWS alert types can notify you for this place. iOS Settings controls sound and banner style for Touch Weather notifications.") }
                Section("Alert types") {
                    Toggle("Warnings & urgent life-safety alerts", isOn: $prefs.warnings)
                    Toggle("Watches", isOn: $prefs.watches); Toggle("Advisories", isOn: $prefs.advisories); Toggle("Statements & other notices", isOn: $prefs.statements)
                }.disabled(!prefs.notify)
                Section { Text("App notifications supplement — and never replace — Wireless Emergency Alerts from the government. Keep those enabled in your phone's settings.").font(.footnote) }
            }
            .navigationTitle("Notifications for \(place.name)").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { var p = place; p.alertPrefs = prefs; model.repo.updatePlace(p); dismiss() } }
            }
        }
    }
}

struct SearchPlaceView: View {
    let asHome: Bool
    var onboarding = false
    var onDone: (() -> Void)? = nil
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false

    var body: some View {
        let s = model.search
        List {
            Section {
                TextField("Address, ZIP code or city", text: Binding(get: { model.search.query }, set: { model.onSearchQuery($0) }))
                    .textInputAutocapitalization(.words).autocorrectionDisabled().submitLabel(.search).accessibilityIdentifier("searchField")
                HStack {
                    Button { useMyLocation() } label: { Label("Use my location", systemImage: "location.fill") }.buttonStyle(.bordered).disabled(busy)
                    if !onboarding { Button { model.tab = .map; model.toast("Long-press the map to drop a pin."); model.path = [] } label: { Label("Drop a pin", systemImage: "mappin") }.buttonStyle(.bordered) }
                }
            } footer: { Text("Examples: 77002 · Miami, FL · 1600 Pennsylvania Ave NW, Washington DC") }

            if s.loading { LoadingView(label: "Searching U.S. Census data…") }
            else if let e = s.error { ErrorState(message: e) { model.onSearchQuery(s.query) } }
            else if s.searched && s.results.isEmpty { EmptyState(icon: "magnifyingglass", title: "No U.S. matches", message: "Check the spelling, add the state (e.g. “Springfield, IL”), or try a ZIP code. Touch Weather covers U.S. locations.") }
            else {
                ForEach(s.results) { g in
                    Button { Task { busy = true; _ = await model.addFromResult(g, asHome: asHome); busy = false; finish() } } label: {
                        HStack { Image(systemName: g.kind == "Address" ? "house" : g.kind == "ZIP code" ? "envelope" : "building.2").frame(width: 28)
                            VStack(alignment: .leading) { Text(g.label).font(.headline); Text([g.kind, g.subtitle].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
                            Spacer() }
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle())   // the whole row is tappable, not just the text
                    }.buttonStyle(.plain)
                }
            }
            Section { Text("Searches use free U.S. Census Bureau services. Your search text is sent to the Census Bureau; nothing is sent to any other party.").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
        }
        .navigationTitle(asHome ? "Set Home" : "Add a place").navigationBarTitleDisplayMode(.inline)
        .onDisappear { model.clearSearch() }
    }

    private func useMyLocation() {
        Task { busy = true; if await model.addDeviceLocation(asHome: asHome) != nil { finish() }; busy = false }
    }
    private func finish() { if let onDone { onDone() } else { dismiss() } }
}

struct FavoritesPickerView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var state: String?
    @State private var counties: [(fips: String, name: String)]?
    @State private var error: String?

    var body: some View {
        List {
            if let st = state {
                Button { model.repo.addFavorite(FavoriteArea(id: "ST:\(st)", label: NwsSource.stateName(st), state: st)); model.toast("Following \(NwsSource.stateName(st))"); dismiss() } label: { Label("Follow all of \(NwsSource.stateName(st))", systemImage: "star.fill") }.frame(minHeight: 44)
                if let e = error { ErrorState(message: e) { load(st) } }
                else if counties == nil { LoadingView() }
                ForEach(counties ?? [], id: \.fips) { c in
                    Button { model.repo.addFavorite(FavoriteArea(id: "CO:\(c.fips)", label: "\(c.name), \(st)", state: st, countyFips: c.fips)); model.toast("Following \(c.name), \(st)"); dismiss() } label: { Text(c.name).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).contentShape(Rectangle()) }
                }
            } else {
                ForEach(NwsSource.allStates, id: \.abbr) { s in Button { state = s.abbr; load(s.abbr) } label: { HStack { Text(s.name); Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary) }.frame(maxWidth: .infinity, minHeight: 44).contentShape(Rectangle()) } }
            }
        }
        .navigationTitle(state.map(NwsSource.stateName) ?? "Choose a state").navigationBarTitleDisplayMode(.inline)
        .toolbar { if state != nil { ToolbarItem(placement: .navigationBarLeading) { Button("States") { state = nil; counties = nil; error = nil } } } }
    }

    private func load(_ st: String) {
        counties = nil; error = nil
        Task { do { counties = try await CensusSource.counties(st) } catch { self.error = error.localizedDescription } }
    }
}
