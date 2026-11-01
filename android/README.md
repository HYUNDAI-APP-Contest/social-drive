# Social Drive Android demo

An offline-first visual foundation for the Hyundai app contest. The current build intentionally uses deterministic fake data so the product flow can be reviewed before AWS and Pleos SDK integration.

## Included flow

- Premium day/night Material 3 theme and Route Memory components
- Home feed, drive detail, distraction-minimized recording, parked publish, and settings
- Fake repository and abstract route points with no precise geographic coordinates
- `Parked`, `Driving`, and fail-closed `Unknown` safety states
- Loading, content, empty, offline, error, permission-denied, and retry content contracts
- On-screen demo controls for every content and upload state
- Confirmed recording stop: parked sessions continue to publish; driving/unknown sessions retain a draft without opening editing
- Explicit demo badges and capability boundaries for future Pleos adapters
- Room-backed recording sessions, durable drafts, and a staged upload queue with exported schemas and non-destructive migrations
- A unique WorkManager chain per draft (`create → asset upload → publish`) that resumes after process restart
- Stable `clientRequestId` values, local-original retention through publish acknowledgement, and no persisted presigned URLs or tokens

## Run

Requirements: JDK 17+, Android SDK 35, and an emulator or device. From this directory:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
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
| Motion state | Settings simulation | `CarUxRestrictions`/Pleos vehicle capability |
| Navigation | Capability placeholder | NaviHelper adapter |
| Recording | Room session and draft persistence | Approved sensor adapter writing privacy-minimized samples |
| Publish | Room/WorkManager queue with injected fake API | Cognito API adapter that consumes presigned URLs only in memory |

Driving or unknown motion cannot enter detail, publish, or editable settings. AWS keys, precise coordinates, and raw vehicle state must never be added to the APK.
The instrumented safety suite verifies that Driving/Unknown home states contain no feed or settings controls, system Back during recording opens the shared stop confirmation, and only the parked flow reaches publish.
The publish admission check also fails closed for Driving and Unknown even if it is called outside Compose. Queued work stores only opaque local/remote IDs and retry metadata. The immutable `originalContentRef` and draft row remain until and after a successful publish acknowledgement; cleanup requires a separate future retention policy.
