# Feature checklist (Android) – as of this branch head

Legend: **Implemented** = code complete and compiled/unit-checked here; **Implemented with limits** = works but with the stated constraint; **Blocked** = not implemented, reason and next action given.
**Important:** no emulator/device was available in the build environment (no KVM). UI behavior was compiled, packaged and logic-tested, but has **not been exercised on a device**; the owner's install test is the first runtime test. See docs/knowledge/TESTING_HISTORY.md.

| Area | Status | Notes / next action |
|---|---|---|
| Home + saved places; address / ZIP / city / device location / map pin; edit, remove, default | Implemented | Address/ZIP/city verified against live Census services; GPS & pin untested on device |
| Favorite states & counties | Implemented | County lists from Census TIGERweb (verified for FL) |
| Today: conditions, feels-like, wind/gust, humidity, visibility, pressure, dew point, hourly 24 h, daily, sunrise/sunset, hazardous weather outlook | Implemented | Verified against live NWS for Houston. Rain-last-hour often "none reported" (station dependent) |
| Offline / outage: last forecast with timestamp, "not current" | Implemented | Weather, alerts, storms, national outages cached. **Resources/shelters are not cached** (re-fetch on open) |
| Alerts feed (Home / saved / favorites / all), filters, detail, "no alerts found" wording | Implemented | Verified parsing of 475 live nationwide alerts |
| Alert notifications (Home warnings default; per-place types; de-dup; updates; early-ending notice; no quiet hours) | Implemented with limits | WorkManager ≥15 min & Doze-limited; not instant; **no server push** (would need paid hosting) – documented in-app |
| Radar (NOAA MRMS via RIDGE2 service) | Implemented with limits | Service reports no scan time; NWS radar-display/direct-MRMS fallbacks not built |
| Satellite (GOES visible/IR/water vapor via nowCOAST) with track overlay toggle | Implemented | Verified a live GetMap tile |
| NHC official track, cone, watches/warnings, past track, advisory text, key messages | Implemented with limits | **No tropical cyclone was active during development (Nov)**, so graphics layer field handling (watch/warning colours) is verified only against the service schema, not a live storm; ATCF/advisory parsing verified on archived 2026 storms |
| GFS model track (labelled guidance, separate) | Implemented | ATCF `AVNO`; shown to every unlocked user |
| ECMWF model track | **Blocked** | ECMWF open data publishes tracks only as BUFR (`*-tf.bufr`) with ECMWF local descriptors; needs a table-driven BUFR decoder (or a server). Next action: decide between a vetted BUFR library and a decoder with WMO/ECMWF tables. UI shows "no ECMWF track available" and never estimates |
| Plain-language storm report (what's happening / forecast here / measured nearby / warnings) with source+time labels and details panel | Implemented | Pure builder with unit tests; recon/buoy/gauge verified live; omits missing data |
| Recon (HDOB, VDM, dropsonde MBL wind) | Implemented with limits | Parsers verified on live archive; dropsonde decoding limited to release time/position/mean boundary-layer wind |
| Storm surge | Implemented (official only) | Links to NHC surge products and local NWS warnings; never calculated |
| Event mode (72 h NHC U.S.-landfall test, offer / decline / enable / end prompts, settings) | Implemented with limits | Landfall test = OFCL hurricane-strength ≤72 h crossing a U.S. state (Census) or NWS Hurricane Watch/Warning. Untested with a live storm. 15-min WorkManager cadence, no continuous GPS |
| Power: likely utility, outage map/phone/report, manual correction, MassOutage county + national (attribution, age, no-coverage ≠ zero), ERCOT link for TX | Implemented with limits | Utility boundaries from third-party mirror of HIFLD; many entries lack phone/website |
| Resources: FEMA declarations & programs & deadlines, how to apply | Implemented | |
| Resources: shelters with pet icons (allowed / not allowed / unknown) | Implemented | Live FEMA feed currently reports no pet codes → shows "not reported" (never inferred) |
| Resources: Disaster Recovery Centers | Implemented | |
| Resources: food / water / charging | Implemented with limits | **No viable official live feed**; app links to 211, Feeding America, Red Cross and never lists unverified sites as open |
| USACE Operation Blue Roof | Implemented with limits | Active mission inferred from OpenFEMA USACE mission assignments mentioning roofing; county eligibility/sign-up via USACE (their site blocks automated access) |
| Widgets: 9 styles in the three agreed families — Everyday Weather (Temp Small 1x1, Temp Wide 3x1, Temp Tall 2x3, Hourly 4x2, Today 4x2, 5-Day 4x2), Saved Place (2x2 and 4x1, place chosen per widget, shows top alert), Storm Watch (2x2, grows to larger sizes) | Implemented with limits | Own icon set, day/night/alert backgrounds, update time on every widget, picker previews. **Not seen on a real launcher by the developer.** Everyday family follows the app's selected place. Picker previews are static illustrations. No circular "dial" widget (Glance cannot draw arcs). Android controls refresh timing; locked app shows "Locked" |
| Monetization: app locked until 4 rewarded ads (24 h), buy the app, or all-apps subscription; restore; entitlement state; no banner ads | Implemented with limits | Real Play Billing/AdMob code. **Needs Play Console products and the owner's AdMob ids** (test ids used). Locked app does no background work/notifications (see MONETIZATION.md safety note). Play subscriptions are per-app, so a true all-apps entitlement needs separate owner infrastructure |
| Every unlock route gives the identical complete app | Implemented | Single gate; nothing is gated inside the app |
| Onboarding, progressive permissions (notifications, location when tapped) | Implemented | |
| Accessibility: non-color severity (icon+label), 48 dp targets, content descriptions, dark/light, dynamic text | Implemented with limits | No TalkBack/large-font pass was possible without a device |
| Localization-ready strings | **Not done** | UI text is hard-coded in Kotlin; extracting to `strings.xml` is a mechanical follow-up |
| Final screenshots | **Not produced** | No device; use docs/REVIEW_GUIDE.md |
