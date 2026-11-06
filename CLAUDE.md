# Touch Weather Tracker — Claude Instructions

## Start here
Read these files before changing the app:
1. `docs/PRODUCT_VISION.md` — approved complete product and feature brief.
2. `docs/DECISIONS.md` — decisions already agreed with the owner.
3. `docs/DEVELOPMENT_PLAN.md` — Android-first implementation order and iOS follow-on.
4. `README.md` and `docs/BRANCH_STATUS.md` — repository and branch handoff.

The product brief and this repository are the source of truth. The owner has already approved the feature direction and asked Claude to use full judgment and autonomy to produce a polished, complete, working first pass. Do not stop for a feasibility proposal or routine approval. Resolve ordinary implementation details independently while preserving agreed scope.

## Platform sequence
- This branch, `claude/android`, is the Android implementation branch. Build and finish Android first.
- Keep platform-independent product behavior, models, service boundaries, and design decisions documented so they can be carried into iOS.
- After the Android app is complete and its required builds and implementation checks pass, build the iOS app on `claude/ios`, based on the completed Android branch. Preserve equivalent features, content, and visual quality while following native iOS conventions.
- Do not begin iOS as a separate product interpretation or reduce its feature set. If a platform restriction requires an adjustment, document the reason and the closest equivalent.
- Never merge to `main`, submit to a store, or publish a release. The owner and Codex will conduct a full app review before any store submission.

## Quality bar
Deliver a complete, installable, professional app. Every represented feature must work end to end. Do not use demos, mock screens, sample-data modes, placeholder flows, fake functionality, or claim unfinished work as complete. Use the agreed real services and product design in the brief. Make errors, stale information, and uncertainty understandable to users.

Follow Android platform conventions, accessible design, privacy expectations, resilience, and battery-conscious background behavior. Use autonomy for choices that improve usability, reliability, and polish without changing the agreed product.

## Repository workflow
- GitHub is the single source of truth. Work only on the relevant Claude branch.
- Inspect the current branch and repository before implementation; do not overwrite owner work.
- Make cohesive, reviewable commits and keep documentation current.
- Use the shared Mundy App Template structure already present here. Read `docs/COMPONENT_REGISTRY.md` and consult Touch-Modules before substantial new functionality; inspect source and compatibility before reusing anything.
- Read `docs/knowledge/README.md` and relevant knowledge files before coding. After substantial work, add an evidence-based report using `docs/knowledge/WORK_REPORT_TEMPLATE.md`. Never fabricate build or test results.
- Build and validate the Android app through the established repository workflow when the implementation is ready. Configure deliverables in `.autobuild/deliver.conf`. Report the branch, commit, build evidence, and any remaining issue accurately.
- Do not expose private information or add credentials to source control.

## App-specific outcome
Implement the full approved scope in `docs/PRODUCT_VISION.md`, including the everyday weather experience, official alerts, storm and hurricane tracking, storm interpretation, saved places, resources, power information, widgets, and approved monetization. The owner expects a premium first pass and will test it before the separate full review ahead of store release.
