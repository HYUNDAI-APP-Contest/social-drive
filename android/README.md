# Social Drive Android demo

An offline-first visual foundation for the Hyundai app contest with isolated `demo` and `pleos` integration flavors.

## Included flow

- Premium day/night Material 3 theme and Route Memory components
- Home feed, drive detail, distraction-minimized recording, parked publish, and settings
- Fake repository and abstract route points with no precise geographic coordinates
- `Parked`, `Driving`, and fail-closed `Unknown` safety states
- Loading, content, empty, offline, error, permission-denied, and retry content contracts
- On-screen demo controls for every content and upload state
- Confirmed recording stop: parked sessions continue to publish; driving/unknown sessions retain a draft without opening editing
- Explicit actual/demo/unavailable badges and fail-closed Pleos boundaries
- Pleos Vehicle 2.3.2 read-only Gear adapter with permission preflight, fresh-state timeout, and fail-closed `Unknown`; actual P/D/R/N callback evidence is still pending
- Pleos NaviHelper 3.0.5 route adapter with permission preflight, named coordinate validation, bounded initialize/request timeouts, one-waypoint limit, duplicate request exclusion, stale-event quarantine, privacy-safe lifecycle evidence, and fail-closed cleanup
- Honest Coming Soon status for Fused Location, TTS, STT, and Vehicle Control (no dependencies or permissions included)
- Room-backed recording sessions, durable drafts, and a staged upload queue with exported schemas and non-destructive migrations
- A unique WorkManager chain per draft (`create → asset upload → publish`) that resumes after process restart
- Stable `clientRequestId` values, local-original retention through publish acknowledgement, and no persisted presigned URLs or tokens
- A cancellation-aware Parked execution gate around every remote worker stage and process-restart recovery; Driving/Unknown pauses locally and blocks later calls
- Replay-safe asset sessions keyed by stable `clientRequestId + assetId + SHA-256 checksum`; checksum conflicts fail closed
- SDK-independent production cloud core for authenticated create/upload-session/publish/feed/detail/update/delete calls
- Fresh in-memory Bearer sessions per API operation and a separate presigned-object transport that cannot inherit API authorization
- Exact-host, HTTPS, `PUT`, signed content-type, and signed RFC 4648 checksum validation before any object upload
- Room v3 persistence of the non-secret create response version, with publish pinned to that exact optimistic-concurrency version
- Room v4 privacy state, normalized-point retention, and confirmed published-delete pending records
- Room v5 version-conflict snapshots with a draft/client-request lifetime replay counter and generation-CAS fetches
- Room v6 parked-only published-delete tombstones with a stable request hash, CAS worker lease, bounded four-attempt retry, and fail-closed legacy migration
- Explicit parked-only conflict review: no automatic GET, overwrite, version adoption, or generic retry after a 409
- Bounded upload recovery: two presigned-session refreshes per upload run and two durable publish-to-upload rewinds per draft
- Explicit `데모 데이터` source badges across feed, detail, recording, publish, and privacy UI
- Coming Soon-safe live-route consent: unavailable capabilities never persist a false granted state
- Durable privacy withdrawal that stops the future collector boundary, purges only unpublished normalized points, tombstones pending upload work, and preserves published data until a confirmed delete request

## Run

Requirements: JDK 17+, Android SDK 35, and an emulator or device. From this directory:

```bash
./gradlew testDemoDebugUnitTest
./gradlew assembleDemoDebug
./gradlew connectedDemoDebugAndroidTest
```

Open `android/` in Android Studio to run the `app` configuration. The first Gradle run downloads public Android and Compose dependencies. Both wrapper scripts fall back to the pinned Gradle 8.9 distribution when the binary wrapper JAR is absent and verify the official SHA-256 before execution. Do not place SDK secrets or AWS credentials in this project.

For a reproducible command-line setup on macOS/Linux, use the repo-local launcher. It selects JDK 17 (ignoring an inherited JDK 21), keeps Gradle state and temporary files below ignored `android/.gradle/local-runtime`, and only auto-detects the conventional macOS Android SDK directory:

```bash
bash scripts/android-toolchain-doctor.sh
bash scripts/gradle-local.sh --version
bash scripts/gradle-local.sh --offline tasks --all
```

