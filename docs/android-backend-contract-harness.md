# Android–backend local contract harness

This deterministic JVM harness connects the real `CloudDriveClient` to the real serverless `createHandler` over a Node child process using newline-delimited JSON. The bridge opens no socket, imports no production AWS adapter, reads no environment configuration, and needs no credential or deployed service. Gradle may still require its own local IPC, which a restricted sandbox can deny before test execution.

## Run

Requirements are Node.js, JDK 17, Android SDK 35, and access to the configured public/PLEOS artifact repositories on the first Gradle run. From the repository root:

```bash
bash android/scripts/test-contract-harness.sh
```

The command runs the same test class in `testDemoDebugUnitTest` and `testPleosDebugUnitTest`. Each test starts an isolated in-memory repository with a fixed clock (`2026-11-01T10:00:00Z`), fixed IDs, a five-second test deadline, and no network listener. A script watchdog terminates the complete two-flavor harness after 120 seconds, allowing cold Gradle configuration without hiding a stalled child process.

## Evidence and failure behavior

The suite covers create/replay (including the handler's `201` then `200` statuses), complete manifest refresh, byte uploads, publish, feed/detail, versioned patch/conflict, and idempotent delete replay. Both delete calls must return `204` and carry the same lowercase 64-character SHA-256 idempotency key. Partial, duplicate, and oversized manifests are rejected by the Android boundary, while a direct partial refresh that bypasses the client is rejected independently by the backend. The local object adapter rejects MIME, size, and checksum mismatches without retaining bytes. The suite also checks an in-flight request held after the real handler response, authentication, one-shot retryable transport failure, malformed response, malformed and over-64-KiB request envelopes, upload-incomplete, manifest/idempotency conflict, and owner-hidden `404` behavior.

Boundary assertions combine recursive forbidden-key checks with sentinel scanning across request URLs, headers, API bodies, and uploaded bytes. The route asset is allowlisted to `schemaVersion`, `path`, and `highlights`; points allow only `horizontal`, `vertical`, and optional `progress`. Object PUTs must not carry authorization. Harness errors identify only an operation ID/status or forbidden key; tokens, URLs, payloads, and response bodies remain redacted.

If startup says the repository root was not found, run the checked-in script rather than copying the test class elsewhere. A missing SDK, JDK, cached Gradle distribution, or cached PLEOS artifact is an environment prerequisite failure; do not weaken or skip either flavor. A timeout indicates a child-process/protocol regression and should be investigated rather than retried in a loop.
