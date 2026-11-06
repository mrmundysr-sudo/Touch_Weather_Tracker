# Known issues

1. **Never run on a device.** The build environment has no KVM/emulator; UI, notifications, widgets, billing, ads and location were compiled and packaged but not exercised at runtime.
2. **No active tropical cyclone** existed during development (November). Storm UI, NHC ArcGIS watch/warning styling (attribute names assumed: `HURRICANE/TROPICAL STORM WARNING/WATCH` text or `HWRN/HWCH/TWRN/TWCH`), `CurrentStorms.json` movement units (assumed mph per NHC docs) and event-mode landfall logic were verified only against archived 2026 ATCF files, the NHC sample JSON schema, and unit tests.
3. **ECMWF tracks blocked:** open-data BUFR (`*-360h-oper-tf.bufr`) uses ECMWF local descriptors; no decoder written.
4. **Hub reusable workflow fails** for this public repo (run ids 42267222744, 42270026397: conclusion failure, 0 jobs). Replaced with a self-contained workflow; owner can restore the hub caller if the hub repo's Actions access allows public callers.
5. **Utility territories** come from a third-party ArcGIS mirror of HIFLD (HIFLD's own site is retired); boundaries approximate; many records lack phone/website.
6. **Food/water/charging** has no viable official feed – guidance and links only.
7. **Strings are hard-coded** (not localization-ready).
8. **Resources are not cached offline.**
9. **Maven Central 429s** from the build sandbox's proxy needed retries; CI is unaffected.
10. FEMA shelter feed currently supplies no pet codes, so all shelters show pet policy "not reported".
11. **Locked app = no background alerts (temporary).** Current build: a user who has not unlocked receives no app notifications. The owner has approved the intended behavior — alert notifications continue while locked — and scheduled it for the next update (docs/MONETIZATION.md).
