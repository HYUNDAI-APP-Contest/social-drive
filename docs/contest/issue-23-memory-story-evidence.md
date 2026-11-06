# Issue #23 memory-story evidence

## Product outcome

This change turns the existing abstract route line into one continuous, privacy-safe story across Home, Recording, Detail, and Publish:

- Home gives coast, mountain, and city drives distinct normalized routes and visual palettes instead of repeating one generic card treatment.
- Recording makes each `방금 좋았어` action visible on the route artwork and gives immediate Korean confirmation that the moment was saved for the parked story flow.
- One saveable presentation identity now carries the same demo route and recorded highlight count through Recording → local draft → Publish → Detail/back. The local draft resolves its count from the durable recording row instead of reverting to the repository fixture count.
- Detail connects each highlight card to a numbered marker and updates the artwork story when a moment is selected.
- Publish reuses that same route-and-moment language as the representative scene instead of showing the former `ROUTE ARTWORK` placeholder.
- The dead passive scope/tag copy was removed. No filter behavior is implied when no filter exists.

The artwork is generated only from normalized `RoutePoint` values and theme tokens. It does not use map tiles, raw coordinates, place names, photographs, live location, Vehicle data, or cloud data. Every affected runtime fixture still says `데모 데이터`; this is not evidence of a live PLEOS or AWS integration.

## Reviewed visual matrix

The source-owned golden set contains 27 deterministic 1280×720 RGBA references:

- 9 scenarios in day mode at font scale 1.0.
- The same 9 scenarios in night mode at font scale 1.0.
- The same 9 scenarios in day mode at font scale 1.3.

All 27 references were individually inspected after the explicit update. The four story-critical views preserve first-viewport hierarchy, Korean readability, and action visibility in all three variants:

- [Home day](../../android/app/src/screenshotTestDemoDebug/reference/com/socialdrive/app/VisualScreenshotTestKt/HomeContentDay_3d78f1e1_0.png)
- [Recording day](../../android/app/src/screenshotTestDemoDebug/reference/com/socialdrive/app/VisualScreenshotTestKt/RecordingActiveDay_3d78f1e1_0.png)
- [Memory detail day](../../android/app/src/screenshotTestDemoDebug/reference/com/socialdrive/app/VisualScreenshotTestKt/MemoryDetailDemoDay_3d78f1e1_0.png)
- [Publish day](../../android/app/src/screenshotTestDemoDebug/reference/com/socialdrive/app/VisualScreenshotTestKt/PublishDraftDay_3d78f1e1_0.png)

The path-independent aggregate of the 27 reference image content hashes is `a1d90d9b875ddec6f8ac45680cb4a7b87ed16e60d927f0e87d9134770b5eac76`.

## Safety and accessibility contract

- Driving/Unknown navigation restrictions, the recording stop flow, and parked-only publishing behavior are unchanged.
- Route artwork clamps normalized values and exposes a Korean semantic description without precise position data.
- Highlight selection is represented by marker shape/size and text, not color alone.
- Existing 64dp Home and 72dp Recording primary targets remain intact.
- Publish back, consent, and state actions explicitly retain a minimum 48dp target.
- Day, night, and 1.3× fixtures keep the story content and primary actions inside the 1280×720 viewport.
- Every Home state keeps the full title, 64dp record action, vehicle status, and settings action. The offline cache grid consumes only the space remaining below its banner, preventing its content from displacing the standard header.
- The immediate recording feedback is interaction evidence covered by the host Compose test; it is not timing-dependent golden content.

## Observed checks

| Check | Observed result |
| --- | --- |
| Final explicit `updateDemoDebugScreenshotTest` | Passed after the continuity/header rework. The 27 refreshed references were visually reviewed before read-only validation; no update ran between the two final validations. |
| Visual inspection | Passed: all 27 Day/Night/1.3× references individually reviewed; no story-critical clipping, unreadable contrast, or missing action observed. |
| Focused presentation and host checks | Passed: `MemoryStoryPresentationTest` 2/2 and `VisualAccessibilityHostTest` 10/10. They cover route/count identity continuity, mismatch fail-closed behavior, 48/64/72dp targets, recording feedback, detail moment selection, and the complete standard Home header for every Home state across Day/Night/1.3×. |
| `npm test` from `android/` | Passed: 33/33 repository policy/static tests. |
| `bash scripts/test-kotlin-core.sh` | Passed: 74/74 SDK-independent Kotlin tests. |
| Demo/Pleos JVM unit | Passed: Demo 99/99 and Pleos 85/85, failures/errors/skips 0. |
| Demo/Pleos debug assemble | Passed; `app-demo-debug.apk` and `app-pleos-debug.apk` were produced. These are debug artifacts, not signed release evidence. |
| Combined final Gradle pass | Passed: Demo/Pleos JVM unit, both debug APK assembles, and screenshot validation; `BUILD SUCCESSFUL`, 98 tasks. |
| Screenshot validation run 1 | Passed: 27/27, failures/errors/skips 0 with `--rerun-tasks`; no update task ran. |
| Screenshot validation run 2 | Passed independently: 27/27, failures/errors/skips 0; no update task ran. Reference and rendered aggregates both remained `a1d90d9b875ddec6f8ac45680cb4a7b87ed16e60d927f0e87d9134770b5eac76`. |
| `git diff --check` | Passed. |

The explicit reference update was not treated as validation. Both read-only validations ran afterward without an intervening update.

## Known evidence boundary

- The references and host Compose checks are deterministic host-rendered UI evidence, not physical PLEOS display captures.
- The memory artwork demonstrates a product story with fake normalized data. It does not prove Vehicle, live route collection, NaviHelper terminal callbacks, cloud upload, or authenticated backend behavior.
- A separate PR #22 baseline smoke at commit `96f7663` used the `issue15_accessibility` PLEOS Connect 2.1.0/API 34 arm64 AVD (1024×768) with Vehicle Service v2.3.22 code 74. Its Pleos debug APK built, installed fresh, cold-started with `Status: ok` and no observed FATAL, then manually completed safe Recording → highlight 0→1 → stop confirmation → save draft → locked Home preservation. That smoke predates this issue and does **not** verify the issue #23 artwork, gear callback, NaviHelper, or AWS.
- PLEOS physical/system Back delivery remains a separate unresolved integration item documented by the Android baseline; this UI package does not change or claim it.
- No push, merge, deployment, credential access, signing, or production mutation is part of this issue.
