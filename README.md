# Touch Weather Tracker

A U.S.-wide everyday weather and severe-weather app for Android and iOS.

- Android implementation: `claude/android` (Kotlin + Jetpack Compose; owner-tested)
- iOS implementation: `claude/ios` (this branch: SwiftUI, built from the Android behavior – see [docs/IOS_HANDOFF.md](docs/IOS_HANDOFF.md) and [ios/README.md](ios/README.md) once present)
- Product brief: [docs/PRODUCT_VISION.md](docs/PRODUCT_VISION.md) · Decisions: [docs/DECISIONS.md](docs/DECISIONS.md) · Plan: [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md)
- **Status and honest feature checklist:** [docs/FEATURE_CHECKLIST.md](docs/FEATURE_CHECKLIST.md) · Owner review guide: [docs/REVIEW_GUIDE.md](docs/REVIEW_GUIDE.md)
- Data sources, terms and refresh rules: [docs/SOURCES.md](docs/SOURCES.md) · Ads/billing setup: [docs/MONETIZATION.md](docs/MONETIZATION.md)

Not for store submission. The owner tests the builds; the owner and Codex complete a full review before any store submission. Nothing here has been merged to `main`, published, or submitted.

## Build (Android)

Requirements: JDK 17, Android SDK platform 35 + build-tools 35, Gradle 8.9+ (no wrapper is committed, matching the other Touch apps).

```bash
export ANDROID_HOME=/path/to/android-sdk   # or put sdk.dir=... in local.properties
gradle :app:assembleDebug                  # -> app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest --tests '*ReportAndParserTest*'   # offline parser/report tests
gradle :app:testDebugUnitTest --tests '*LiveSourceTest*'        # hits the real public services (skipped if offline)
```

CI (`.github/workflows/autobuild.yml`) builds the debug APK and runs the offline tests on every `claude/**` push and uploads the APK as a workflow artifact (`Touch-Weather-Tracker-debug-<run>`).

Ads use Google's *test* AdMob ids by default (`gradle.properties`), so a debug build never serves live ads. See [docs/MONETIZATION.md](docs/MONETIZATION.md) for the ids and Play Console products needed before any release.

## Architecture

```
app/src/main/java/com/mundymorning/touchweathertracker/
  core/     Http (OkHttp, UA, retry), Geo, Units/time formatting
  source/   one adapter per data source (NwsSource, CensusSource, NhcSource+Atcf, ReconSource,
            BuoySources[NDBC,CO-OPS], NowCoast, ResourceSources[FEMA, utilities, MassOutage, Blue Roof])
  data/     Models, Store (atomic JSON files), Prefs, Repository (caching, stale handling, places)
  domain/   StormReportBuilder (pure, unit-tested), EventMode (NHC landfall test)
  work/     AlertEngine (de-dup/updates/early endings), PollWorker, Scheduler, Notifier, event-mode actions
  widget/   Glance widgets: Everyday Weather, Saved Place, Storm Watch
  billing/  Monetization: AdMob rewarded (4 views -> 24 h), UMP consent, Play Billing (ad-free + subscription)
  ui/       Compose: theme, components, Today / Map / Alerts / Storms / Resources / Power / Places / Settings / Paywall
```

Adapters are independent of the UI; replacing a source means replacing one file in `source/` and, if needed, its call in `Repository`.
Everything is cached on-device and shown with its own timestamp; failed refreshes keep the last copy and label it "not current".
There is no developer-owned server, paid API, paid map tile service, or account system.
