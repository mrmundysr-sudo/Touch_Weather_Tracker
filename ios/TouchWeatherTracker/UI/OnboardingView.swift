import SwiftUI
import TWCore

struct OnboardingView: View {
    @EnvironmentObject var model: AppModel
    let onFinished: () -> Void
    @State private var step = 0

    var body: some View {
        Group {
            switch step {
            case 0: WelcomeStep { step = 1 }
            case 1: NavigationStack { SearchPlaceView(asHome: true, onboarding: true) { step = 2 } }
            case 2: NotificationsStep { step = 3 }
            default: ReadyStep(onFinished: onFinished)
            }
        }
        .onChange(of: model.places.count) { _, n in if n > 0 && step == 0 { step = 2 } }
        .onAppear { if !model.places.isEmpty && step < 2 { step = 2 } }
    }
}

private struct WelcomeStep: View {
    let next: () -> Void
    var body: some View {
        ZStack {
            LinearGradient(colors: [Tw.skyDeep, Color(hex: 0x1B5B94)], startPoint: .top, endPoint: .bottom).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 18) {
                Spacer(minLength: 20)
                Image(systemName: "cloud.sun.fill").font(.system(size: 64)).foregroundStyle(.white, .yellow)
                Text("Touch Weather Tracker").font(.largeTitle.bold()).foregroundStyle(.white)
                Text("Everyday weather and severe-weather information for the United States — from official sources, explained plainly.").font(.title3).foregroundStyle(.white.opacity(0.9))
                VStack(alignment: .leading, spacing: 14) {
                    row("calendar", "Forecasts and conditions for home and the places you care about")
                    row("bell.badge", "Official NWS warnings, with clear notification choices")
                    row("hurricane", "Hurricane tracking and a plain-language storm report")
                    row("heart.circle", "Shelters, FEMA help and power-outage information near you")
                }.padding(.top, 8)
                Spacer()
                Text("Information comes from NOAA, the National Weather Service, FEMA and other U.S. government sources. This app is not a government product and does not replace official emergency alerts.").font(.footnote).foregroundStyle(.white.opacity(0.8))
                Button(action: next) { Text("Get started").font(.headline).frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent).tint(.white).foregroundStyle(Tw.skyDeep)
            }.padding(28)
        }
    }
    func row(_ icon: String, _ text: String) -> some View { HStack(spacing: 14) { Image(systemName: icon).frame(width: 28).foregroundStyle(.white); Text(text).foregroundStyle(.white) } }
}

private struct NotificationsStep: View {
    @EnvironmentObject var model: AppModel
    let next: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: "bell.badge.fill").font(.system(size: 52)).foregroundStyle(Tw.primary).padding(.top, 40)
                Text("Get warnings for Home").font(.largeTitle.bold())
                Text("Touch Weather can notify you when the National Weather Service issues a new warning or urgent life-safety alert for your Home location.")
                Text("By default we only notify for new warnings at Home. Watches, advisories and other places are off until you choose them in Places → Alerts.").card()
                VStack(alignment: .leading, spacing: 6) {
                    Text("Keep Wireless Emergency Alerts on").font(.subheadline.weight(.semibold))
                    Text("Government WEA messages arrive on their own and don't depend on this app. App notifications are a supplement: iOS may delay background updates, so delivery is never instant or guaranteed.")
                }.card(Tw.tertiaryContainer)
                Button { Task { await Notifier.requestPermission(); model.prefs.notificationsAsked = true; next() } } label: { Text("Turn on warning notifications").frame(maxWidth: .infinity, minHeight: 50) }.buttonStyle(.borderedProminent)
                Button("Not now") { model.prefs.notificationsAsked = true; next() }.frame(maxWidth: .infinity, minHeight: 44)
            }.padding(24)
        }
    }
}

private struct ReadyStep: View {
    @EnvironmentObject var model: AppModel
    let onFinished: () -> Void
    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "checkmark.circle.fill").font(.system(size: 72)).foregroundStyle(Tw.observed)
            Text("You're set").font(.largeTitle.bold())
            Text("Home is \(model.home?.cityState ?? "set"). You can add family, friends, work or travel places any time, and enable hurricane event mode when a storm threatens.").multilineTextAlignment(.center)
            Text("Location, notifications and the map ask for permission only when you use them.").font(.subheadline).foregroundStyle(Tw.onSurfaceMuted).multilineTextAlignment(.center)
            Spacer()
            Button(action: onFinished) { Text("Show my weather").frame(maxWidth: .infinity, minHeight: 52) }.buttonStyle(.borderedProminent)
        }.padding(24)
    }
}