The checkout intentionally does not commit the binary wrapper JAR. If Gradle 8.9 is not already cached, `--offline` now fails immediately without calling the network. One approved online `bash scripts/gradle-local.sh --version` run can bootstrap only the pinned distribution after SHA-256 verification. The doctor is read-only: it does not install SDK packages or inspect credentials. This project requires the exact `platforms/android-35` package; another installed platform does not substitute for it.

Only after `tasks --all` succeeds and lists the variants should the debug assemblies be attempted:

```bash
bash scripts/gradle-local.sh --offline assembleDemoDebug
bash scripts/gradle-local.sh --offline assemblePleosDebug
```

`assembleDemoDebug` validates the public demo boundary. `assemblePleosDebug` additionally requires the approved Pleos Maven artifacts to already exist in the local Gradle cache when offline; neither command signs, deploys, or accesses cloud credentials.

When Android SDK/Gradle dependencies are unavailable, the deterministic repository and safety contract can still be checked with:

```bash
npm test
```

The Android–backend contract harness uses a socket-free client–handler bridge to run the real Kotlin client against the real local Node handler in both unit-test flavors, without AWS credentials or deployment. Gradle itself may still require local IPC that a restricted sandbox can deny:

```bash
bash scripts/test-contract-harness.sh
```

See [the contract harness guide](../docs/android-backend-contract-harness.md) for its privacy evidence, covered failure states, prerequisites, and troubleshooting.

## Submission release pipeline

Release artifacts are fail-closed. `assemble…Release`, `bundle…Release`, generic release assembly, and the supported entrypoint require all five environment variables below; debug, unit-test, and lint tasks do not. Values must be injected by the approved operator and must never be written to shell history, Gradle properties, logs, or this repository.

The local history contains PR #2 merge commit `dcffaad`, but local Git ancestry cannot prove that the merge received the required approval or that `agent:ready` was applied afterward. The orchestrator must confirm both before an operator runs this pipeline.

```text
SOCIAL_DRIVE_RELEASE_STORE_FILE       absolute path outside this repository
SOCIAL_DRIVE_RELEASE_STORE_PASSWORD   secret value
SOCIAL_DRIVE_RELEASE_KEY_ALIAS        approved release alias
SOCIAL_DRIVE_RELEASE_KEY_PASSWORD     secret value
SOCIAL_DRIVE_RELEASE_CERT_SHA256      approved certificate SHA-256
```

With a clean worktree and the approved environment already present, run:

```bash
bash scripts/build-submission-release.sh
```

The script first requires PR #2 commit `dcffaad` to be an ancestor of the clean HEAD. It uses `--no-daemon`, builds both `demoRelease` and `pleosRelease`, verifies each APK with `apksigner`, rejects an Android Debug subject or a certificate fingerprint mismatch, and uses `aapt2` to require the expected Demo/Pleos application IDs. Identical Demo and Pleos APK hashes are rejected. It then writes an atomic release set below ignored `build/submission/<sourceSha>/`. `metadata.json` contains only the source SHA, variant, filename, and final-file SHA-256. Any missing variant, dirty source, changed HEAD, stale destination, invalid keystore location, variant identity failure, or signature failure rejects the entire set. A checksum identifies the built file; it is not a claim of bit-for-bit deterministic Gradle output.

Do not rename the Demo artifact into a submission APK. Only `SocialDrive_pleos_release.apk` may later be promoted to `apk/[팀명]_SocialDrive_release.apk`, and only after the repository's separate publication approval.

### Clean PLEOS AVD evidence

In Pleos Connect Emulator 2.1.0, cold boot the API 34 AVD with wipe/factory reset. Connect only that AVD and do not use an emulator containing a previous `com.socialdrive.app` install. Then run:

```bash
bash scripts/verify-pleos-clean-install.sh \
  --serial <PLEOS_SERIAL> \
  --apk build/submission/<SOURCE_SHA>/SocialDrive_pleos_release.apk
```

