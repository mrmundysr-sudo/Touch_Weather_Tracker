# iOS branch handoff

This branch is reserved for the iOS counterpart. Android is implemented first on `claude/android`.

## Start condition and source of truth
Before coding iOS, verify that Android is complete, installable, and validated. Bring the completed Android implementation into this branch without discarding either branch's commits. Inspect the resulting history and working tree before making changes. The approved product brief remains `docs/PRODUCT_VISION.md`.

Use the Android app as the authoritative reference for product behavior, feature coverage, interpretation rules, and navigation. Implement native iOS experiences and follow iOS design conventions while preserving equivalent scope and quality.

## Expectations
- Implement every agreed feature; do not use demo, mock, sample-data, or placeholder behavior.
- Keep data handling, source attribution, alert logic, saved places, resources, widgets, and monetization consistent with Android.
- Document any unavoidable iOS-specific difference and its closest equivalent.
- Build and validate using the established iOS workflow for owner testing.
- The owner and Codex will perform a full review before any store submission. Do not submit or publish.
