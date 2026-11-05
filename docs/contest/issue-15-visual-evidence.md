# Issue #15 visual evidence

## Evidence boundary

- Before revision: `5eba284` (`origin/main` at issue start).
- Committed judge-ready implementation baseline: `03fff547b8c0f823a12138d991d14a8b12466661`. It includes the offline-draft truthfulness fix, its regression tests, and the three refreshed settings references.
- Final evidence correction: the validated implementation baseline is `03fff547`. The documentation correction was subsequently committed and is included in the final handoff tree. Repository cleanliness and the exact handoff commit are verified at PR publication time rather than embedded as a self-referential SHA in this tracked document. No merge, deployment, or release signing is claimed here.
- Canonical fixture condition: 1280×720 physical pixels, landscape, `ko-KR`, font scale 1.0, fixed day/night theme, no system bars.
- Accessibility fixture condition: the same 1280×720 day theme at font scale 1.3 for all eight representative states.
- All images are host-rendered deterministic Compose fixtures, not PLEOS AVD or physical-device captures. Fixtures use constant fake Korean content and abstract route points; they do not open Room, WorkManager, a network, Vehicle, NaviHelper, or a clock.
- No real Vehicle/Navi callback, AWS E2E, signed release, or physical TaskView/System Back success was observed.

## UX specification used for the revision

- Flow and hierarchy: each state leads with the screen purpose, then the current source/capability, decisive metrics, and one next action. Judge-only state selectors and non-functional filters are excluded from the runtime flow.
- Component states: loading, empty, offline-with-cache, offline-without-cache, and error use a shared panel vocabulary while cached content remains browsable. Recording, consent, and detail preserve their task-specific hierarchy.
- Responsive behavior: the target is a fixed 1280×720 landscape viewport. Header controls retain their space, content consumes the remainder, and status/metric rows stack at font scale 1.3 instead of clipping or colliding.
- Accessibility: enabled semantic actions are at least 48dp; the home recording action is at least 64dp and both recording actions are at least 72dp. State meaning always has Korean text in addition to color or an icon.
- Motion: deterministic evidence contains no animated progress or timing-dependent transition. Runtime haptics remain only on the existing highlight action; the revision adds no distracting motion.
- Visual language and copy: existing Hyundai Navy, Route Blue, Mint, Amber, and day/night surfaces are reused. Copy names `데모 데이터`, unavailable integrations, local preservation, and the safe recovery action without implying live success.

## Before / after evidence

The before set was rendered from `5eba284` in a temporary detached source export with the same renderer, locale, theme, density, and 1280×720 viewport. The temporary source and build output were removed after the approved evidence PNGs were copied. The after evidence is copied byte-for-byte from the day golden set.

| State | Before | After | Information and accessibility change |
| --- | --- | --- | --- |
| Home | [before](visual-evidence/issue-15/before/home_content.png) | [after](visual-evidence/issue-15/after/home_content.png) | Removed judge-facing runtime state controls; value, 64dp record CTA, Korean demo source, and vehicle-state badge now lead the hierarchy. Non-functional filter chips are replaced by a passive `전체 기록` scope. Each 232dp card now owns its full measured height, preserving a visible route-art band above a theme-aware text scrim instead of leaving an empty lower half. |
| Recording | [before](visual-evidence/issue-15/before/recording_active.png) | [after](visual-evidence/issue-15/after/recording_active.png) | Status → elapsed time → distance → highlights; `실시간 위치 미사용`; 72dp actions; an adaptive surface restores day contrast and the 1.3× header wraps without clipping. The stop action now uses a contrasting surface with a 2dp Hyundai-primary boundary, so its automotive touch area remains visible. |
| Consent | [before](visual-evidence/issue-15/before/consent_live_unavailable.png) | [after](visual-evidence/issue-15/after/consent_live_unavailable.png) | A landscape two-column overview keeps theme/consent beside all integration and local-preservation facts. At 1.3×, `차량 제어`, `오프라인 초안`, and the AWS/token/location demo boundary are all visible in the initial 720px viewport. |
| Memory detail | [before](visual-evidence/issue-15/before/memory_detail_demo.png) | [after](visual-evidence/issue-15/after/memory_detail_demo.png) | Date/distance/time/highlights are grouped; artwork declares a non-precise demo route; unavailable navigation is guidance, not a success-looking action. Highlight times use a day-specific dark Amber foreground: 8.67:1 on white, 8.08:1 on the day surface, and 7.78:1 on the Amber container. |
| Loading | [before](visual-evidence/issue-15/before/home_loading.png) | [after](visual-evidence/issue-15/after/home_loading.png) | A deterministic hourglass, title, and an explicit saved-record check replace a single line; no invented percentage or animated capture frame is used. |
| Empty | [before](visual-evidence/issue-15/before/home_empty.png) | [after](visual-evidence/issue-15/after/home_empty.png) | Icon, task-oriented explanation, and a 48dp first-drive CTA provide a clear next action. |
| Offline with cache | [before](visual-evidence/issue-15/before/home_offline_cached.png) | [after](visual-evidence/issue-15/after/home_offline_cached.png) | Cloud-off icon, cached-content title, refresh expectation, and browsing remain available together. |
| Error | [before](visual-evidence/issue-15/before/home_error.png) | [after](visual-evidence/issue-15/after/home_error.png) | Icon, stable title, explicit local-record preservation, and a 48dp retry action replace repository text alone. |