The verifier refuses zero or multiple device rows (including offline or unauthorized devices), a serial mismatch, non-PLEOS/API 34/2.1.0 images, an existing package, an incomplete two-artifact metadata contract, checksum/signature mismatch, a launch crash, or a reused process. It performs a fresh `adb install` without `-r`, bounds every adb command, explicitly launches `.MainActivity`, waits for a stable PID and resumed Activity, force-stops it, and requires a different stable PID with no crash exit after relaunch. Its adjacent `pleos-clean-install-evidence.json` is created without overwriting prior evidence and is limited to source/artifact/certificate identifiers, non-sensitive AVD identity, user ID, and successful step status; it never collects logcat, screenshots, coordinates, URLs, or raw vehicle data.

This evidence proves only clean installation and process relaunch. Actual Vehicle P/D/R/N callbacks, NaviHelper route/event callbacks, physical/vendor System Back delivery, actual cloud/Cognito E2E, and AWS deployment remain independent acceptance gates.

In a restricted shell where Gradle, network access, or its local lock-listener socket is unavailable, compile and run the SDK-independent PLEOS lifecycle core with Android Studio's bundled Kotlin compiler:

```bash
bash scripts/test-kotlin-core.sh
```

This compiles the production request controller/coordinator, route consent/withdrawal and conflict-review contracts, and SDK-independent cloud client/worker adapter, then executes their JVM tests. It does not compile Android framework code, Room SQL, Compose UI, the Pleos vendor adapter, a concrete Cognito or HTTP SDK adapter, assemble an APK, call AWS, or replace a Pleos emulator run.

## Real versus demo

| Area | Current behavior | Future boundary |
| --- | --- | --- |
| Route and drive feed | Preset normalized artwork and Korean copy; production DTO/HTTP contract is implemented and fake-transport tested | Configure Cognito mobile client/API hosts, inject a concrete cancellation-cooperative HTTP transport, and add cached feed entities |
| Motion state | Demo flavor simulation | Pleos flavor initializes Vehicle 2.3.2 but remains fail-closed `Unknown`; the public Brake API is not proof of parking |
| Navigation | Demo feedback; fake records contain no coordinates | Pleos flavor initializes NaviHelper 3.0.5 and accepts only validated, privacy-reviewed route input |
| Recording | Room session and draft persistence | Approved sensor adapter writing privacy-minimized samples |
| Route source and consent | Demo presets only; Fused Location is unavailable and no location permission is requested | Inject a reviewed capability token, collector, and permission flow before live collection can exist |
| Publish | Room/WorkManager queue; demo flavor uses an explicit fake, while the actual flavor fails closed until configured | Inject the production cloud adapter with approved Cognito/API settings and a cancellation-cooperative HTTP transport |
| Conflict review | Demo flavor uses a bounded fake owner snapshot; the actual flavor is unavailable | Inject the reviewed owner-detail adapter only after mobile auth/API configuration is approved |
| Published delete | Demo flavor exercises the durable DELETE lifecycle; only backend 204 confirms deletion and the local original remains | Production factory stays unavailable until the same approved auth/API transport is injected; 404 remains `NOT_FOUND_UNCONFIRMED` |

