# Feature checklist (iOS) — as of this branch head

Legend: **Implemented** = code complete; **Implemented with limits** = works with the stated constraint; **Blocked** = not implemented, reason and next action given.
**Validation reality:** the shared logic (`TWCore`) is compiled and unit/live-tested on Linux (see docs/knowledge/TESTING_HISTORY.md). The SwiftUI app and widget extension can only be
compiled on macOS; they are built by the macOS CI workflow — see the work report for what that run actually showed. **No iOS device or simulator run has been performed by the developer**
(no macOS here), so every screen is unverified at runtime until the owner tests it.

| Area | Status | Notes |
|---|---|---|
| Home + places; address / ZIP / city / device location / map pin; rename, default, remove; tap the home icon to make a place Home (app and widgets follow) | Implemented | Logic tested in TWCore (RepositoryTests); UI untested on device |
| Favorite states & counties | Implemented | |
| Today (conditions, hourly, daily, sunrise/sunset, outlook, urgent look during warnings), offline/stale labelling | Implemented | Same sources/cache rules as Android |
| Alerts feed (Home/Saved/Favorites/All), filters, detail, map link | Implemented | |
| Alert notifications (Home warnings default; per-place types; de-dup; updates; early-end) | Implemented with limits | `UNUserNotificationCenter` local notifications from `BGAppRefreshTask`: iOS decides if/when it runs (no 15-min guarantee), no server push. Time-Sensitive level for warnings; no Critical Alerts (needs an Apple entitlement) |
| Map: USGS basemap, NOAA radar, GOES satellite, warning polygons (tap), NHC cone/track/watch-warning/past track, GFS guidance, buoys, long-press pin | Implemented with limits | MapKit overlays; radar has no published scan time (stated in UI) |
| Storms + plain-language storm report (labels, sources, details panel), recon, buoys, water levels | Implemented | `StormReportBuilder` is the same pure logic as Android, unit-tested |
| GFS model track | Implemented | ATCF `AVNO` |
| ECMWF model track | **Blocked** | Same as Android: BUFR-only free source; UI says none is available and never estimates |
| Event mode (72 h NHC U.S.-landfall test, offer/decline/enable/end prompts) | Implemented with limits | Notification actions on iOS; BGTask cadence is opportunistic. Untested with a live storm |
| Power (utility, MassOutage county/national, ERCOT link) | Implemented with limits | Same third-party HIFLD mirror risk as Android |
| Resources: FEMA declarations, shelters (pet icons), DRCs, food/water/charging guidance, Blue Roof | Implemented with limits | Same limits as Android (no verified food/water feed; Blue Roof via OpenFEMA mission assignments) |
| Widgets: Temp (small + lock screen), Hourly, Today, 5-Day, Saved Place (configurable), Storm Watch | Implemented with limits | iOS has no 1×1/3×1/4×1 widget sizes: closest equivalents are systemSmall, systemMedium, lock-screen accessory families. Everyday widgets follow Home; refresh timing is controlled by iOS. Need the App Group (signed build) to see app data |
| Monetization: whole app locked until 4 rewarded ads (24 h) / purchase / subscription; restore; no banner ads | Implemented with limits | StoreKit 2 + AdMob/UMP code is real. Needs App Store Connect products and the owner's AdMob ids (test ids now). Locked app does no background work (alerts-while-locked is the approved next-update item, same as Android) |
| Onboarding; progressive permissions (notifications, location when tapped) | Implemented | |
| Accessibility (non-color severity, 44 pt targets, labels, dark/light, Dynamic Type via system fonts) | Implemented with limits | No VoiceOver/large-text pass possible without a device |
| Localization-ready strings | **Not done** | Same as Android: text is in code, not string catalogs |
| Screenshots | **Not produced** | No simulator available to the developer; see ios/README.md for the simulator build |
