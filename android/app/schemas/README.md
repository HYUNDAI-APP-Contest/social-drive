# Room schemas

The committed `1.json`, `2.json`, and `3.json` files are the versioned Room migration baselines. KSP is configured to export the current schema here during Android builds; generated changes must match the reviewed baseline before commit. `RoomMigrationTest` creates v1 from this asset, migrates through v2 to v3, validates the schema, and checks that recording, draft, client request, original reference, and queued upload rows survive while the new frozen remote version and bounded upload-rewind fields receive safe defaults. Destructive fallback is intentionally disabled.