Driving or unknown motion cannot enter detail, publish, or editable settings. AWS keys, precise coordinates, and raw vehicle state must never be added to the APK.
Every current route is a normalized demo preset and stays visibly labeled `데모 데이터`. There is no Fused Location dependency, location permission, or live collector. `LiveAvailable` is guarded by a compile-time test-only opt-in token; production exposes `SDK_COMING_SOON` and does not save a granted consent state. Withdrawal is durable before WorkManager cancellation, so stale workers cannot overwrite the `PRIVACY_WITHDRAWN` queue tombstone. Published deletion requires explicit confirmation and re-reads the authoritative vehicle motion at the mutation boundary; Driving and Unknown return a typed rejection without a database write. Parked persists a Room v6 tombstone before scheduling a unique cancellation-aware worker. The tombstone uses a stable SHA-256 request identity, blocks publish/conflict claims, and retries only offline/timeout/429/5xx failures up to four attempts. Authentication/authorization and every DELETE 409—including a server-marked retryable or unknown conflict code—stop without automatic retry. External WorkManager/app cancellation moves the owned RUNNING lease to `RETRY_WAIT/INTERRUPTED` inside `NonCancellable` before propagating cancellation, so same-process or restart recovery can replay the stable key; motion pause remains a separate zero-attempt path. Because the backend has no deletion receipt, only 204 confirms deletion; owner-hidden 404 becomes `NOT_FOUND_UNCONFIRMED` and never masquerades as success. Confirmation changes only the tombstone—the published local draft and original content remain. The demo flavor has a deterministic fake; the Pleos/production factory remains unavailable until reviewed auth/API configuration is injected.
The Pleos flavor declares the read-only `CAR_DRIVE` permission for Vehicle 2.3.2 Gear selection and `NAVI_ROUTE_SEARCH` for route search. It does not request `CONTROL_CAR_DRIVE`, private vehicle permissions, Fused Location, TTS, STT, or other vehicle-control dependencies. Only `GEAR_PARK` is admitted as parked; every movable gear is driving, while missing permission, unknown values, failures, stale reads, and release remain fail-closed `Unknown`. A standard Android emulator cannot prove Pleos runtime behavior. `PleosGearRuntimeSmokeTest` is skipped unless the runner receives `requirePleosGear=true`; it observes the application-owned actual source and requires a bounded PARK state at the application boundary without logging the raw signal. It does not restart the vendor lifecycle, and its cleanup has a separate deadline. Its presence is not runtime evidence by itself: the matching Pleos Connect Emulator must execute it successfully before the callback path is described as verified.

NaviHelper callbacks do not contain the app request id. Each request therefore installs its own listener closure carrying a lifecycle epoch and request generation. `onRouteStarted` remains non-terminal; `onRoutingCompleted` or `onError` settles the request once after the minimum feedback interval. Missing callbacks time out, remove the listener, and enter a short no-listener quarantine before the next request. Old listener callbacks, old initialization callbacks, and delayed timeout jobs cannot mutate a newer request. Release cancels every job, removes the exact listener instance, releases the helper, and a later initialize creates a new `NaviHelper` object.

The production gateway runs through an SDK-independent coordinator and a narrow vendor port. Lifecycle state is detached atomically before cleanup, while `initialize`, listener registration/removal, route requests, and helper release execute outside the lifecycle lock. In-flight vendor-call accounting defers cleanup until a synchronous SDK callback has returned, preventing release recursion and lock inversion. The same coordinator is exercised by fake-port Kotlin tests with a deterministic scheduler.

`PleosNaviRuntimeSmokeTest` is an opt-in application-boundary test. Normal connected suites skip it; the Pleos Connect Emulator 2.1.0/API 34 runner must grant `NAVI_ROUTE_SEARCH` and explicitly provide `requirePleosNavi=true`. The test uses the production application gateway and production `NaviHelper` adapter, sends one fixed privacy-reviewed public destination, requires `Active` plus `TERMINAL_COMPLETED`, then requires exact listener removal and release within a separate deadline. Its logs contain constant stage names only; the in-memory evidence contains only lifecycle generation, request generation, and an allowlisted stage.

```bash
ANDROID_SERIAL=<PLEOS_API34_SERIAL> ./gradlew \
  :app:connectedPleosDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.socialdrive.app.PleosNaviRuntimeSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.requirePleosNavi=true
```

The issue #28 implementation commit `dd916733a528d933b7804f195d3a00ac4f6e1ba4` was retested on 2026-11-07. It passed 50/50 static/architecture tests, 91/91 SDK-independent Kotlin core tests, 121/121 Demo unit tests, 109/109 Pleos unit tests, and fresh Demo/Pleos debug APK builds. The unit and APK tasks were forced to rerun so these results are attributable to that implementation commit rather than older build output. The suites dynamically cover listener-registration failure, a route-start-only timeout, unknown vendor error mapping, overlong names, production-gateway rejection before listener or request calls, and a release race during terminal cleanup. The opt-in smoke requires successful stages to share one lifecycle/request generation.

