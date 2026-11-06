#if TWC_UITEST
import SwiftUI
import WidgetKit
import TWCore

/// CI-only (UITest configuration): renders the REAL widget views with the app's real cached data at standard widget sizes so layouts can be reviewed
/// from simulator screenshots. Not compiled into installable builds.
struct WidgetGalleryView: View {
    @State private var entry: WxEntry?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Widget gallery (CI)").font(.headline)
                if let e = entry {
                    item("Temp · small", .systemSmall, 170, 170) { TempWidgetView(e: e) }
                    item("Temp · lock screen rectangular", .accessoryRectangular, 172, 76) { TempWidgetView(e: e) }
                    item("Hourly · medium", .systemMedium, 364, 170) { HourlyWidgetView(e: e) }
                    item("Today · medium", .systemMedium, 364, 170) { TodayWidgetView(e: e) }
                    item("Today · large", .systemLarge, 364, 382) { TodayWidgetView(e: e) }
                    item("5-Day · medium", .systemMedium, 364, 170) { FiveDayWidgetView(e: e) }
                    item("5-Day · large", .systemLarge, 364, 382) { FiveDayWidgetView(e: e) }
                    item("Saved Place · small", .systemSmall, 170, 170) { SavedPlaceWidgetView(e: e) }
                    item("Saved Place · medium", .systemMedium, 364, 170) { SavedPlaceWidgetView(e: e) }
                    item("Storm Watch · small", .systemSmall, 170, 170) { StormWatchWidgetView(e: e) }
                    item("Storm Watch · medium", .systemMedium, 364, 170) { StormWatchWidgetView(e: e) }
                } else { ProgressView("Loading widget data…") }
            }.padding(16)
        }
        .task { entry = await WidgetLoader.load(placeId: nil, follow: true, refreshStale: true) }
    }

    private func item<V: View>(_ title: String, _ family: WidgetFamily, _ w: CGFloat, _ h: CGFloat, @ViewBuilder _ v: () -> V) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            v().environment(\.widgetFamily, family).padding(14).frame(width: w, height: h)
                .background(LinearGradient(colors: entry?.background ?? [.blue, .black], startPoint: .top, endPoint: .bottom))
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
    }
}
#endif
