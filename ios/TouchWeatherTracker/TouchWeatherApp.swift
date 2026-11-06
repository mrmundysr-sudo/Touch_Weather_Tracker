import SwiftUI
import UserNotifications
import BackgroundTasks
import TWCore

@main
struct TouchWeatherApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.monetization)
                .onOpenURL { model.handle(url: $0) }
        }
        .onChange(of: phase) { _, p in
            if p == .active { model.monetization.refresh(); Task { await model.monetization.refreshEntitlements() } }
            if p == .background { BackgroundRefresh.schedule(eventMode: model.prefs.eventModeActiveStorm != nil) }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Notifier.registerCategories()
        BackgroundRefresh.register(repo: AppModel.shared.repo)
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions { [.banner, .list, .sound] }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let storm = info["storm"] as? String
        await MainActor.run {
            let m = AppModel.shared
            switch response.actionIdentifier {
            case Notifier.actionEnable: if let storm { m.eventAction(.enable, stormId: storm) }
            case Notifier.actionDecline: if let storm { m.eventAction(.decline, stormId: storm) }
            case Notifier.actionEnd: if let storm { m.eventAction(.end, stormId: storm) }
            case Notifier.actionKeep: if let storm { m.eventAction(.keep, stormId: storm) }
            default:
                m.launchRequest = LaunchRequest(tab: (info["tab"] as? String).flatMap(AppTab.init(rawValue:)), alertId: info["alertId"] as? String, placeId: nil, stormId: storm)
            }
        }
    }
}

extension AppModel {
    static let shared = AppModel()

    /// touchweather://tab/<today|map|alerts|storms|resources>?place=<id>  (used by widgets)
    func handle(url: URL) {
        guard url.scheme == "touchweather" else { return }
        let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let tabName = url.host == "tab" ? url.pathComponents.dropFirst().first : url.host
        let place = comps?.queryItems?.first { $0.name == "place" }?.value
        launchRequest = LaunchRequest(tab: tabName.flatMap(AppTab.init(rawValue:)), alertId: nil, placeId: place, stormId: nil)
    }
}
