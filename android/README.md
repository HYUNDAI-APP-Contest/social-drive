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
- Pleos Vehicle 2.3.2 lifecycle-only integration; no permissioned Vehicle read is made and actual motion remains `Unknown`
- Pleos NaviHelper 3.0.5 route adapter with named coordinate validation, one-waypoint limit, duplicate request exclusion, request timeout, bounded stale-event quarantine, and privacy-safe errors
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

When Android SDK/Gradle dependencies are unavailable, the deterministic repository and safety contract can still be checked with:

```bash
npm test
```

In a restricted shell where Gradle, network access, or its local lock-listener socket is unavailable, compile and run the SDK-independent PLEOS lifecycle core with Android Studio's bundled Kotlin compiler:

```bash
bash scripts/test-kotlin-core.sh
```

This compiles the production request controller/coordinator, route consent/withdrawal state machine, and SDK-independent cloud client/worker adapter, then executes their JVM tests. It does not compile Android framework code, Room SQL, Compose UI, the Pleos vendor adapter, a concrete Cognito or HTTP SDK adapter, assemble an APK, call AWS, or replace a Pleos emulator run.

## Real versus demo

| Area | Current behavior | Future boundary |
| --- | --- | --- |
| Route and drive feed | Preset normalized artwork and Korean copy; production DTO/HTTP contract is implemented and fake-transport tested | Configure Cognito mobile client/API hosts, inject a concrete cancellation-cooperative HTTP transport, and add cached feed entities |
| Motion state | Demo flavor simulation | Pleos flavor initializes Vehicle 2.3.2 but remains fail-closed `Unknown`; the public Brake API is not proof of parking |
| Navigation | Demo feedback; fake records contain no coordinates | Pleos flavor initializes NaviHelper 3.0.5 and accepts only validated, privacy-reviewed route input |
| Recording | Room session and draft persistence | Approved sensor adapter writing privacy-minimized samples |
| Route source and consent | Demo presets only; Fused Location is unavailable and no location permission is requested | Inject a reviewed capability token, collector, and permission flow before live collection can exist |
| Publish | Room/WorkManager queue; demo flavor uses an explicit fake, while the actual flavor fails closed until configured | Inject the production cloud adapter with approved Cognito/API settings and a cancellation-cooperative HTTP transport |

Driving or unknown motion cannot enter detail, publish, or editable settings. AWS keys, precise coordinates, and raw vehicle state must never be added to the APK.
Every current route is a normalized demo preset and stays visibly labeled `데모 데이터`. There is no Fused Location dependency, location permission, or live collector. `LiveAvailable` is guarded by a compile-time test-only opt-in token; production exposes `SDK_COMING_SOON` and does not save a granted consent state. Withdrawal is durable before WorkManager cancellation, so stale workers cannot overwrite the `PRIVACY_WITHDRAWN` queue tombstone. Published deletion requires explicit confirmation and persists only `DELETE_PENDING`; this implementation intentionally makes no delete network call.
The Pleos flavor declares only `NAVI_ROUTE_SEARCH`. Vehicle is initialized and released but no permissioned Vehicle API is read, so `CAR_DRIVE` is intentionally absent. It does not include Fused Location, TTS, STT, or vehicle-control dependencies. A standard Android emulator cannot prove Pleos runtime behavior; use the matching Pleos Connect Emulator and approved SDK access before making a release claim.

NaviHelper callbacks do not contain the app request id. Each request therefore installs its own listener closure carrying a lifecycle epoch and request generation. `onRouteStarted` remains non-terminal; `onRoutingCompleted` or `onError` settles the request once after the minimum feedback interval. Missing callbacks time out, remove the listener, and enter a short no-listener quarantine before the next request. Old listener callbacks, old initialization callbacks, and delayed timeout jobs cannot mutate a newer request. Release cancels every job, removes the exact listener instance, releases the helper, and a later initialize creates a new `NaviHelper` object.

The production gateway runs through an SDK-independent coordinator and a narrow vendor port. Lifecycle state is detached atomically before cleanup, while `initialize`, listener registration/removal, route requests, and helper release execute outside the lifecycle lock. In-flight vendor-call accounting defers cleanup until a synchronous SDK callback has returned, preventing release recursion and lock inversion. The same coordinator is exercised by fake-port Kotlin tests with a deterministic scheduler.

