# Touch Weather Tracker — iOS (SwiftUI)

Native iOS counterpart of the Android app (`claude/android`, owner-tested), built on `claude/ios`. Android is the behavior reference; see
[../docs/IOS_HANDOFF.md](../docs/IOS_HANDOFF.md) and [../docs/FEATURE_CHECKLIST_IOS.md](../docs/FEATURE_CHECKLIST_IOS.md) for the honest state.

## Layout
```
ios/
  project.yml              XcodeGen definition (the .xcodeproj is generated, not committed)
  TWCore/                  Swift Package: models, data sources, storm report, event mode, repository, alert planner (no Apple-only frameworks;
                           builds and tests on Linux and macOS)
  TouchWeatherTracker/     SwiftUI app: Core/ (app model, StoreKit 2 + AdMob gate, notifications, background refresh, location), UI/ (screens)
  TouchWeatherWidgets/     WidgetKit extension: Temp, Hourly, Today, 5-Day, Saved Place (App Intent), Storm Watch
```
Requirements: Xcode 16+, iOS 17+. Generate the project with `brew install xcodegen && cd ios && xcodegen generate`, then open `TouchWeatherTracker.xcodeproj`.
Shared logic tests: `cd ios/TWCore && swift test` (`--filter ParserTests|RepositoryTests|PresentationTests` for the offline set; `LiveSourceTests` call the real public services and skip if offline).

## CI
`.github/workflows/ios-build.yml` (macOS runner) runs the offline TWCore tests, generates the project, builds for the iOS Simulator and for device **unsigned**, and uploads
`Touch-Weather-Tracker-ios-<run>` (unsigned `.ipa`, simulator `.app.zip`) plus build logs. Nothing is signed, published or submitted.

## Installing on a device (owner action)
An unsigned `.ipa` cannot be installed on an iPhone as-is: iOS requires Apple signing. The owner (who holds the Apple Developer account) must sign it — e.g. in Xcode set the
Team on both targets and run on a device, or re-sign the IPA — and enable the App Group `group.com.mundymorning.touchweathertracker` for both bundle ids
(`com.mundymorning.touchweathertracker` and `…widgets`). Without the App Group, widgets cannot read the app's data (the app says so in Settings).
The simulator `.app.zip` can be run in the iOS Simulator on a Mac without any signing.

## What the owner must configure before any release
See [../docs/MONETIZATION.md](../docs/MONETIZATION.md): AdMob app id (Info.plist `GADApplicationIdentifier`) and rewarded unit (`Monetization.rewardedUnit`) — Google test ids are used now — and App Store Connect products
`ad_free_premium` (non-consumable) and `all_apps_subscription` (auto-renewable). A privacy-policy URL and subscription terms are required by App Review.
