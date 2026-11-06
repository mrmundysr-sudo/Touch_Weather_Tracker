import SwiftUI
import UserNotifications
import TWCore

struct SettingsView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var mon: Monetization
    @Environment(\.openURL) private var openURL
    @State private var offers = AppModel.shared.prefs.eventModeOffers

    var body: some View {
        let _ = model.prefsTick
        let activeStorm = model.prefs.eventModeActiveStorm
        List {
            Section("Places") { NavigationLink(value: Route.places) { Label("Places & favorite areas", systemImage: "mappin.and.ellipse") } }
            Section {
                Button { if let u = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(u) } } label: { Label("Notification settings", systemImage: "bell") }
                Button { Task { if !(await Notifier.postTest()) { model.toast("Notifications are turned off for this app in iOS Settings.") } } } label: { Label("Send a test notification", systemImage: "bell.badge") }
                VStack(alignment: .leading, spacing: 4) {
                    Text("Wireless Emergency Alerts").font(.subheadline.weight(.semibold))
                    Text("Keep government Wireless Emergency Alerts enabled in your phone's settings (Settings → Notifications → Government Alerts). App notifications supplement them and may be delayed by iOS power management.").font(.caption)
                }.padding(.vertical, 4)
            } header: { Text("Notifications") }
            Section {
                Toggle("Offer event mode", isOn: Binding(get: { offers }, set: { offers = $0; model.prefs.eventModeOffers = $0 }))
                Text("When NHC's official forecast shows a hurricane expected to make U.S. landfall within 72 hours, offer temporary event mode.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                if let s = activeStorm {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Event mode is on for \(s.uppercased())").font(.subheadline.weight(.semibold))
                        Text("Background refresh is requested more often for your saved places' storm information and urgent app alerts. iOS decides when it actually runs and may delay it. No continuous location tracking is used.").font(.caption)
                        Button("Return to normal") { model.eventAction(.end, stormId: s) }
                    }
                } else { Text("Event mode is off. Normal background refresh is requested about every 30 minutes; iOS decides when it runs.").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
            } header: { Text("Hurricane event mode") }
            Section("Unlock") {
                NavigationLink(value: Route.unlockStatus) { Label(unlockText, systemImage: "lock.open") }
            }
            Section {
                if mon.privacyOptionsRequired { Button { mon.showPrivacyOptions() } label: { Label("Ad privacy choices", systemImage: "hand.raised") } }
                NavigationLink(value: Route.sources) { Label("Data sources & attribution", systemImage: "info.circle") }
                Text("Your places stay on this device. Network requests go directly from your phone to U.S. government and other public data services; this app has no account system and no server of its own. Approximate location is used only when you tap “Use my location”.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
            } header: { Text("Privacy & data") }
            Section("About") {
                Text("Touch Weather Tracker \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")").font(.subheadline.weight(.semibold))
                Text("Build for owner testing. Not for store release until the owner's full review is complete.").font(.caption).foregroundStyle(Tw.onSurfaceMuted)
                if !AppEnvironment.hasSharedContainer { Text("This build has no App Group, so widgets cannot read the app's data. Signed builds with the App Group entitlement share data with widgets.").font(.caption).foregroundStyle(Tw.warning) }
            }
        }.navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
    }

    private var unlockText: String {
        switch mon.source { case .adUnlock: return "Unlocked until \(Units.stamp(mon.unlockUntilMs))"; case .adFreePurchase: return "Purchased — unlocked"; case .subscription: return "Subscription active"; case .none: return "Locked" }
    }
}

struct SourceInfo: Identifiable { var id: String { name }; let name, what, refresh, terms, stale: String }