PLEOS vehicle status also preserves the decisive cause: `실차 · 주차 신호 없음` is presented as `PLEOS 연결됨 · 주차 신호 미확인`. It is not generalized to a connection success claim. An unavailable Navi status is presented as `차량 내비게이션 사용 불가`.

## Sentinel rework evidence

- The home header and primary controls now own their measured height, while state content receives only the remaining viewport through a weighted container. The offline cache can fill that container without displacing the title, 64dp record action, vehicle status, settings, or scope label.
- A dedicated 1.3× Compose regression asserts all of those home elements plus the offline banner and reconnection copy are visible and inside the fixture. It also asserts that `전체 기록` has no click action.
- Android regressions render with density 1 into an explicitly required 1280×720 fixture and assert that exact pixel extent. A connected target with a different usable viewport now fails instead of silently validating its current screen size.
- Every enabled click semantic in all eight representative states is checked for a minimum 48dp target. Explicit checks retain the 64dp home record and 72dp recording actions; an additional settings fixture covers theme, consent withdrawal, and delete-request controls.
- A Demo-only Robolectric Compose suite executes those semantics and layout measurements on the host at mdpi 1280×720. Its initial five-test form exposed 40dp settings/detail icon actions; the current seven tests pass after those actions and all three overlay text actions were made explicitly 48dp, including optional consent-withdrawal and delete-request actions.
- Theme chips, consent/delete controls, and memory-detail navigation/publish controls now have explicit 48dp minimum heights. The detail controls also have named 48dp regression assertions instead of relying only on the aggregate semantic scan.
- An unavailable live route with a previously persisted `Granted` state now says `현재 수집하지 않음 · 기존 동의 저장됨` next to `동의 철회`. Not-asked or withdrawn states retain `현재 수집하지 않음 · 동의도 저장하지 않음`; a regression asserts that the two statements never appear together.
- `Offline(empty)` has a dedicated regression for `연결이 필요해요`, the absence of cached drives, and its 48dp retry action.
- The former `전체/바다/산/도심/즐겨찾기` no-op filter chips are no longer interactive. The home now states the passive scope (`전체 기록`) and available card tags without promising filtering behavior.
- Unavailable location, voice, and vehicle-control rows use a neutral schedule icon and the Korean status `준비 중 · 사용 불가`; they no longer use a success check icon.
- Memory detail now derives its Navi action label from both capability and request availability. `Unavailable` with a non-null privacy-reviewed request remains `차량 내비게이션 연결 안내`; only `Available` with a request can show `차량 내비게이션으로 보내기`. JVM and Compose regressions cover the previously missing combination.
- The recording-stop dialog actions (`초안으로 저장`, `계속 기록`) and Navi guidance-sheet `확인` action now explicitly retain at least 48dp height. Host Compose regressions open each overlay through its visible parent action and measure all three controls, preventing closed-screen scans from missing Material's smaller default text-button height.
- Settings no longer invents `오프라인 초안 0개` without a runtime count. It states `오프라인 초안` and `기기에 보존`; host and Android regressions require the preservation copy and reject the false count.

## Golden and accessibility contract

- `app/src/screenshotTestDemoDebug/reference`: 24 approved 1280×720 references.
  - 16 canonical references: eight scenarios × day/night at font scale 1.0.
  - 8 accessibility references: all eight scenarios at font scale 1.3 in the day theme.
