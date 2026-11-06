import Foundation
import TWCore

/// Shared storage so the app and its widgets read the same on-device data. Uses the App Group container when the build is signed with it,
/// otherwise falls back to Application Support (widgets then cannot see app data — only possible on unsigned/simulator builds without entitlements).
enum AppEnvironment {
    static let appGroup = "group.com.mundymorning.touchweathertracker"

    static var storeURL: URL {
        if let c = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) { return c.appendingPathComponent("store", isDirectory: true) }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("store", isDirectory: true)
    }
    static var hasSharedContainer: Bool { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil }

    static func makeRepository() -> Repository {
        Repository(store: Store(directory: storeURL), prefs: Prefs(suiteName: hasSharedContainer ? appGroup : nil))
    }
}