struct SourcesView: View {
    private let sources = [
        SourceInfo(name: "NOAA / National Weather Service API (api.weather.gov)", what: "Forecasts, hourly forecasts, observations, alerts, Hazardous Weather Outlook, point → zone/county lookup.", refresh: "On open and pull-to-refresh; background as iOS allows (requested about every 30 min).", terms: "U.S. Government work, no key; requires a User-Agent. Not an endorsement by NWS.", stale: "Last forecast is kept and labeled 'not current'; alerts warn that they could not be refreshed."),
        SourceInfo(name: "National Hurricane Center (CurrentStorms.json, ATCF a-deck/best track, public advisories, ArcGIS tropical service)", what: "Active storms, official forecast track (OFCL), GFS guidance (AVNO), cone, watches/warnings, advisory text.", refresh: "About every 5 minutes while the Storms tab is open.", terms: "U.S. Government work. The official NHC forecast is authoritative; model tracks are shown separately as guidance.", stale: "Storms are hidden if the feed cannot be reached; saved copy labeled with its time."),
        SourceInfo(name: "NHC aircraft reconnaissance archive + tgftp.nws.noaa.gov", what: "HDOB observations, Vortex Data Messages, dropsonde reports near an active storm.", refresh: "Fetched when a storm report is opened; last 12 hours only.", terms: "U.S. Government products. No third-party live displays are used.", stale: "Omitted from the report when none is recent or in range."),
        SourceInfo(name: "NOAA NDBC latest observations", what: "Buoy wind, gust, pressure, wave height/period, water temperature.", refresh: "On storm report; stations older than 3 h are ignored.", terms: "NOAA public data.", stale: "Omitted; shown as unavailable in details."),
        SourceInfo(name: "NOAA CO-OPS Tides & Currents", what: "Observed water level and departure from predicted tide.", refresh: "On storm report; readings older than 2 h are ignored.", terms: "NOAA public data. Water level is never presented as storm surge.", stale: "Omitted."),
        SourceInfo(name: "NOAA MRMS radar via NWS RIDGE2 map service (mapservices.weather.noaa.gov)", what: "Base reflectivity mosaic on the Map.", refresh: "Roughly every 10 minutes at the source; the service does not publish a scan time.", terms: "NOAA public data.", stale: "Tiles show blank if unreachable."),
        SourceInfo(name: "NOAA nowCOAST GOES satellite imagery", what: "GOES East/West visible, longwave (infrared) and water vapor imagery.", refresh: "Source updates every few minutes; the app shows the service's latest time.", terms: "NOAA public data.", stale: "Layer is skipped if unreachable."),
        SourceInfo(name: "USGS The National Map basemap", what: "Basemap tiles (topographic and imagery).", refresh: "Static, cached on device.", terms: "USGS public domain; attribution shown on the map.", stale: "Cached tiles; blank if never loaded."),
        SourceInfo(name: "U.S. Census Bureau Geocoder and TIGERweb", what: "Address, ZIP code and city lookup; county lists; state-landfall test for event mode.", refresh: "On search.", terms: "U.S. Government public services; search text is sent to the Census Bureau.", stale: "Search shows an error; saved places keep working."),
        SourceInfo(name: "FEMA (OpenFEMA, ESF-6 shelters, Disaster Recovery Centers)", what: "Declarations and assistance programs, open shelters (incl. reported pet policy), recovery centers, USACE mission assignments.", refresh: "On opening Resources.", terms: "FEMA public data. Pet policy is shown only when the feed reports it; otherwise 'not reported'.", stale: "Listings are shown with their source and never as verified-open unless the feed says open."),
        SourceInfo(name: "Electric retail service territories (HIFLD data, public domain; mirrored on ArcGIS Online)", what: "Likely local electric utility, phone and website for a location.", refresh: "11-minute cache.", terms: "Public-domain HIFLD data via a third-party-hosted mirror; boundaries are approximate so you can correct the utility manually.", stale: "Manual utility selection remains available."),
        SourceInfo(name: "MassOutage (massoutage.com)", what: "Unofficial, supplemental county and national outage overview.", refresh: "No more than once every 11 minutes; cached; honors rate limits.", terms: "CC BY 4.0 — “MassOutage (massoutage.com)”. Unofficial and supplemental; never a substitute for your utility.", stale: "Shows data age; missing coverage is shown as 'not tracked', never as zero."),
    ]
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("No paid weather API, map tile service or server is used. Government sources are not endorsing this app.").font(.subheadline)
                ForEach(sources) { s in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.name).font(.subheadline.weight(.semibold))
                        DetailRowView(label: "Provides", value: s.what); DetailRowView(label: "Refresh", value: s.refresh)
                        DetailRowView(label: "Terms / attribution", value: s.terms); DetailRowView(label: "If stale or unavailable", value: s.stale)
                    }.card()
                }
                LinkButton(title: "MassOutage (massoutage.com)", url: "https://massoutage.com")
                LinkButton(title: "NOAA National Hurricane Center", url: "https://www.nhc.noaa.gov")
            }.padding(16)
        }.navigationTitle("Data sources").navigationBarTitleDisplayMode(.inline)
    }
}
