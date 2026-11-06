import SwiftUI
import TWCore

/// The unlock screen. As the gate (`isGate`) it is the only thing shown until the user watches four ads (24 hours of use), buys the app, or subscribes.
/// Opened from Settings it is the status page.
struct UnlockView: View {
    let isGate: Bool
    @EnvironmentObject var mon: Monetization
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let content = ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if isGate {
                    Image(systemName: "lock.fill").font(.system(size: 40)).foregroundStyle(Tw.primary).padding(.top, 24)
                    Text("Unlock Touch Weather Tracker").font(.largeTitle.bold())
                    Text("Choose how to use the app. Every option unlocks the same complete app.").font(.body)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(mon.premium ? "Touch Weather Tracker is unlocked" : "Locked").font(.headline)
                    Text(statusText).font(.subheadline)
                }.card(mon.premium ? Tw.secondaryContainer : Tw.surface)

                if !mon.premium {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Watch \(Monetization.adsRequired) ads → 24 hours of full use").font(.subheadline.weight(.semibold))
                        ProgressView(value: Double(mon.adViews), total: Double(Monetization.adsRequired))
                        Text("\(mon.adViews) of \(Monetization.adsRequired) ads watched").font(.caption)
                        Button { mon.watchAd() } label: {
                            HStack { if mon.adLoading && !mon.adReady { ProgressView() }; Text(mon.adReady ? "Watch an ad" : mon.adLoading ? "Loading an ad…" : "No ad available") }.frame(maxWidth: .infinity, minHeight: 44)
                        }.buttonStyle(.borderedProminent).disabled(!mon.adReady)
                        if !mon.canRequestAds { Text("Ads can't be requested until ad consent is completed. Review your choices below, or buy / subscribe instead.").font(.caption).foregroundStyle(Tw.warning) }
                        if !mon.adReady && !mon.adLoading { Button("Try loading an ad again") { mon.startAds(); mon.loadRewarded() }.frame(minHeight: 44) }
                    }.card()
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Buy the app").font(.subheadline.weight(.semibold))
                        Text("One-time purchase. No ads, unlocked permanently on this Apple ID.").font(.caption)
                        Button { Task { await mon.buyAdFree() } } label: { Text(mon.adFreePrice.map { "Buy · \($0)" } ?? "Buy the app").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent)
                    }.card()
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Subscribe to all apps").font(.subheadline.weight(.semibold))
                        Text("One subscription that unlocks this app and the owner's other apps that support it. Renews automatically until cancelled in your Apple ID settings.").font(.caption)
                        Button { Task { await mon.subscribe() } } label: { Text(mon.subscriptionPrice.map { "Subscribe · \($0)" } ?? "Subscribe").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.borderedProminent)
                    }.card()
                }
                Button { Task { await mon.restore() } } label: { Text("Restore purchases").frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
                if mon.privacyOptionsRequired { Button("Ad privacy choices") { mon.showPrivacyOptions() }.frame(maxWidth: .infinity, minHeight: 44) }
                if let m = mon.message { Text(m).font(.subheadline).foregroundStyle(Tw.primary) }
                if !mon.billingReady { Text("The App Store isn't connected for this build. Purchases only work for builds with the products set up in App Store Connect (see docs/MONETIZATION.md).").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
                if isGate { Text("While the app is locked it does not refresh data or send notifications, so keep Wireless Emergency Alerts turned on in your phone's settings — those government alerts do not depend on this app.").font(.caption).foregroundStyle(Tw.onSurfaceMuted) }
                Link("Terms of Use (Apple standard EULA)", destination: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!).font(.caption)
            }.padding(16)
        }
        if isGate { content } else { content.navigationTitle("Unlock status").navigationBarTitleDisplayMode(.inline) }
    }

    private var statusText: String {
        switch mon.source {
        case .adUnlock: return "Unlocked with ads until \(Units.stamp(mon.unlockUntilMs))."
        case .adFreePurchase: return "Thanks for buying the app — it's unlocked permanently on this Apple ID."
        case .subscription: return "Thanks — your subscription is active."
        case .none: return "Watch \(Monetization.adsRequired) short ads for 24 hours of full use, or buy the app, or subscribe to all Mundy Morning apps."
        }
    }
}