The pinned repository, artifacts, permissions, lifecycle, listener, and route request shapes follow the current official Pleos guides: [Vehicle SDK import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [Vehicle permissions](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [NaviHelper import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [initialization/release](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [event listeners](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), and [waypoint routes](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints).
The instrumented safety suite verifies that Driving/Unknown home states contain no feed or settings controls, system Back during recording opens the shared stop confirmation, and only the parked flow reaches publish.
The publish admission check also fails closed for Driving and Unknown even if it is called outside Compose. Queued work stores only opaque local/remote IDs and retry metadata. The immutable `originalContentRef` and draft row remain until and after a successful publish acknowledgement; cleanup requires a separate future retention policy.
Once a draft enters the queue its editable copy is locked (`state = DRAFT` is required for updates), so a retry cannot upload content different from the frozen local payload. The production API adapter must reissue an upload session using the stable idempotency key, sign checksum and content type, and treat only the same checksum as a replay; presigned URLs remain memory-only.

The cloud core maps the stable local `clientRequestId` to both `clientDraftId` and `Idempotency-Key`. Create always sends a privacy-minimized `COVER + ROUTE` manifest with lowercase hexadecimal SHA-256. The returned remote id and version are stored in Room; publish sends that frozen `expectedVersion` and never performs a GET to adopt a newer server version. Room v3 marks legacy in-flight or failed upload/publish rows that have an id but no version and replaces their chain so the same idempotent create request repairs the version before continuing; completed, published, and deleted rows are never rewound, and a replay that returns a different remote id fails closed. A `VERSION_CONFLICT` stops permanently with the local queue and original intact for explicit user review. Lost-response replay relies on the backend's already-published idempotency behavior, not a client-side version refresh.

Upload retry calls `POST /drives/{id}/uploads` once with the complete, identical two-asset manifest; subset refresh is rejected locally. It accepts only the exact configured HTTPS host and `PUT`, then validates the full SigV4 query (`AWS4-HMAC-SHA256`, credential date/region/`s3/aws4_request` scope, signing date, 1–900 second lifetime, lowercase 64-hex signature, and exact signed-header set). Required headers are allowlisted to content type and checksum, and both must be signed alongside `host`. Expiry is checked with a 30-second skew allowance immediately before every PUT. A clock-expired instruction or object `403` refreshes the full manifest at most once more; it cannot loop indefinitely. Worker admission, pause, failure, and stage completion all compare stage/status/attempt leases. Create and publish acknowledgements additionally compare the frozen draft remote id/version inside the same Room transaction. If publish reports `UPLOAD_INCOMPLETE`, the same CAS discipline atomically rewinds the durable row to UPLOAD before replacing the unique chain, at most twice per draft. Stale workers cannot overwrite a newer draft, rewind a completed row, or advance a replacement chain. Restart recovery replaces an unfinished pre-requeue chain when enqueue was interrupted. These Room CAS paths have Android instrumentation coverage, but the bundled Kotlin core command exercises only their SDK-independent state-machine mirror; an SDK/emulator is required to execute the SQLite tests. The object request is built by a transport separate from the authenticated API transport and rejects any `Authorization` header.

API and object transports are cancellation-cooperative, no-redirect, no-log boundaries with a 2 MiB response ceiling. The core enforces the ceiling again and bounds JSON depth, node count, and string size. Drive resources are accepted only with safe ids, supported status, bounded version/metrics/copy, and reasonable ordered timestamps. Feed cursors remain opaque and are forwarded without decoding. Owner-hidden `404`, create idempotency conflict, version conflict, manifest mismatch, incomplete upload, and retryable service errors remain typed without echoing response bodies.

Tokens, presigned/read URLs, request/response bodies, coordinates, and raw vehicle data are neither logged nor written to Room/WorkManager. Sensitive transport objects have redacted diagnostics. There is intentionally no embedded Cognito app client, API base URL, upload host, client secret, or AWS credential. Until those mobile settings and a concrete HTTP transport are approved and injected, the `pleos` flavor uses `UnavailableRemoteDriveApi` and never silently falls back to demo data.

The current local `DraftSnapshot` does not yet carry the recording's highlight count, so the disabled production adapter deliberately sends `highlightCount = 0`. The actual flavor must remain unavailable until approved derived publication metadata (including the local highlight count) is frozen into the draft and tested; it must not infer or upload raw location or vehicle telemetry.

The motion gate coordinates the motion state observed by this process with cancellation-cooperative remote child jobs; it cannot make a physical vehicle signal and a network service globally atomic. After a Driving/Unknown transition is observed, no new remote block starts, in-flight work receives best-effort cancellation, and stable idempotency makes a replay safe if the service had already accepted the request. Motion pause ends the current WorkManager chain without consuming retry backoff. A later Parked transition waits for the pause record, then immediately replaces the unique chain at the same persisted stage and `clientRequestId`; only one replacement chain remains runnable.
