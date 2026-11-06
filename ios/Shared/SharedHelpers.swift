import SwiftUI
import TWCore

// Shared by the app and the widget extension (compiled into both targets).

/// SwiftUI also defines `Alert`; make the weather alert model win inside these modules.
typealias Alert = TWCore.Alert

extension Color { init(hex: UInt32) { self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255) } }

/// Runs `body` but gives up waiting after `seconds` (widget refresh budgets and network calls are bounded).
func withTimeout(_ seconds: Double, _ body: @escaping @Sendable () async -> Void) async {
    await withTaskGroup(of: Void.self) { g in
        g.addTask { await body() }
        g.addTask { try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
        _ = await g.next(); g.cancelAll()
    }
}

import WidgetKit
private struct FamilyOverrideKey: EnvironmentKey { static let defaultValue: WidgetFamily? = nil }
extension EnvironmentValues {
    /// Lets the CI widget gallery render a widget view at a chosen family (`widgetFamily` itself is read-only). nil in real widgets.
    var twFamilyOverride: WidgetFamily? { get { self[FamilyOverrideKey.self] } set { self[FamilyOverrideKey.self] = newValue } }
}
