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

## Installing on a device with working widgets (owner action)
An unsigned `.ipa` cannot be installed as-is, and a re-signed IPA that lacks the App Group makes the widgets unable to read the app's data (Settings shows a red notice). Requires a paid Apple Developer Program account.

**One-time setup in the Apple Developer portal (developer.apple.com > Certificates, Identifiers & Profiles)**
1. Identifiers > "+" > **App Groups** > register `group.com.mundymorning.touchweathertracker`.
2. Identifiers > "+" > **App IDs** > App: `com.mundymorning.touchweathertracker`; enable **App Groups** and Configure it to select the group above.
3. Same for the widget extension App ID `com.mundymorning.touchweathertracker.widgets` (App Groups enabled, same group).
4. Register your iPhone under Devices (UDID) if you use development signing.

**Build and install from a Mac (simplest)**
1. `brew install xcodegen`, then in `ios/` run `xcodegen generate` and open `TouchWeatherTracker.xcodeproj`.
2. Select the **TouchWeatherTracker** target > Signing & Capabilities > pick your Team; repeat for **TouchWeatherWidgets**. Xcode's automatic signing creates the profiles and keeps the App Group (already declared in the entitlements files generated from `project.yml`).
3. Choose your iPhone and Run. Delete any older copy first. Then long-press the Home Screen > "+" > Touch Weather Tracker to add widgets.

**Re-signing the CI IPA instead**
Sign with a provisioning profile for each bundle id that includes the App Group (the main app's and the `.widgets` extension's, embedded in the `.appex` under `PlugIns/`), and sign the extension and app with entitlements containing `com.apple.security.application-groups = group.com.mundymorning.touchweathertracker`. A tool that signs everything with one profile or strips entitlements will reproduce the "no App Group" notice.

**Check it worked:** Settings > About no longer shows the red App Group notice. Widgets show your Home place after the app has opened once.
The simulator `.app.zip` can be run in the iOS Simulator on a Mac without signing (the simulator has no App Group restriction).

## What the owner must configure before any release
See [../docs/MONETIZATION.md](../docs/MONETIZATION.md): AdMob app id (Info.plist `GADApplicationIdentifier`) and rewarded unit (`Monetization.rewardedUnit`) — Google test ids are used now — and App Store Connect products
`ad_free_premium` (non-consumable) and `all_apps_subscription` (auto-renewable). A privacy-policy URL and subscription terms are required by App Review.
