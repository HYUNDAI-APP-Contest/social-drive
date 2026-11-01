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

## Real versus demo

| Area | Current behavior | Future boundary |
| --- | --- | --- |
| Route and drive feed | Preset normalized artwork and Korean copy | Authenticated feed API and cached feed entities |
| Motion state | Demo flavor simulation | Pleos flavor initializes Vehicle 2.3.2 but remains fail-closed `Unknown`; the public Brake API is not proof of parking |
| Navigation | Demo feedback; fake records contain no coordinates | Pleos flavor initializes NaviHelper 3.0.5 and accepts only validated, privacy-reviewed route input |
| Recording | Room session and draft persistence | Approved sensor adapter writing privacy-minimized samples |
| Publish | Room/WorkManager queue with injected fake API | Cognito API adapter that consumes presigned URLs only in memory |

Driving or unknown motion cannot enter detail, publish, or editable settings. AWS keys, precise coordinates, and raw vehicle state must never be added to the APK.
The Pleos flavor declares only `NAVI_ROUTE_SEARCH`. Vehicle is initialized and released but no permissioned Vehicle API is read, so `CAR_DRIVE` is intentionally absent. It does not include Fused Location, TTS, STT, or vehicle-control dependencies. A standard Android emulator cannot prove Pleos runtime behavior; use the matching Pleos Connect Emulator and approved SDK access before making a release claim.

NaviHelper callbacks do not contain the app request id. Each request therefore installs its own listener closure carrying a lifecycle epoch and request generation. `onRouteStarted` remains non-terminal; `onRoutingCompleted` or `onError` settles the request once after the minimum feedback interval. Missing callbacks time out, remove the listener, and enter a short no-listener quarantine before the next request. Old listener callbacks, old initialization callbacks, and delayed timeout jobs cannot mutate a newer request. Release cancels every job, removes the exact listener instance, releases the helper, and a later initialize creates a new `NaviHelper` object.

The pinned repository, artifacts, permissions, lifecycle, listener, and route request shapes follow the current official Pleos guides: [Vehicle SDK import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/project-setup), [Vehicle permissions](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/Vehicle/vehicle-permission), [NaviHelper import](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk), [initialization/release](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/initialization-and-release), [event listeners](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/event-listeners), and [waypoint routes](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints).
The instrumented safety suite verifies that Driving/Unknown home states contain no feed or settings controls, system Back during recording opens the shared stop confirmation, and only the parked flow reaches publish.
The publish admission check also fails closed for Driving and Unknown even if it is called outside Compose. Queued work stores only opaque local/remote IDs and retry metadata. The immutable `originalContentRef` and draft row remain until and after a successful publish acknowledgement; cleanup requires a separate future retention policy.
Once a draft enters the queue its editable copy is locked (`state = DRAFT` is required for updates), so a retry cannot upload content different from the frozen local payload. The production API adapter must reissue an upload session using the stable idempotency key, sign checksum and content type, and treat only the same checksum as a replay; presigned URLs remain memory-only.

The motion gate coordinates the motion state observed by this process with cancellation-cooperative remote child jobs; it cannot make a physical vehicle signal and a network service globally atomic. After a Driving/Unknown transition is observed, no new remote block starts, in-flight work receives best-effort cancellation, and stable idempotency makes a replay safe if the service had already accepted the request. Motion pause ends the current WorkManager chain without consuming retry backoff. A later Parked transition waits for the pause record, then immediately replaces the unique chain at the same persisted stage and `clientRequestId`; only one replacement chain remains runnable.
