# Testing history

All entries are results actually observed.

- 2026-11-06 local (Linux, JDK 21, Gradle 8.14.3, AGP 8.7.3, Android SDK 35): `:app:assembleDebug` BUILD SUCCESSFUL, debug APK ≈ 24 MB.
- 2026-11-06 local: `:app:testDebugUnitTest` – ReportAndParserTest (9 tests: ATCF parsing, HDOB/VDM, NDBC missing values, pet-status rule, alert levels, geometry, storm-report omission/labelling) and LiveSourceTest (13 live-service checks) all passed. Live checks printed real data: NWS Houston forecast/conditions/county FIPS 48201, 475 nationwide alerts, Census ZIP/city/address/county lists, ATCF OFCL+GFS tracks for archived storm al102026, NDBC buoys, CO-OPS gauges with predicted-tide departure, FEMA declarations/DRCs/shelters (NM), utility territories, MassOutage county, recon (HDOB/VDM) for archived storm al092026, Census state-crossing landfall test.
- Not run: any emulator/device test, Compose UI tests, instrumented tests, TalkBack, large-font, lint (see KNOWN_ISSUES).
- 2026-11-06 (Home-switching fix) local: new `HomeSwitchingTest` (Robolectric, 4 tests) passed – real Repository/AppViewModel/WidgetKit: making a place Home back and forth keeps exactly one Home, persists it, `repo.selected()` and widget data follow it; browsing another place does not move Home/widgets; removing Home promotes another; same-city places get distinct widget labels. `WidgetPreviewLayoutTest` passed (preview layouts only use RemoteViews-safe elements). **Not verified:** Glance redraw timing and the Today screen on a real device.
- Sandbox note: Robolectric downloads its Android framework jar at test time; the sandbox proxy returned 429 until the jar was fetched manually into `~/.m2`. CI is unaffected.
