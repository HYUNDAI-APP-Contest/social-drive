# PLEOS commercial-quality visual evidence v2

## Runtime

- AVD: `Pleos_Connect_v2.1.0`
- Physical display: 2560 × 1440, 160 dpi
- Android user: 10
- App package: `com.socialdrive.app.demo`
- App activity bounds observed through UI Automator: `[853,90][2535,1300]`
- Capture: [`home_2560x1440.png`](home_2560x1440.png)

PLEOS owns the left vehicle panel, top status area, bottom dock, and the right-side
TaskView layout. The image is therefore intentionally a full-display capture rather
than a cropped app-only mockup. Social Drive is the focused activity in the right
TaskView.

## Verified interaction flows

The following tests ran against the real `MainActivity` on the PLEOS AVD through
`SocialDriveTestRunner`:

- `homeCategoryFiltersUpdateTheVisibleDriveCollection`: selecting **산** leaves only
  the mountain drive card.
- `runtimeMemoryFlowOpensDetailAndPublish`: home → memory detail → publish.
- `runtimeRecordingFlowOpensDistractionMinimizedScreen`: home → recording, including
  both primary recording actions.

The responsive home grid uses the compact card heights at the 1280 × 720 judge
fixture and expands the hero/supporting cards when the available automotive viewport
height is at least 760 dp.

## Acceptance boundary

This evidence proves UI rendering and Compose interaction in the PLEOS emulator. It
does not prove physical vehicle signals, physical PLEOS Back delivery, actual cloud
publication, or real navigation callbacks.
