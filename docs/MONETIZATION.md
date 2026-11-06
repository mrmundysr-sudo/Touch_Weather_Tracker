# Monetization setup

Implemented in `billing/Monetization.kt` (real Google Play Billing 7.1 and AdMob/UMP; no mock purchases or demo unlocks).

## Entitlement rules
- Free: ad-supported. Banner appears only on the Today screen, never during an active warning, never on Alerts / Storms / Resources / Map.
- Four completed rewarded ads → premium for 24 hours (counter resets after unlock).
- One-time **ad-free** purchase → premium permanently. **Subscription** → premium while subscribed.
- Every route unlocks the same set: no ads, GFS/ECMWF track comparison, more than Home + 2 saved places. Warnings, forecasts, storm reports and resources are never gated.
- "Restore purchases" re-queries Google Play (in-app and subscriptions).

## What the owner must configure before any release (none of this exists yet)
1. AdMob app + ad units → set `admobAppId`, `admobRewardedUnit`, `admobBannerUnit` in `gradle.properties` (defaults are Google's published TEST ids).
2. Play Console products for `com.mundymorning.touchweathertracker`: in-app product **`ad_free_premium`**, subscription **`all_apps_subscription`**. Billing only works for builds installed from a Play track by a licensed tester.
3. Consent: UMP is wired; configure the consent message in AdMob.
4. All-apps subscription: a Play subscription belongs to one app. Sharing it across the portfolio requires either a bundle/pass owned by the owner's accounting or a server verifying purchases across apps; this repo deliberately adds no server.

On a sideloaded debug build the paywall states plainly that Google Play billing isn't available; the 4-ad unlock works with test ads.
