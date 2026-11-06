import SwiftUI
import WidgetKit

@main
struct TWWidgetsBundle: WidgetBundle {
    var body: some Widget {
        TempWidget(); HourlyWidget(); TodayWidget(); FiveDayWidget(); SavedPlaceWidget(); StormWatchWidget()
    }
}
