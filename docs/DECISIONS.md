# Approved decisions

The full requirements are recorded in [PRODUCT_VISION.md](PRODUCT_VISION.md).

- Touch Weather Tracker is a polished U.S.-wide everyday weather and severe-weather companion.
- Implement Android first on `claude/android`; then build iOS on `claude/ios` from the completed Android implementation, preserving feature parity and polish.
- Support Home plus several saved places, address/ZIP/device location, and favorite states/counties.
- Include everyday forecasts, NWS alerts, official NHC hurricane products, selectable U.S. GFS and European ECMWF track comparison, radar/satellite views, and an understandable storm report.
- Integrate FEMA resources, shelters with pet status, food/water/charging, USACE Blue Roof, local power utility outage/contact information, and national MassOutage fallback plus ERCOT information.
- Include the agreed three widgets and event-based hurricane notification prompts.
- Monetization: ads-supported free, 4 ads unlock all features for 24 hours, paid ad-free, and subscription to all apps; every premium route unlocks the same feature set.
- Deliver fully working product flows; no demos, mock functionality, sample modes, or placeholders.
- Owner will test. Codex and owner will conduct a full app review before any store submission. Do not submit or publish.

Claude has autonomy to make product and engineering decisions that improve quality while keeping to this approved feature set.

## Implementation decisions (Android, 2026-11-06)
- **Premium feature set (identical on every route):** no banner ads, GFS/ECMWF model-track comparison, and more than Home + 2 saved places. Warnings, forecasts, storm reports, resources and widgets are never gated. Banner ads appear only on Today and never while a warning is active.
- **Map base:** USGS The National Map (free, public domain) – avoids OpenStreetMap tile-usage limits and paid tiles. Radar via NOAA MRMS from the NWS RIDGE2 map service; satellite via NOAA nowCOAST WMS (GOES).
- **Location search:** U.S. Census Geocoder + TIGERweb only (no paid geocoder).
- **ECMWF:** not faked – blocked pending a BUFR decoder (see FEATURE_CHECKLIST).
- **Blue Roof:** no scraping of the blocked USACE site; use OpenFEMA USACE mission assignments to indicate an active mission.
- **Notifications:** on-device polling with WorkManager (no server); documented as non-instant.
- **CI:** the hub reusable workflow produced zero jobs for this public repo, so `autobuild.yml` is self-contained.
