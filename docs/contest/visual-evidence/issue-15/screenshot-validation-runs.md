# Issue #15 screenshot validation runs

The original two runs used the same command from `android/` after the final UI and golden update:

```bash
bash scripts/gradle-local.sh -Pkotlin.compiler.execution.strategy=in-process \
  validateDemoDebugScreenshotTest --rerun-tasks
```

The aggregate is path-independent: each PNG is hashed, the sorted list of content hashes is hashed again, and no filename or machine path enters the value.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Rendered | Reference after |
| --- | --- | ---: | --- | --- | --- | --- |
| 1 | 2026-11-03 08:00:06–08:00:51 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 2 | 2026-11-03 08:01:02–08:01:47 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 1 ended with `BUILD SUCCESSFUL in 45s` and `26 actionable tasks: 26 executed`; its generated JUnit suite timestamp was `2026-11-03T08:00:49`, with 24 tests and no failures or errors. Run 2 ended with `BUILD SUCCESSFUL in 44s` and `26 actionable tasks: 26 executed`; its generated JUnit suite timestamp was `2026-11-03T08:01:44`, again with 24 tests and no failures or errors. The plugin keeps only the latest generated XML, so this checked-in record preserves both observed executions and their boundary hashes.

The reference directory contains exactly 24 PNGs, and `file` reported every image as 1280×720 RGBA. Neither validation command invokes the screenshot update task.

## Current implementation revalidation

The same read-only command was run twice again from the clean implementation commit `cbb9346eda81e42506856e11b45c17192c9d370c`. No reference update ran between or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 3 | 2026-11-03 08:08:45–08:09:34 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 4 | 2026-11-03 08:09:37–08:10:26 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Each rerun ended with `BUILD SUCCESSFUL in 48s` and `26 actionable tasks: 26 executed`. The latest generated JUnit suite timestamp was `2026-11-03T08:10:24`, with 24 tests and no failures or errors. The unchanged aggregate before and after both runs proves validation did not replace the approved references; the host renderer remains fixture evidence, not physical PLEOS evidence.

## `b6d9dd4` evidence-boundary revalidation

The same read-only command was run twice from clean HEAD `b6d9dd4615f243c3ccd258ff87d436bd74a6c21d` before the documentation-only correction. No reference update ran between or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 5 | 2026-11-03 08:15:19–08:16:07 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 6 | 2026-11-03 08:16:10–08:16:57 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 5 ended with `BUILD SUCCESSFUL in 47s`; run 6 ended with `BUILD SUCCESSFUL in 46s`. Both executed 26/26 actionable tasks. The latest generated JUnit suite timestamp was `2026-11-03T08:16:55`, with 24 tests and no failures or errors. The reference directory still contains exactly 24 PNGs, every image is 1280×720, and the unchanged aggregate proves these validation runs did not update the approved references.

Connected Compose instrumentation was also attempted with `connectedDemoDebugAndroidTest`. It failed with `No connected devices!`, so no device-side result is claimed for the 48/64/72dp, 1.3× bounds/overlap, or offline-empty assertions.

## `7b11512` final revalidation

The same read-only command was run twice from clean HEAD `7b11512489d9401db02a3e0405cc3e99bd754945` before this final documentation correction. No reference update ran between or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 7 | 2026-11-03 08:21:01–08:21:51 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 8 | 2026-11-03 08:21:53–08:22:44 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 7 ended with `BUILD SUCCESSFUL in 49s`; run 8 ended with `BUILD SUCCESSFUL in 50s`. Both executed 26/26 actionable tasks. The latest generated JUnit suite contains 24 tests with no failures or errors. The reference directory still contains exactly 24 PNGs, every image is 1280×720 RGBA, and the unchanged aggregate proves the validation tasks did not update the approved references.

The final worker shell reported `adb unavailable`, so connected instrumentation was not rerun. This preserves the earlier no-device result as an explicit physical-target gap rather than treating compile-only coverage as runtime evidence.

## `b4ff3b3` Sentinel rework validation

