import SwiftUI
import TWCore

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var mon: Monetization
    @State private var onboarded = AppModel.shared.prefs.onboarded
    private let tick = Timer.publish(every: 15, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Tw.background.ignoresSafeArea()
            if !onboarded || model.places.isEmpty {
                OnboardingView { model.prefs.onboarded = true; onboarded = true; BackgroundRefresh.schedule(eventMode: false); model.refreshToday() }
            } else if !mon.premium {
                // The whole app is locked until an entitlement exists (4 ads = 24 h, purchase, or subscription).
                UnlockView(isGate: true)
            } else {
                MainTabs()
            }
        }
        .task { mon.startAds(); await mon.loadProducts(); await mon.refreshEntitlements() }
        .onReceive(tick) { _ in mon.refresh() }          // re-evaluate against the clock so a 24 h unlock locks again on time
        .overlay(alignment: .bottom) { if let m = model.message { Toast(text: m).onAppear { DispatchQueue.main.asyncAfter(deadline: .now() + 4) { if model.message == m { model.message = nil } } } } }
        .animation(.default, value: model.message)
    }
}

struct Toast: View {
    let text: String
    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(.white).padding(.horizontal, 16).padding(.vertical, 12)
            .background(Color.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 12)).padding(.bottom, 90).padding(.horizontal, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity)).accessibilityAddTraits(.updatesFrequently)
    }
}

struct MainTabs: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        TabView(selection: $model.tab) {
            NavigationStack(path: $model.path) { TodayView().routes() }.tabItem { Label("Today", systemImage: "sun.max") }.tag(AppTab.today)
            MapTab().tabItem { Label("Map", systemImage: "map") }.tag(AppTab.map)
            NavigationStack { AlertsView().routes() }.tabItem { Label("Alerts", systemImage: "bell") }.tag(AppTab.alerts)
            NavigationStack { StormsView().routes() }.tabItem { Label("Storms", systemImage: "hurricane") }.tag(AppTab.storms)
            NavigationStack { ResourcesView().routes() }.tabItem { Label("Resources", systemImage: "heart.circle") }.tag(AppTab.resources)
        }
        .onChange(of: model.launchRequest) { _, r in
            guard let r else { return }
            if let id = r.placeId { model.selectPlace(id) }
            if let t = r.tab { model.tab = t }
            if let a = r.alertId { model.tab = .alerts; model.path = [] ; model.pendingAlertId = a }
            if r.stormId != nil { model.tab = .storms }
            model.launchRequest = nil
        }
        .task { if model.launchRequest != nil { model.objectWillChange.send() } }
    }
}

extension View {
    /// Destinations reachable from any tab.
    func routes() -> some View {
        navigationDestination(for: Route.self) { r in
            switch r {
            case .alert(let id): AlertDetailView(id: id)
            case .places: PlacesView()
            case .search(let home): SearchPlaceView(asHome: home)
            case .favorites: FavoritesPickerView()
            case .settings: SettingsView()
            case .unlockStatus: UnlockView(isGate: false)
            case .sources: SourcesView()
            case .power: PowerView()
            case .storm(let id): StormDetailView(stormId: id)
            }
        }
    }
}
