import SwiftUI
import TWCore

extension View {
    func card(_ container: Color = Tw.surface) -> some View {
        self.padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(container, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Tw.outline, lineWidth: 1))
    }
}

struct Section_<Content: View>: View {
    let title: String
    var trailing: AnyView? = nil
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(title).font(.headline).accessibilityAddTraits(.isHeader); Spacer(); trailing }
            content
        }.padding(.horizontal, 16).padding(.vertical, 8)
    }
}

struct SeverityChip: View {
    let level: AlertLevel
    var body: some View {
        Label(level.label, systemImage: Tw.icon(level)).font(.caption2.weight(.bold)).foregroundStyle(.white)
            .padding(.horizontal, 10).padding(.vertical, 4).background(Tw.color(level).opacity(0.95), in: Capsule())
            .accessibilityLabel("Severity: \(level.label.lowercased())")
    }
}

struct KindChip: View {
    let kind: ReportKind
    var color: Color { switch kind { case .observed: return Tw.observed; case .forecast: return Tw.forecast; case .warning: return Tw.warning; case .model: return Tw.model; case .summary: return Tw.onSurfaceMuted } }
    var icon: String { switch kind { case .observed: return "sensor"; case .forecast: return "chart.xyaxis.line"; case .warning: return "exclamationmark.triangle"; case .model: return "flask"; case .summary: return "doc.text" } }
    var body: some View { Label(kind.label, systemImage: icon).font(.caption2.weight(.semibold)).foregroundStyle(color).padding(.horizontal, 8).padding(.vertical, 3).background(color.opacity(0.12), in: Capsule()) }
}

struct SourceLine: View {
    let source: String
    var updatedMs: Int64?
    var tz: String?
    var stale = false
    var body: some View {
        var t = "Source: \(source)"
        if let u = updatedMs { t += " · updated \(Units.clock(u, tz)) (\(Units.ago(u)))" }
        if stale { t += " · NOT CURRENT" }
        return Text(t).font(.caption).foregroundStyle(stale ? Tw.warning : Tw.onSurfaceMuted)
    }
}

struct StaleBanner: View {
    let message: String; var fetchedAtMs: Int64?; var tz: String?; var retry: (() -> Void)?
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "icloud.slash.fill")
            VStack(alignment: .leading, spacing: 2) {
                Text("Showing saved information — not current").font(.subheadline.weight(.semibold))
                Text(message + (fetchedAtMs.map { " Last updated \(Units.stamp($0, tz))." } ?? "")).font(.caption)
            }
            Spacer()
            if let retry { Button("Retry", action: retry).font(.subheadline.weight(.semibold)).frame(minHeight: 44) }
        }.padding(12).background(Tw.errorContainer, in: RoundedRectangle(cornerRadius: 10)).padding(.horizontal, 16).padding(.vertical, 4)
    }
}

struct ErrorState: View {
    let message: String; var retry: (() -> Void)?
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "icloud.slash").font(.largeTitle).foregroundStyle(.secondary)
            Text("Couldn't load this").font(.headline)
            Text(message).font(.subheadline).foregroundStyle(Tw.onSurfaceMuted).multilineTextAlignment(.center)
            if let retry { Button("Try again", action: retry).buttonStyle(.bordered) }
        }.frame(maxWidth: .infinity).padding(24)
    }
}

struct EmptyState: View {
    let icon: String, title: String, message: String
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon).font(.system(size: 40)).foregroundStyle(Tw.primary)
            Text(title).font(.headline).multilineTextAlignment(.center)
            Text(message).font(.subheadline).foregroundStyle(Tw.onSurfaceMuted).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(24)
    }
}

struct LoadingView: View {
    var label = "Loading…"
    var body: some View { VStack(spacing: 10) { ProgressView(); Text(label).font(.subheadline).foregroundStyle(Tw.onSurfaceMuted) }.frame(maxWidth: .infinity).padding(32) }
}

/// Pet-accommodation indicator exactly as specified: allowed = colored icon, not allowed = grey icon with a red prohibition mark, unknown = greyed icon with no mark.
struct PetBadge: View {
    let status: PetStatus
    var body: some View {
        ZStack {
            switch status {
            case .allowed: Image(systemName: "pawprint.fill").foregroundStyle(Color(hex: 0x2E7D32))
            case .notAllowed: Image(systemName: "pawprint.fill").foregroundStyle(Color(hex: 0x9E9E9E)); Image(systemName: "nosign").font(.system(size: 30)).foregroundStyle(Color(hex: 0xD32F2F))
            case .unknown: Image(systemName: "pawprint.fill").foregroundStyle(Color(hex: 0xBDBDBD).opacity(0.7))
            }
        }.font(.system(size: 20)).frame(width: 36, height: 36)
        .accessibilityLabel(status == .allowed ? "Pets allowed" : status == .notAllowed ? "Pets not allowed" : "Pet policy not reported")
    }
}

struct LinkButton: View {
    let title: String, url: String
    var icon = "arrow.up.right.square"
    @Environment(\.openURL) private var openURL
    var body: some View {
        Button { if let u = URL(string: url) { openURL(u) } } label: { Label(title, systemImage: icon).frame(maxWidth: .infinity, minHeight: 44) }.buttonStyle(.bordered)
    }
}

func dial(_ phone: String) { let digits = phone.filter { $0.isNumber || $0 == "+" }; if let u = URL(string: "tel:\(digits)") { UIApplication.shared.open(u) } }
func openDirections(lat: Double, lon: Double, label: String) {
    let q = label.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
    if let u = URL(string: "http://maps.apple.com/?ll=\(lat),\(lon)&q=\(q)") { UIApplication.shared.open(u) }
}

struct DetailRowView: View {
    let label: String, value: String
    var body: some View { VStack(alignment: .leading, spacing: 2) { Text(label).font(.caption.weight(.semibold)).foregroundStyle(Tw.onSurfaceMuted); Text(value).font(.subheadline) }.padding(.vertical, 3).frame(maxWidth: .infinity, alignment: .leading) }
}