Two further read-only runs were executed from base HEAD `b4ff3b331682ae43440dc3ab08c5f402ea73edc0` plus the uncommitted Navi-label implementation and regression tests. No screenshot fixture, catalog, or reference image changed, and no reference update task ran.

| Run | JUnit timestamp (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 9 | 2026-11-03 08:28:00 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 10 | 2026-11-03 08:28:52 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 9 ended with `BUILD SUCCESSFUL in 52s`; run 10 ended with `BUILD SUCCESSFUL in 48s`. Both executed 26/26 actionable tasks. The latest generated suite reports 24 tests with no failures or errors. The reference directory still contains exactly 24 PNGs, all 1280×720 8-bit RGBA, and the aggregate remained unchanged before, between, and after the runs.

The `b4ff3b3` section above is a historical record of the pre-commit handoff. Its implementation and test changes were subsequently committed in `255eafefbdbf6f6859621145aa9f76b2f1306207`; it is not the current repository state.

## `255eafe` final implementation revalidation

The same read-only validation was run twice from clean implementation HEAD `255eafefbdbf6f6859621145aa9f76b2f1306207`. No reference update ran between or during these checks.

| Run | JUnit timestamp (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 11 | 2026-11-03 08:34:59 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 12 | 2026-11-03 08:36:04 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 11 ended with `BUILD SUCCESSFUL in 52s`; run 12 ended with `BUILD SUCCESSFUL in 48s`. Both executed 26/26 actionable tasks, and each generated suite contained 24 tests with no failures or errors. The reference directory contains exactly 24 PNGs, every image is 1280×720, the eight after-image content hashes match the eight day references, and the unchanged aggregate confirms that validation did not replace approved evidence.

## `d1acb14` evidence-commit revalidation

The same read-only validation was run twice from clean evidence commit `d1acb14eb18834e797bf0e1c29e0afefb1a7db3b`, whose runtime implementation remains the `255eafe` baseline. No reference update ran between or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Reference after |
| --- | --- | ---: | --- | --- | --- |
| 13 | 2026-11-03 08:40:21–08:41:17 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 14 | 2026-11-03 08:41:21–08:42:16 | 0 | 24, failures 0, errors 0 | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Run 13 ended with `BUILD SUCCESSFUL in 52s`; run 14 ended with `BUILD SUCCESSFUL in 53s`. Both executed 26/26 actionable tasks. The latest generated suite timestamp is `2026-11-03T08:42:11`, with 24 tests and no failures, errors, or skips. The reference directory still contains exactly 24 1280×720 PNGs, the eight after images remain byte-for-byte equal to their day references, and the unchanged aggregate proves the validation tasks did not update approved evidence.

The prior `.git/index.lock` denials documented above are historical context from earlier workers; subsequent local commits resolved those handoffs.

## `c873b7c` worker revalidation

The read-only validation command was run twice again on 2026-11-06 KST from clean HEAD `c873b7c`. No screenshot update task ran between or during these checks.

| Run | Exit | Tests | Duration | Reference before | Reference after |
| --- | ---: | --- | ---: | --- | --- |
| 15 | 0 | 24, failures 0, errors 0 | 52s | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |
| 16 | 0 | 24, failures 0, errors 0 | 44s | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` | `fdb0f623fe11c4cb7df8f62762d15cc630f842f8b2ac7feaeb5e2f17eddec7e1` |

Both runs executed 26/26 actionable tasks. The latest retained JUnit suite timestamp is `2026-11-06T00:42:59` with 24 tests and no failures, errors, or skips. The directory still contains exactly 24 1280×720 RGBA references. `adb` was unavailable, so connected instrumentation was not run and no device-side accessibility or physical PLEOS result is claimed.

## Sentinel settings and executable-accessibility rework

After visual review, the settings day, night, and day/1.3× references were explicitly updated for the two-column judge overview. No update task ran during or between the two validation runs below.

| Run | Time (Asia/Seoul) | Exit | Tests | Reference before | Rendered | Reference after |
| --- | --- | ---: | --- | --- | --- | --- |
| 17 | 2026-11-06 01:11 KST | 0 | 24, failures 0, errors 0 | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |
| 18 | 2026-11-06 01:13 KST | 0 | 24, failures 0, errors 0 | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |

Run 17 ended with `BUILD SUCCESSFUL in 1m 3s`; run 18 ended with `BUILD SUCCESSFUL in 55s`. Each executed 26/26 actionable tasks. The reference directory contains exactly 24 PNGs and every file is 1280×720 RGBA. A Demo Robolectric Compose suite also executed 5/5 accessibility/layout tests successfully; Android instrumentation still only compiled because both installed-image AVD launch attempts exited before boot with `SIGILL` (132), so no physical-device or PLEOS runtime result is inferred.

## `7cd0995` implementation reconciliation

The same read-only validation was run twice from clean implementation HEAD `7cd0995`. No screenshot update task ran before, between, or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Duration | Reference before | Reference after |
| --- | --- | ---: | --- | ---: | --- | --- |
| 19 | 2026-11-06 01:27:44–01:28:41 | 0 | 24, failures 0, errors 0 | 56s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |
| 20 | 2026-11-06 01:28:51–01:29:45 | 0 | 24, failures 0, errors 0 | 53s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |

Both runs executed 26/26 actionable tasks. The retained JUnit suites reported 24 tests with no failures, errors, or skips. The reference directory remained exactly 24 1280×720 RGBA PNGs, and all eight checked-in after images remained byte-for-byte equal to their canonical day references. Connected instrumentation was not rerun because no working connected target was available; the prior AVD `SIGILL` result remains a physical-device gap, not a passing result.

## `4f15736` Sentinel evidence correction

The read-only validation command was run twice again on 2026-11-06 KST from committed baseline `4f15736a8b2a1abe0032aa2feb0f567c8289c5d8`; `git status --short` was empty before this documentation-only correction. No screenshot update task ran before, between, or during these checks.

| Run | JUnit timestamp (Asia/Seoul) | Exit | Tests | Duration | Reference before | Reference after |
| --- | --- | ---: | --- | ---: | --- | --- |
| 21 | 2026-11-06 01:37 KST | 0 | 24, failures 0, errors 0, skipped 0 | 54s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |
| 22 | 2026-11-06 01:38:46 KST | 0 | 24, failures 0, errors 0, skipped 0 | 56s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |

Both runs executed 26/26 actionable tasks and completed successfully. The reference directory remained exactly 24 PNGs; a `file` scan found no image outside the required 1280×720 RGBA format. Forced Demo/Pleos JVM execution also passed 92/92 and 83/83 respectively, including the Demo Host Compose accessibility/layout suite at 5/5. Connected instrumentation was not run because no working target was available, so Vehicle/Navi callbacks, AWS E2E, signed release behavior, and physical TaskView behavior remain unverified.

## `f27e417` provenance revalidation

The read-only validation command was run twice on 2026-11-06 KST from clean committed implementation/evidence baseline `f27e417cbea8706c7836916642f14f719f404333`. No screenshot update task ran before, between, or during these checks. The resulting provenance correction was subsequently committed as documentation-only successor `74ed09acb49c18c3e9b9cf0bc5893375be8c7163`; `git status --short` was empty before the later overlay touch-target rework began.

| Run | Time (Asia/Seoul) | Exit | Tests | Duration | Reference | Rendered |
| --- | --- | ---: | --- | ---: | --- | --- |
| 23 | 2026-11-06 01:49:51–01:50:42 | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |
| 24 | 2026-11-06 01:50:56–01:51:47 | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |

Both runs executed 26/26 actionable tasks and completed successfully. The reference directory contains exactly 24 PNGs, all 1280×720 RGBA; all eight checked-in after images are byte-for-byte equal to their canonical day references. This evidence record was committed in `74ed09a`, resolving the earlier handoff wording. `adb` was unavailable, so connected instrumentation was not run and Vehicle/Navi callbacks, AWS E2E, signed release behavior, and physical TaskView/System Back behavior remain `미검증`.

## `74ed09a`-based overlay touch-target rework

The read-only validation command was run twice on 2026-11-06 KST from the Sentinel rework working tree based on committed parent `74ed09acb49c18c3e9b9cf0bc5893375be8c7163`. No screenshot update task ran before, between, or during these checks. The rework adds explicit 48dp minimum heights to the recording-stop dialog and Navi guidance-sheet text actions plus host tests that open both overlays.

| Run | JUnit timestamp (Asia/Seoul) | Exit | Tests | Build duration | Reference | Rendered |
| --- | --- | ---: | --- | ---: | --- | --- |
| 25 | 2026-11-06 02:04:02 KST | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |
| 26 | 2026-11-06 02:05:11 KST | 0 | 24, failures 0, errors 0, skipped 0 | 53s | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` | `998ee345de08eaa1b9307bdb9c332527dfe40a0295f694964115ff159c2b9b68` |

Both runs executed 26/26 actionable tasks. The reference directory remained exactly 24 1280×720 RGBA PNGs, and all eight after images remained byte-for-byte equal to their canonical day references. The full forced JVM run passed Demo 94/94 and Pleos 83/83; the Demo count includes seven Host Compose tests and the two new opened-overlay regressions. The commit denial was the state observed by that worker, but the complete rework was subsequently committed as `540d05b2d5b83eeb4736c9676c9db91159ce6de3`; it is not an uncommitted current-tree condition. Connected instrumentation was not run, so Vehicle/Navi callbacks, AWS E2E, signed release behavior, and physical TaskView/System Back behavior remain `미검증`.

## `540d05b`-based offline-draft truthfulness pass

The settings copy was changed from the invented `0개 · 기기에 보존` to `기기에 보존`, and both host and Android tests now require that statement while rejecting `0개`. One explicit update refreshed only the consent/settings day, night, and day/1.3× references. The read-only validation command was then run twice on 2026-11-06 KST from the finalization working tree based on clean committed parent `540d05b2d5b83eeb4736c9676c9db91159ce6de3`; no update task ran between or during these checks.

| Run | Time (Asia/Seoul) | Exit | Tests | Build duration | Reference before | Rendered / reference after |
| --- | --- | ---: | --- | ---: | --- | --- |
| 27 | 2026-11-06 02:19 KST | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` |
| 28 | 2026-11-06 02:20 KST | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` |

Both runs executed 26/26 actionable tasks. Their originally reported `d7c33885…f907c` aggregate sorted image paths before hashing their content-hash list. Recomputing the same unchanged images with the documented path-independent order—extract content hashes, sort those hashes, then hash the list—produces the corrected `56723a70…faea1` value above.

## Final `03fff547` implementation evidence

Two additional read-only validations were run on 2026-11-06 KST from clean committed implementation baseline `03fff547b8c0f823a12138d991d14a8b12466661` with the evidence correction applied. The correction was subsequently committed and is included in the final handoff tree. No screenshot update task was run before, between, or during them.

| Run | JUnit timestamp (Asia/Seoul) | Exit | Tests | Build duration | Reference before / after | Rendered |
| --- | --- | ---: | --- | ---: | --- | --- |
| 29 | 2026-11-06 02:27:16 KST | 0 | 24, failures 0, errors 0, skipped 0 | 50s | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` |
| 30 | 2026-11-06 02:28:15 KST | 0 | 24, failures 0, errors 0, skipped 0 | 47s | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` | `56723a70c0a600ad491b6d57d0b29d07bb208d97363c6ab59d86ebccd61faea1` |

Both runs executed 26/26 actionable tasks. The reference directory contains exactly 24 PNGs, all 1280×720 RGBA, and the rendered aggregate matches the immutable reference aggregate. Forced JVM tests passed Demo 94/94 and Pleos 83/83; repository and Kotlin core checks passed 32/32 and 74/74. Demo/Pleos Android test compilation, lint, and debug assembly also completed successfully. The documentation changes were subsequently committed; final repository cleanliness and the exact handoff commit are verified during PR publication. Connected instrumentation was not run; Vehicle/Navi callbacks, AWS E2E, signed release behavior, and physical TaskView/System Back behavior remain `미검증`.
