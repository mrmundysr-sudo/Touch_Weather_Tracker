import Foundation
import BackgroundTasks
import TWCore
#if canImport(WidgetKit)
import WidgetKit
#endif

/// Battery-conscious background refresh. iOS decides when (or whether) a BGAppRefreshTask runs — it is opportunistic and never a fixed cadence,
/// so the app never claims instant delivery. Event mode asks for earlier refreshes; the system may still delay them.
enum BackgroundRefresh {
    static let identifier = "com.mundymorning.touchweathertracker.refresh"

    static func register(repo: Repository) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            let work = Task {
                await run(repo: repo)
                task.setTaskCompleted(success: true)
            }
            task.expirationHandler = { work.cancel() }
            schedule(eventMode: repo.prefs.eventModeActiveStorm != nil)      // always request the next one
        }
    }

    static func schedule(eventMode: Bool) {
        let req = BGAppRefreshTaskRequest(identifier: identifier)
        req.earliestBeginDate = Date(timeIntervalSinceNow: (eventMode ? 15 : 30) * 60)
        try? BGTaskScheduler.shared.submit(req)
    }

    /// One refresh pass: alert planning + notifications, event-mode check, widget data. A locked app does no background work.
    static func run(repo: Repository) async {
        guard repo.prefs.premiumActive() else { return }
        await pollAlerts(repo: repo)
        await checkEventMode(repo: repo)
        if let h = repo.home() { _ = await repo.weather(h); _ = await repo.alertsFor(h) }
        for p in repo.places.filter({ !$0.isHome }).prefix(5) { _ = await repo.weather(p); _ = await repo.alertsFor(p) }
        _ = await repo.storms()
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }

    static func pollAlerts(repo: Repository) async {
        var targets: [AlertEngine.Target] = []
        for p in repo.places where p.alertPrefs.notify { if let a = try? await NwsSource.alertsForPoint(lat: p.lat, lon: p.lon) { targets.append(.init(label: p.name, prefs: p.alertPrefs, alerts: a)) } }
        for f in repo.favorites where f.alertPrefs.notify { if let a = try? await repo.alertsForFavorite(f) { targets.append(.init(label: f.label, prefs: f.alertPrefs, alerts: a)) } }
        guard !targets.isEmpty else { return }
        let plan = AlertEngine.plan(targets: targets, prefs: repo.prefs)
        for n in plan.notify { await Notifier.postAlert(n.alert, label: n.label, isUpdate: n.isUpdate) }
        for e in plan.ended { await Notifier.postEnded(event: e.event, label: e.label) }
    }

    static func checkEventMode(repo: Repository) async {
        let prefs = repo.prefs
        let storms = (await repo.storms(force: true)).value ?? []
        let hurr = ((try? await NwsSource.alertsAll()) ?? []).filter { $0.event.hasPrefix("Hurricane") }
        let sig = await EventMode.evaluate(storms: storms, hurricaneAlerts: hurr)
        if let active = prefs.eventModeActiveStorm {
            let stillRelevant = sig.eligibleStorm?.id == active || hurr.contains { a in repo.places.contains { p in p.countyFips.map { c in a.sameCodes.contains { $0.hasSuffix(c) } } ?? false } }
            if !stillRelevant, !prefs.eventModeEndPromptDismissed.contains(active) {
                await Notifier.postEventEnd(stormId: active, stormName: storms.first { $0.id == active }?.name ?? "The storm")
            }
        } else if let s = sig.eligibleStorm, prefs.eventModeOffers, !prefs.eventModeDeclined.contains(s.id), !prefs.eventModePromptedFor.contains(s.id) {
            prefs.eventModePromptedFor.insert(s.id)
            await Notifier.postEventOffer(stormId: s.id, stormName: s.name, reason: sig.reason ?? "")
        }
    }
}
