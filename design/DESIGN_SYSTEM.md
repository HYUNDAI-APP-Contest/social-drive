# Route Memory design system

Social Drive treats a route as a memory ribbon rather than a utility map line. The product should feel calm, safe, modern, and emotionally warm without becoming visually busy.

Visual reference: `design/route-memory-ui-concept-v1.png`.

## Foundation

| Token | Value | Use |
| --- | --- | --- |
| Hyundai Navy | `#002C5F` | Brand anchor, dark gradients |
| Route Blue | `#3B82F6` | Route and navigation state |
| Highlight Mint | `#35D0BA` | Primary action and success |
| Moment Amber | `#FFB547` | Memory markers and attention |
| Night Surface | `#0B1220` | Night background |
| Day Surface | `#F5F7FA` | Day background |

- Material 3 Compose tokens are the implementation source of truth.
- Use Noto Sans KR or the platform Korean sans fallback.
- Distance, duration, and timestamps use tabular numerals.
- Day/night palettes must be tested independently; never invert the map mechanically.
- Minimum normal touch target is 48dp; primary driving actions target 64dp or larger.

## Driving mode

- Show only recording, GPS, vehicle connection, elapsed time, distance, route ribbon, highlight, and stop.
- No keyboard, long list, swipe carousel, rich editing, or automatic screen transition.
- Highlight feedback uses one short color pulse and haptic response.
- If vehicle or navigation capability is unavailable, explain it without blocking local recording.

## Parked mode

- Home uses large editorial drive cards with route, distance, duration, and highlight count.
- Detail overlays moment markers on the route and provides a chronological photo timeline.
- Publish follows image → title → visibility → consent → upload.
- Draft, offline, uploading, retrying, and published states are always explicit.

## Compose components

- `DriveStatusHeader`
- `RouteRibbon`
- `HighlightButton`
- `DriveSummaryCard`
- `MomentTimeline`
- `UploadStateChip`
- `SafetyRestrictionBanner`
- `CapabilityUnavailableSheet`
- `EmptyFeedIllustration`

## Quality gates

- Day/night screenshot tests for every screen state.
- Large text, contrast, color-vision, and Korean truncation checks.
- Automotive restriction tests that prove parked-only actions are inaccessible while driving.
- Skeleton, empty, offline, retry, error, and permission-denied states.
- No precise location is exposed in screenshots, logs, analytics, or notifications without consent.

