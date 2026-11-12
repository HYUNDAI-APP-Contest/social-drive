# Social Drive MVP API

All routes use the Cognito JWT authorizer. The API accepts only parked, user-confirmed publication metadata; driving traces and raw vehicle state remain local. Responses never include DynamoDB keys, S3 object keys, AWS identifiers, or user email.

## Offline-safe publication

1. The app persists one `clientDraftId` and `Idempotency-Key` per local draft.
2. `POST /drives` creates one remote draft and returns 10-minute presigned PUT URLs.
3. `POST /drives/{driveId}/uploads` refreshes URLs only when the manifest exactly matches the draft.
4. The app uploads the consented cover and sanitized route assets directly to private S3. The manifest `sha256` is a 64-character lowercase hexadecimal SHA-256 digest. The API converts it to RFC 4648 base64 and returns that exact value as the required `x-amz-checksum-sha256` header. Clients must send both returned headers exactly: `Content-Type` is signed and the checksum header is signed and deliberately not hoisted into the query string. The MVP route schema contains only normalized 0–1 artwork points and highlight progress; geographic coordinates and raw vehicle fields are rejected before publish.
5. `POST /drives/{driveId}/publish` calls S3 `HeadObject` with checksum mode enabled and compares the S3-calculated SHA-256 checksum, object type, and byte size with the manifest before a conditional `DRAFT → PUBLISHED` transition. User-controlled object metadata is not accepted as checksum evidence.
6. Repeating create or publish after a lost response returns the same resource rather than duplicating it.

Concurrent create requests classify DynamoDB transaction cancellation reasons. An idempotency-record condition failure is resolved with bounded, strongly consistent reads; a transaction conflict uses bounded exponential backoff with jitter and observes a possible preceding commit before retrying. Capacity, validation, and other cancellation reasons are not reported as idempotency conflicts.

`GET /feed` uses a random opaque cursor. The server stores only its SHA-256 hash with the DynamoDB continuation key and a 15-minute TTL, so a client never receives a raw LastEvaluatedKey. The table also expires idempotency and cursor records through `expiresAtEpoch`.

## Routes

- `GET /users/me`
- `POST /drives`
- `POST /drives/{driveId}/uploads`
- `POST /drives/{driveId}/publish`
- `GET /feed?limit=20&cursor=...`
- `GET /drives/{driveId}`
- `PATCH /drives/{driveId}`
- `DELETE /drives/{driveId}`

Create, refresh, publish, update, and delete are owner-scoped. Non-owner draft access returns `404` to avoid existence disclosure. Delete requires the Android client's stable lowercase SHA-256 `Idempotency-Key`; the first key is stored with the tombstone, same-key replay is accepted, and a different-key replay returns `409 IDEMPOTENCY_CONFLICT`. A successful delete removes the item from the public feed before best-effort media cleanup, and same-key replay retries that cleanup safely.

## Build and test

```bash
npm --prefix infra run build:function
npm --prefix infra test
```

AWS SDK v3 packages are exact-version locked under `functions/api/package-lock.json`. The deployment host runs `npm ci --omit=dev --ignore-scripts` inside the function directory before CloudFormation packaging. Tests use in-memory adapters and never call AWS.
