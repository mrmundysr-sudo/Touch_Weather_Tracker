# iOS handoff

`docs/IOS_HANDOFF.md` did not exist on `claude/android` when work began (the assignment referred to it); this file is its first version. iOS work happens on `claude/ios` **only after Android is accepted**; the iOS app must preserve behavior, data-source choices, wording and safety rules below, using native SwiftUI/WidgetKit/StoreKit 2/BGTaskScheduler/UserNotifications conventions.

## Behavior reference
Android `app/src/main/java/.../` is the reference. Port by layer: `source/` (HTTP adapters, same endpoints), `domain/` (StormReportBuilder, EventMode – pure logic, copy rules and wording exactly), `data/` (cache + stale handling), then UI.

## Rules that must carry over
- Observed vs Forecast vs Official warning vs Model guidance labels; omit stale/missing data, never zero-fill; never compute surge or invent risk.
- NHC OFCL/cone is the official forecast; GFS/ECMWF only as separately labeled optional guidance (premium).
- Alerts: warnings only for Home by default; de-dup by id+signature; notify on update and early end; no quiet-hours suppression.
- Event mode offer: NHC official hurricane-strength forecast crossing a U.S. state within 72 h, or NWS Hurricane Watch/Warning; respect declines; end prompt (Return to normal / Keep).
- Pets: allowed / not allowed / unknown (never inferred). MassOutage attribution and “unofficial” wording; missing coverage ≠ zero.
- Monetization: whole app locked until 4 rewarded ads (24 h of use) / purchase / all-apps subscription; no banner ads; identical complete app on every route; locked app does no background work and says to keep WEA on.
- No server, no paid API/tiles. Cache everything with timestamps.

## iOS platform adaptations to document when made
- Background: BGAppRefreshTask is opportunistic (not a 15-min guarantee); no event-mode cadence promise. Server push is unavailable without hosting – say so in-app.
- Maps: MapKit overlays with the same tile URL templates (radar export, nowCOAST WMS, USGS basemap) via `MKTileOverlay`.
- WidgetKit timelines instead of Glance; StoreKit 2 products equal to the Play ids (`ad_free_premium`, `all_apps_subscription`); AdMob iOS SDK + UMP.
- iOS cannot be built or tested in the Linux build environment; validate through a macOS runner/Xcode and report only what actually ran.

## Blocked / open items inherited from Android
ECMWF tracks (BUFR), localization strings, food/water/charging live feed, hub reusable workflow failing for this public repo.
