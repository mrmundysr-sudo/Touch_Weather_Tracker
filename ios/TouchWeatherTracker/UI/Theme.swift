import SwiftUI
import UIKit
import TWCore

/// Adaptive color from light/dark hex values.
func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
    Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
}
extension UIColor { convenience init(hex: UInt32) { self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1) } }

/// Severity and semantic palette. Colors are never the only signal: every use also carries an icon and a text label.
enum Tw {
    static let background = dyn(0xF4F8FC, 0x0A1522)
    static let surface = dyn(0xFFFFFF, 0x10202F)
    static let surfaceVariant = dyn(0xE3EDF7, 0x1A3047)
    static let outline = dyn(0xC5D4E3, 0x2D4660)
    static let primary = dyn(0x0B5FA5, 0x8AC4FF)
    static let secondary = dyn(0x00695C, 0x6FD6C4)
    static let onSurface = dyn(0x0B2239, 0xE6EEF7)
    static let onSurfaceMuted = dyn(0x3F566C, 0xB3C6D9)
    static let warning = dyn(0xB3261E, 0xFFB4AB)
    static let watch = dyn(0xA85600, 0xFFB77C)
    static let advisory = dyn(0x7A5C00, 0xE9C46A)
    static let statement = dyn(0x3B5B7A, 0xA9C7E8)
    static let observed = dyn(0x00695C, 0x6FD6C4)
    static let forecast = dyn(0x0B5FA5, 0x8AC4FF)
    static let model = dyn(0x6A3FA0, 0xD0B8FF)
    static let skyDeep = Color(hex: 0x0B2A4A)
    static let errorContainer = dyn(0xFFDAD6, 0x93000A)
    static let tertiaryContainer = dyn(0xFFDEA8, 0x5E3C00)
    static let secondaryContainer = dyn(0xCDEFE8, 0x004F44)

    static func color(_ l: AlertLevel) -> Color { switch l { case .warning: return warning; case .watch: return watch; case .advisory: return advisory; case .statement: return statement } }
    static func icon(_ l: AlertLevel) -> String { switch l { case .warning: return "exclamationmark.triangle.fill"; case .watch: return "eye.fill"; case .advisory: return "info.circle.fill"; case .statement: return "doc.text.fill" } }
}

func weatherSymbol(_ summary: String?, day: Bool = true) -> String { Wx.symbol(summary, day: day) }