- `VisualScreenshotTest.kt` is the only preview matrix. The normal validation task reads references and cannot replace them; only `updateDemoDebugScreenshotTest` performs an explicit update.
- After explicit visual review, only the three settings references were updated. Two final independent `validateDemoDebugScreenshotTest --rerun-tasks` runs passed 24/24 without an update before, between, or during them. With the documented path-independent calculation (sort the 24 content hashes, then hash that list), reference and rendered aggregates remained `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1`; details are preserved in [screenshot-validation-runs.md](visual-evidence/issue-15/screenshot-validation-runs.md).
- The 1.3× images were inspected for all representative states. Responsive recording status rows and vertically stacked home-card metrics remove the observed right-edge and metric wrapping defects.
- Android instrumentation retains exact-viewport, fixture-bound, and primary-node overlap assertions for all eight 1.3× states and compiles for Demo and Pleos. The equivalent host Compose suite actually executed and passed seven tests covering all eight scenarios, 48/64/72dp targets, both interactive overlays, 1.3× bounds, passive `전체 기록`, the full settings overview, and optional settings actions. This is host-emulated Compose evidence, not physical-device evidence.

## Reproduction

Run from `android/` with JDK 17:

```bash
# Explicit reference update; review generated PNGs before committing.
bash scripts/gradle-local.sh -Pkotlin.compiler.execution.strategy=in-process updateDemoDebugScreenshotTest

# Read-only regression comparison. Run twice with --rerun-tasks for independence.
bash scripts/gradle-local.sh -Pkotlin.compiler.execution.strategy=in-process validateDemoDebugScreenshotTest --rerun-tasks

# Path-independent aggregate of the 24 reference image contents.
find app/src/screenshotTestDemoDebug/reference -type f -name '*.png' -print0 \
  | xargs -0 shasum -a 256 \
  | awk '{print $1}' \
  | sort \
  | shasum -a 256
```

The screenshot plugin is `0.0.1-alpha16`, upgraded from the arm64-crashing `alpha10` renderer used during the first attempt. The project remains on Kotlin 2.0.21, so only screenshot compilation uses the compiler metadata compatibility flag and excludes the API's transitive Kotlin stdlib; runtime app dependencies are unchanged. The local Gradle launcher also keeps the debug keystore and Android user state under ignored workspace runtime storage, so a clean forced assemble does not write to the host Android home.

## Final implementation revalidation

The full check set below was independently reproduced on 2026-11-06 KST from clean committed implementation baseline `03fff547b8c0f823a12138d991d14a8b12466661` with the evidence correction applied. The correction is included in the final handoff tree. No reference update task was run before, between, or during the two final read-only validations.

| Check | Observed result |
| --- | --- |
| `npm test` (from `android/`) | Passed: 32/32 repository contract tests, including the WCAG contrast calculation and visible stop-action boundary regression. |
| `bash scripts/test-kotlin-core.sh` | Passed: 74 core Kotlin tests. |
| Demo/Pleos JVM unit | Passed after forced execution: Demo 94/94, Pleos 83/83, failures 0; 60/60 Gradle tasks executed. Demo includes seven executed Robolectric Compose accessibility/layout tests, including opened recording and Navi overlays. |
| Demo/Pleos Android test compile | Passed: `compileDemoDebugAndroidTestKotlin` and `compilePleosDebugAndroidTestKotlin`. |
| Demo/Pleos lint | Passed: errors 0. Checks were not disabled. |
| Demo/Pleos debug assemble | Passed. These are debug APKs, not signed-release evidence. |
| Host screenshot references | No update task was run during final revalidation. All 24 existing references are 1280×720; the 8 after images match the day-reference content hashes. |
| Host screenshot validation run 1 | Passed: 24/24, failures/errors/skips 0, full `--rerun-tasks` render in 50s; path-independent reference and rendered aggregates were `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1`. |
| Host screenshot validation run 2 | Passed: 24/24, failures/errors/skips 0, independent `--rerun-tasks` render in 47s; reference and rendered aggregates remained `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1`. |
| Host Compose accessibility | Passed: 7/7 executed at 1280×720 mdpi, including every representative and optional settings action, opened recording/Navi overlays, 64/72dp primary actions, all eight 1.3× bounds, and settings first-viewport facts. |
| Connected Compose/instrumentation | Not run: `adb` was unavailable in the worker shell, so there was no connected target. This is not a passing device result. |

## Remaining physical-device gaps

- Vehicle P/D/R/N callback and a documented parking signal.
- NaviHelper terminal route callback on privacy-reviewed route input.
- Physical PLEOS TaskView focus and System Back delivery.
- AWS authenticated E2E and cancellation behavior against deployed infrastructure.
- Signed release installation, process restart, and long-run Korean text inspection on target hardware.

These gaps are intentionally still `미검증`; none is inferred from the deterministic fixtures, host renderer, debug APKs, or integration source labels.
