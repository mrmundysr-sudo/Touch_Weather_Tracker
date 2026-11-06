# Work report — Touch Weather Tracker iOS first pass

- Date: 2026-11-06
- Repository and branch: mrmundysr-sudo/Touch_Weather_Tracker, `claude/ios` (Android `claude/android` merged in as the behavior reference)
- Code under test: `7a44b0e` (run 42308851015) plus later commit `c4a6d06` (run described below)
- Scope: iOS 17+ SwiftUI app with feature parity to the owner-approved Android app; widgets; monetization lock model.

## Changes
- `ios/TWCore` Swift package: models, NWS/Census/NHC(ATCF)/NDBC/CO-OPS/FEMA/utility adapters, pure-Swift gzip inflate, repository/cache, storm-report builder, presentation helpers. Same sources and rules as Android.
- `ios/` XcodeGen project: app (Today, Map, Alerts, Storms, Resources, Power, Places with home-icon toggle, Settings, Unlock/lock gate), WidgetKit extension (Temp, Hourly, Today, 5-Day, Saved Place, Storm Watch), StoreKit 2, AdMob rewarded + UMP, local notifications, BGAppRefresh.
- Owner decisions carried over: no banner ads; whole app locked until 4 rewarded ads (24h) / purchase / all-apps subscription; locked app does no background work; alerts-while-locked deferred.
- `.github/workflows/ios-build.yml`: macOS CI build, tests, simulator UI test, screenshots.

## Validation (actually run, on GitHub macOS runner, Xcode 16)
- Run 42308851015 (`7a44b0e`): success — TWCore tests (parsers, repository), XcodeGen generate, simulator build, unsigned device build + IPA package, XCUITest in iPhone simulator, artifacts uploaded.
- UI test drove real onboarding (ZIP/address search against the live Census geocoder) and every tab with live NWS/NHC/FEMA data; screenshots reviewed (published as check-run annotations).
- CI-only widget gallery renders the real widget views with real cached data at medium/large/small sizes; reviewed for layout.
- Defects found by those screenshots and fixed: invisible weather icons on light cards, status bar overlapping scrolled content (scrim; verified fixed in the latest run), ZIP place naming, truncated welcome text, widget hourly cells wrapping, Today-large widget empty space.
- Linux-side: TWCore tests incl. live-source checks run before pushing.

## NOT validated
- No physical device or real home-screen widget/notification/background-refresh run. Widgets were reviewed as views in the simulator, not on a launcher.
- StoreKit purchases/subscription and AdMob rewarded flow (only Google test ad unit ids are configured) are not exercised end to end; they need App Store Connect products and an AdMob app id.
- Unsigned IPA only: no signing, App Group entitlement, or push/background capabilities have been tested. The owner must sign (Apple Developer team) to install on a device.
- Night-time Today widget shows "H —" when NWS has no daytime high left (known minor issue).
- Accessibility (VoiceOver/Dynamic Type), localization and offline resource caching not tested.

## Handoff
- Owner: sign/install the build (see ios/README.md), set real AdMob ids and StoreKit products, add a privacy-policy URL, then follow docs/REVIEW_GUIDE.md on device.
- No merge to `main`, release, or store submission has occurred.
