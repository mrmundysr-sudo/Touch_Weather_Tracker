# Development plan

## Approved outcome

Build the complete professional first pass in PRODUCT_VISION.md. No separate planning proposal or routine approval checkpoint is required.

## Android first — `claude/android`

Implement the full experience: app foundation and design system; onboarding and places; everyday weather; alerts; storm/hurricane tracking and model comparison; maps, radar, satellite, and track overlays; plain-language storm report; FEMA and local recovery resources; power information; widgets; event notifications; monetization; settings, accessibility, and resilience. Produce an installable build through the established Android workflow, fix implementation/build failures, and report evidence accurately.

Acceptance: each agreed feature works end to end; no demos, mocks, placeholders, or unfinished flows; Android builds and is ready for owner installation and testing.

## iOS second — `claude/ios`

After Android is complete, implement its equivalent as a polished native iOS experience, using the finished Android implementation as the behavior/product reference and adapting to iOS conventions. Preserve feature coverage, source behavior, alerts, resources, widgets, and monetization. Build and validate with the established iOS workflow for owner testing.

Acceptance: iOS is complete and installable for owner testing, with substantive feature parity and no demo/placeholder flows.

## Review/release

Owner testing and a full review with Codex happen before any store submission. Never merge to `main`, submit, publish, or claim review readiness before the review is completed.
