# Owner review guide (Android)

Install the debug APK from the AutoBuild artifact (`Touch-Weather-Tracker-debug-<run>`), or build locally (README). The app was never run on a device by the developer – please treat the first launch as the first runtime test and report any crash with the screen you were on.

## Walkthrough
1. **Onboarding** – Welcome → set Home (try a ZIP, “Miami, FL”, an address, and “Use my location”) → notification prompt (choose both ways once) → Today loads.
2. **Today** – temperature, feels-like, hourly strip, daily list (tap a day), conditions grid, sunrise/sunset, outlook. Pull to refresh. Turn on airplane mode and reopen: expect the saved forecast with a red “not current” banner and timestamps.
3. **Places** (top-right icon) – add a second place, rename, set default, per-place Alerts dialog, remove. Add a favorite state and county.
4. **Alerts** – scope chips (Home / Saved / Favorites / All), level chips, filter text; open an alert: detail, “what to do”, “show on map”.
5. **Map** – layers sheet: radar, satellite (visible/IR/water vapor), warnings, tracks, buoys, imagery basemap. Long-press to drop a pin and add it. Read the attribution card.
6. **Storms** – with no active storm you should see the “no active tropical cyclones” state. During hurricane season: storm card → report for a chosen place (labels: Observed / Forecast / Official warning / Model guidance), forecast points, GFS/ECMWF guidance sections, links; event-mode offer card when NHC shows a U.S. hurricane landfall within 72 h.
7. **Resources** – pick a place near an active disaster for best results: FEMA declarations, shelters (pet icons), recovery centers, food/water/charging guidance, Blue Roof, and **Power outage** (utility, MassOutage county/national).
8. **Settings** – notification categories, test notification, event-mode toggle, premium status, data sources.
9. **Locked state** – after onboarding the app shows the unlock screen only: watch an ad (test ad) four times → 24 h of full use (it re-locks when the time ends); buy/subscribe need Play Console setup (docs/MONETIZATION.md). There are no banner ads anywhere.
10. **Widgets** – add all three from the launcher; Saved Place asks which place.

## Known gaps to decide on before store submission
See docs/FEATURE_CHECKLIST.md (ECMWF, strings localization, device-verified accessibility, cached resources) and docs/knowledge/KNOWN_ISSUES.md.
