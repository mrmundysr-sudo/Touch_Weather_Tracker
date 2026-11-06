# Testing history

All entries are results actually observed.

- 2026-11-06 local (Linux, JDK 21, Gradle 8.14.3, AGP 8.7.3, Android SDK 35): `:app:assembleDebug` BUILD SUCCESSFUL, debug APK ≈ 24 MB.
- 2026-11-06 local: `:app:testDebugUnitTest` – ReportAndParserTest (9 tests: ATCF parsing, HDOB/VDM, NDBC missing values, pet-status rule, alert levels, geometry, storm-report omission/labelling) and LiveSourceTest (13 live-service checks) all passed. Live checks printed real data: NWS Houston forecast/conditions/county FIPS 48201, 475 nationwide alerts, Census ZIP/city/address/county lists, ATCF OFCL+GFS tracks for archived storm al102026, NDBC buoys, CO-OPS gauges with predicted-tide departure, FEMA declarations/DRCs/shelters (NM), utility territories, MassOutage county, recon (HDOB/VDM) for archived storm al092026, Census state-crossing landfall test.
- Not run: any emulator/device test, Compose UI tests, instrumented tests, TalkBack, large-font, lint (see KNOWN_ISSUES).
