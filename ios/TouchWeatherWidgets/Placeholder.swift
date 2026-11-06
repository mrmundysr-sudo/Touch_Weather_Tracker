import WidgetKit
import SwiftUI
// Replaced by the real widgets in a following commit of this branch.
@main struct TWWidgetsBundle: WidgetBundle { var body: some Widget { EmptyWidgetPlaceholder() } }
struct EmptyWidgetPlaceholder: Widget {
    var body: some WidgetConfiguration { StaticConfiguration(kind: "twt.empty", provider: EmptyProvider()) { _ in Text("") } }
}
struct EmptyEntry: TimelineEntry { let date: Date }
struct EmptyProvider: TimelineProvider {
    func placeholder(in context: Context) -> EmptyEntry { EmptyEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (EmptyEntry) -> Void) { completion(EmptyEntry(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<EmptyEntry>) -> Void) { completion(Timeline(entries: [EmptyEntry(date: Date())], policy: .never)) }
}
