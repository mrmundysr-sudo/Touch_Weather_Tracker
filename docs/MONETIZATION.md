# Monetization setup

Implemented in `billing/Monetization.kt` and `ui/SettingsScreens.kt` (`PaywallScreen`): real Google Play Billing 7.1 and AdMob/UMP; no mock purchases or demo unlocks.

## Model (owner decision, 2026-11-06)
- **The app is locked** until the user has an entitlement. Locked = the paywall/unlock screen is the only thing shown after onboarding; the app does no background refresh, sends no notifications, and widgets show "Locked".
- Three ways to unlock, all giving the same complete app:
  1. Watch **4 completed rewarded ads** → full use for **24 hours** (the counter resets after unlocking; the app re-locks when the 24 hours end, checked every 15 s while open).
  2. **Buy the app** (one-time Play in-app product) → unlocked permanently on that Google account.
  3. **Subscribe to all apps** (Play subscription) → unlocked while subscribed.
- **There are no banner or interstitial ads.** The only ads are the rewarded ads the user chooses to watch on the unlock screen.
- "Restore purchases" re-queries Google Play (in-app and subscriptions). Billing state is cached and re-checked on every launch.

## Safety note for the owner
Because a locked app sends no notifications and refreshes no data, a user who has not unlocked gets no app alerts. The unlock screen tells them to keep Wireless Emergency Alerts on. This follows your instruction that the app doesn't work without unlocking; if you'd rather keep Home-warning notifications running while locked, it's a one-line change in `PollWorker` (remove the `premiumActive` check) — your call.

## What the owner must configure before any release (none of this exists yet)
1. AdMob app + a **rewarded** ad unit → set `admobAppId` and `admobRewardedUnit` in `gradle.properties` (defaults are Google's published TEST ids, so debug builds never serve live ads).
2. Play Console products for `com.mundymorning.touchweathertracker`: in-app product **`ad_free_premium`**, subscription **`all_apps_subscription`**. Billing only works for builds installed from a Play track by a licensed tester.
3. Consent: UMP is wired; configure the consent message in AdMob. Ads can't load until consent allows it; the unlock screen then points users to buy/subscribe.
4. All-apps subscription: a Play subscription belongs to one app. Sharing it across the portfolio requires a mechanism you own (Play's multi-app options or a server that verifies purchases across apps); this repo deliberately adds no server.

On a sideloaded debug build the unlock screen states plainly that Google Play billing isn't available; the 4-ad unlock works with test ads.
