import Foundation
import UserNotifications
import TWCore

/// Local notifications for official NWS alerts and event-mode prompts. iOS may delay or skip background work; delivery is never instant or guaranteed.
enum Notifier {
    static let eventCategory = "twt.event.offer"
    static let endCategory = "twt.event.end"
    static let actionEnable = "twt.event.enable", actionDecline = "twt.event.decline", actionEnd = "twt.event.end.normal", actionKeep = "twt.event.keep"

    static func registerCategories() {
        let enable = UNNotificationAction(identifier: actionEnable, title: "Enable for this event", options: [])
        let decline = UNNotificationAction(identifier: actionDecline, title: "Not now", options: [])
        let end = UNNotificationAction(identifier: actionEnd, title: "Return to normal", options: [])
        let keep = UNNotificationAction(identifier: actionKeep, title: "Keep event mode", options: [])
        UNUserNotificationCenter.current().setNotificationCategories([
            UNNotificationCategory(identifier: eventCategory, actions: [enable, decline], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: endCategory, actions: [end, keep], intentIdentifiers: [], options: []),
        ])
    }

    static func authorizationStatus() async -> UNAuthorizationStatus { await UNUserNotificationCenter.current().notificationSettings().authorizationStatus }

    /// Ask for permission. `.timeSensitive` alerts need no extra permission prompt; critical alerts need an Apple entitlement we do not claim.
    @discardableResult static func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    static func canPost() async -> Bool {
        let s = await authorizationStatus()
        return s == .authorized || s == .provisional || s == .ephemeral
    }

    private static func post(id: String, title: String, body: String, level: UNNotificationInterruptionLevel, category: String? = nil, userInfo: [String: Any] = [:]) async {
        guard await canPost() else { return }
        let c = UNMutableNotificationContent()
        c.title = title; c.body = body; c.sound = level == .passive ? nil : .default; c.interruptionLevel = level; c.userInfo = userInfo
        if let category { c.categoryIdentifier = category }
        try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: c, trigger: nil))
    }

    static func postAlert(_ a: Alert, label: String, isUpdate: Bool) async {
        let title = (isUpdate ? "Updated: " : "") + a.event + (label.isEmpty ? "" : " — \(label)")
        var body = String((a.headline ?? a.areaDesc).prefix(240))
        if let i = a.instruction, !i.isEmpty { body += "\n" + String(i.prefix(200)) }
        // Urgent life-safety and warnings are time-sensitive so they can break through Focus; lesser alerts are active/passive.
        let level: UNNotificationInterruptionLevel = (a.lifeSafety || a.level == .warning) ? .timeSensitive : (a.level == .watch ? .active : .passive)
        await post(id: "alert-\(a.id)", title: title, body: body, level: level, userInfo: ["alertId": a.id, "tab": "alerts"])
    }

    static func postEnded(event: String, label: String) async {
        await post(id: "end-\(event)-\(label)", title: "\(event) ended or was replaced", body: label.isEmpty ? "Check Alerts for the current status." : "\(label) — check Alerts for the current status.", level: .passive, userInfo: ["tab": "alerts"])
    }

    static func postEventOffer(stormId: String, stormName: String, reason: String) async {
        await post(id: "offer-\(stormId)", title: "Hurricane \(stormName) may reach the U.S.",
                   body: "\(reason) Event mode helps this app refresh storm information and urgent alerts more often. iOS limits background work, so delivery is never instant or guaranteed.",
                   level: .active, category: eventCategory, userInfo: ["storm": stormId, "tab": "storms"])
    }

    static func postEventEnd(stormId: String, stormName: String) async {
        await post(id: "end-\(stormId)", title: "\(stormName) is no longer expected to affect your places", body: "Return to normal refresh settings, or keep event mode on?",
                   level: .active, category: endCategory, userInfo: ["storm": stormId, "tab": "storms"])
    }

    static func postTest() async -> Bool {
        guard await canPost() else { return false }
        await post(id: "test", title: "Test notification", body: "Notifications from Touch Weather are working. This is not a weather alert.", level: .active)
        return true
    }
}
