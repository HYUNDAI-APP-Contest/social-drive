# Room schemas

The committed `1.json` and `2.json` files are the versioned Room migration baselines. KSP is configured to export the current schema here during Android builds; generated changes must match the reviewed baseline before commit. `RoomMigrationTest` creates v1 from this asset, migrates to v2, validates the schema, and checks that recording, draft, client request, original reference, and queued upload rows survive. Destructive fallback is intentionally disabled.