The exact opt-in connected task was also attempted against the non-sensitive serial `emulator-5554`. It compiled and packaged the Pleos test APK, then failed before instrumentation because that serial was not connected. Consequently there is no execution 1 / failure 0 / skip 0 result, no observed NaviHelper terminal callback, and no issue #28 contest runtime evidence file. The tested implementation contains local `origin/main` commit `8a068c3b72cf081d59f41686fa0e15d02e398a7b`; this is a local ancestry observation, not proof that the remote main ref was freshly fetched. The orchestrator must provide a connected Pleos Connect Emulator 2.1.0/API 34 and confirm the current remote main before the command can be rerun and runtime evidence recorded. This does not prove a physical vehicle, signed release, AWS, or vendor System Back behavior.

The pinned repository, artifacts, permissions, lifecycle, listener, and route request shapes follow the current official Pleos guides: [Vehicle SDK import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [Vehicle permissions](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [NaviHelper import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [initialization/release](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [event listeners](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), and [waypoint routes](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints).
The instrumented safety suite verifies that Driving/Unknown home states contain no feed or settings controls, a Back delivered directly to the resumed `MainActivity` dispatcher during recording opens the shared stop confirmation, and only the parked flow reaches publish. This is a Compose navigation contract, not proof that a physical or synthetic PLEOS system Back reaches the embedded child task.

| PLEOS Back evidence | Status | Evidence and acceptance boundary |
|---|---|---|
| System Back delivery to the embedded SocialDrive task | **UNRESOLVED** | On the current cold AVD, synthetic Back was observed routing to the Home organizer. Do not count the dispatcher contract test as vendor delivery evidence. |
| MainActivity dispatcher → recording stop dialog | Automated contract | With explicit Parked test content, direct `OnBackPressedDispatcher` delivery renders the production stop confirmation. |
| Physical PLEOS Back control | Manual/vendor integration required | With SocialDrive TaskView focused, press the real control once: the dialog must appear, Home must not replace the app, and recording state must remain. Until this passes, system Back may rehome the app and bypass confirmation; the in-app `기록 종료` CTA remains the separate safe path. |
The publish admission check also fails closed for Driving and Unknown even if it is called outside Compose. Queued work stores only opaque local/remote IDs and retry metadata. The immutable `originalContentRef` and draft row remain until and after a successful publish acknowledgement; cleanup requires a separate future retention policy.
Once a draft enters the queue its editable copy is locked (`state = DRAFT` is required for updates), so a retry cannot upload content different from the frozen local payload. The production API adapter must reissue an upload session using the stable idempotency key, sign checksum and content type, and treat only the same checksum as a replay; presigned URLs remain memory-only.

The cloud core maps the stable local `clientRequestId` to both `clientDraftId` and `Idempotency-Key`. Create always sends a privacy-minimized `COVER + ROUTE` manifest with lowercase hexadecimal SHA-256. The returned remote id and version are stored in Room; publish sends that frozen `expectedVersion` and never performs a GET to adopt a newer server version. Room v3 marks legacy in-flight or failed upload/publish rows that have an id but no version and replaces their chain so the same idempotent create request repairs the version before continuing; completed, published, and deleted rows are never rewound, and a replay that returns a different remote id fails closed. A `VERSION_CONFLICT` stops permanently with the local queue and original intact for explicit user review. Lost-response replay relies on the backend's already-published idempotency behavior, not a client-side version refresh.

The 409 terminal transition is a minimal non-cancellable Room transaction: the current PUBLISH/RUNNING/attempt lease must still own the row before it can set `FAILED/VERSION_CONFLICT` and create or redetect the review record. A replaced worker changes zero rows. Normal worker admission explicitly rejects `FAILED/VERSION_CONFLICT`, so a process crash before WorkManager observes the failure cannot clear the error or republish; transient FAILED rows remain claimable. Only the explicit adopt transaction can move a reviewed conflict back to `PUBLISH/QUEUED`. Review GET is separate from the worker and occurs only after an explicit tap while the observed motion gate is Parked. `DETECTED`/`FETCH_FAILED → FETCHING` increments a durable generation, so a late result from an earlier tap cannot overwrite the current snapshot; there is no automatic retry. Coroutine cancellation generation-CAS normalizes its own request to `FETCH_FAILED/INTERRUPTED`, and startup normalizes any FETCHING row left by process death without issuing a request. Missing and non-owner 404s use the same safe copy. Short-lived detail links never cross the conflict adapter boundary and are not persisted, logged, or placed in work input.

Choosing “최신 버전을 기준으로 다시 게시” revalidates Parked and atomically checks local draft/queue/conflict identity, the same `clientRequestId`, failed PUBLISH stage, a newer server `DRAFT` snapshot, and the lifetime replay limit of two. It changes only `remoteVersion`, queues PUBLISH, and increments the durable replay counter; title, description, original reference, frozen asset inputs, and client identity stay untouched. The commit happens before a publish-only unique-work replacement. Restart recovery schedules only that publish worker for `REPLAY_QUEUED`; it never reruns CREATE or UPLOAD. `DETECTED`, `FETCH_FAILED`, `SNAPSHOT_READY`, and `KEPT_LOCAL` perform no automatic network or replay. A motion transition observed during fetch/adopt/keep cancels cooperative work and prevents the corresponding CAS from committing; this is an observed-process barrier, not a claim of physical vehicle/DB global atomicity.

Upload retry calls `POST /drives/{id}/uploads` once with the complete, identical two-asset manifest; subset refresh is rejected locally. It accepts only the exact configured HTTPS host and `PUT`, then validates the full SigV4 query (`AWS4-HMAC-SHA256`, credential date/region/`s3/aws4_request` scope, signing date, 1–900 second lifetime, lowercase 64-hex signature, and exact signed-header set). Required headers are allowlisted to content type and checksum, and both must be signed alongside `host`. Expiry is checked with a 30-second skew allowance immediately before every PUT. A clock-expired instruction or object `403` refreshes the full manifest at most once more; it cannot loop indefinitely. Worker admission, pause, failure, and stage completion all compare stage/status/attempt leases. Create and publish acknowledgements additionally compare the frozen draft remote id/version inside the same Room transaction. If publish reports `UPLOAD_INCOMPLETE`, the same CAS discipline atomically rewinds the durable row to UPLOAD before replacing the unique chain, at most twice per draft. Stale workers cannot overwrite a newer draft, rewind a completed row, or advance a replacement chain. Restart recovery replaces an unfinished pre-requeue chain when enqueue was interrupted. These Room CAS paths have Android instrumentation coverage, but the bundled Kotlin core command exercises only their SDK-independent state-machine mirror; an SDK/emulator is required to execute the SQLite tests. The object request is built by a transport separate from the authenticated API transport and rejects any `Authorization` header.

API and object transports are cancellation-cooperative, no-redirect, no-log boundaries with a 2 MiB response ceiling. The core enforces the ceiling again and bounds JSON depth, node count, and string size. Drive resources are accepted only with safe ids, supported status, bounded version/metrics/copy, and reasonable ordered timestamps. Feed cursors remain opaque and are forwarded without decoding. Owner-hidden `404`, create idempotency conflict, version conflict, manifest mismatch, incomplete upload, and retryable service errors remain typed without echoing response bodies.

Tokens, presigned/read URLs, request/response bodies, coordinates, and raw vehicle data are neither logged nor written to Room/WorkManager. Sensitive transport objects have redacted diagnostics. There is intentionally no embedded Cognito app client, API base URL, upload host, client secret, or AWS credential. Until those mobile settings and a concrete HTTP transport are approved and injected, the `pleos` flavor uses `UnavailableRemoteDriveApi` and never silently falls back to demo data.

The current local `DraftSnapshot` does not yet carry the recording's highlight count, so the disabled production adapter deliberately sends `highlightCount = 0`. The actual flavor must remain unavailable until approved derived publication metadata (including the local highlight count) is frozen into the draft and tested; it must not infer or upload raw location or vehicle telemetry.

The motion gate coordinates the motion state observed by this process with cancellation-cooperative remote child jobs; it cannot make a physical vehicle signal and a network service globally atomic. After a Driving/Unknown transition is observed, no new remote block starts, in-flight work receives best-effort cancellation, and stable idempotency makes a replay safe if the service had already accepted the request. Motion pause ends the current WorkManager chain without consuming retry backoff. A later Parked transition waits for the pause record, then immediately replaces the unique chain at the same persisted stage and `clientRequestId`; only one replacement chain remains runnable.
