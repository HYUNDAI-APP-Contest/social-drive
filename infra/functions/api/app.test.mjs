import test from "node:test";
import assert from "node:assert/strict";
import { createHandler } from "./app.mjs";
import { MemoryAssets, MemoryRepository } from "./memory-adapters.mjs";

const digest = "a".repeat(64);
const deleteKey = "d".repeat(64);
const consentPolicyVersion = "2026-11-07.v1";
const draft = (clientDraftId = "client-draft-001") => ({
  clientDraftId, schemaVersion: 1, title: "데모 바닷길", summary: "공개에 동의한 데모 경로",
  visibility: "PUBLIC", distanceMeters: 12000, durationSeconds: 1800, highlightCount: 2,
  consent: { routeShare: true, coverShare: true, policyVersion: consentPolicyVersion },
  assets: [
    { kind: "COVER", contentType: "image/jpeg", sizeBytes: 1024, sha256: digest },
    { kind: "ROUTE", contentType: "application/json", sizeBytes: 2048, sha256: digest },
  ],
});

const event = (routeKey, { userId = "user-demo-a", body, driveId, headers = {}, query } = {}) => ({
  headers,
  body: body === undefined ? undefined : JSON.stringify(body),
  pathParameters: driveId ? { driveId } : undefined,
  queryStringParameters: query,
  requestContext: {
    requestId: "request-demo-001", routeKey,
    authorizer: userId ? { jwt: { claims: { sub: userId } } } : undefined,
  },
});

function harness() {
  const repository = new MemoryRepository(); const assets = new MemoryAssets(); const logs = [];
  let driveSequence = 0;
  const handler = createHandler({
    repository, assets, consentPolicyVersion,
    clock: () => new Date("2026-11-01T10:00:00.000Z"),
    ids: { driveId: () => `drive-demo-${String(++driveSequence).padStart(3, "0")}`, cursor: () => "cursor_demo_token_12345678901234567890" },
    logger: { info: (entry) => logs.push(entry), warn: (entry) => logs.push(entry) },
  });
  return { handler, repository, assets, logs };
}
const parse = (result) => result.body ? JSON.parse(result.body) : null;
const deleteEvent = (driveId, key = deleteKey) => event("DELETE /drives/{driveId}", {
  driveId,
  headers: { "Idempotency-Key": key },
});

async function createDrive(ctx, { key = "idempotency-demo-001", payload = draft(), userId = "user-demo-a" } = {}) {
  const result = await ctx.handler(event("POST /drives", { userId, body: payload, headers: { "Idempotency-Key": key } }));
  return { result, body: parse(result) };
}

async function completeAndPublish(ctx, drive, userId = "user-demo-a") {
  ctx.assets.markUploaded(drive.id, "COVER"); ctx.assets.markUploaded(drive.id, "ROUTE");
  return ctx.handler(event("POST /drives/{driveId}/publish", { userId, driveId: drive.id, body: { expectedVersion: drive.version } }));
}

test("rejects missing JWT with a safe error envelope", async () => {
  const ctx = harness(); const result = await ctx.handler(event("GET /users/me", { userId: null })); const body = parse(result);
  assert.equal(result.statusCode, 401); assert.equal(body.error.code, "UNAUTHORIZED"); assert.equal(body.error.requestId, "request-demo-001");
  assert.deepEqual(ctx.logs[0], { requestId: "request-demo-001", routeKey: "GET /users/me", status: 401, errorCode: "UNAUTHORIZED" });
});

test("creates one draft for concurrent idempotent requests", async () => {
  const ctx = harness();
  const results = await Promise.all(Array.from({ length: 10 }, () => createDrive(ctx)));
  assert.equal(results.filter(({ result }) => result.statusCode === 201).length, 1);
  assert.equal(new Set(results.map(({ body }) => body.drive.id)).size, 1);
  assert.equal(ctx.repository.drives.size, 1);
  assert.ok(results[0].body.uploads.every((upload) => upload.url.startsWith("https://upload.invalid/")));
});

test("rejects reusing an idempotency key with a different payload", async () => {
  const ctx = harness(); await createDrive(ctx);
  const changed = draft(); changed.title = "다른 제목";
  const second = await createDrive(ctx, { payload: changed });
  assert.equal(second.result.statusCode, 409); assert.equal(second.body.error.code, "IDEMPOTENCY_CONFLICT");
});

test("rejects a draft whose consent policy differs from the deployed policy", async () => {
  const ctx = harness(); const payload = draft(); payload.consent.policyVersion = "2026-11-07";
  const rejected = await createDrive(ctx, { payload });
  assert.equal(rejected.result.statusCode, 422);
  assert.deepEqual(rejected.body.error.fields, ["consent"]);
  assert.equal(ctx.repository.drives.size, 0);
});

test("refreshes only exact expected upload manifests", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const ok = await ctx.handler(event("POST /drives/{driveId}/uploads", { driveId: drive.id, body: { assets: draft().assets } }));
  assert.equal(ok.statusCode, 200); assert.equal(parse(ok).uploads.length, 2);
  const partial = await ctx.handler(event("POST /drives/{driveId}/uploads", { driveId: drive.id, body: { assets: [draft().assets[0]] } }));
  assert.equal(partial.statusCode, 409); assert.equal(parse(partial).error.code, "ASSET_MISMATCH");
  const changed = { ...draft().assets[0], sizeBytes: 999 };
  const bad = await ctx.handler(event("POST /drives/{driveId}/uploads", { driveId: drive.id, body: { assets: [changed, draft().assets[1]] } }));
  assert.equal(bad.statusCode, 409); assert.equal(parse(bad).error.code, "ASSET_MISMATCH");
});

test("requires verified assets and publishes idempotently", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const incomplete = await ctx.handler(event("POST /drives/{driveId}/publish", { driveId: drive.id, body: { expectedVersion: 1 } }));
  assert.equal(incomplete.statusCode, 409); assert.equal(parse(incomplete).error.retryable, true);
  const first = await completeAndPublish(ctx, drive); const published = parse(first).drive;
  assert.equal(published.status, "PUBLISHED"); assert.equal(published.version, 2);
  const repeated = await ctx.handler(event("POST /drives/{driveId}/publish", { driveId: drive.id, body: { expectedVersion: 1 } }));
  assert.deepEqual(parse(repeated).drive, published);
});

test("rejects an uploaded route containing non-public data", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  ctx.assets.markUploaded(drive.id, "COVER"); ctx.assets.markUploaded(drive.id, "ROUTE"); ctx.assets.markInvalid(drive.id, "ROUTE");
  const result = await ctx.handler(event("POST /drives/{driveId}/publish", { driveId: drive.id, body: { expectedVersion: 1 } }));
  assert.equal(result.statusCode, 422); assert.equal(parse(result).error.code, "ROUTE_ASSET_INVALID");
  assert.equal((await ctx.repository.getDrive(drive.id)).status, "DRAFT");
});

test("feed cursor is opaque, server stored, scoped, and tamper resistant", async () => {
  const ctx = harness();
  const one = await createDrive(ctx, { key: "idempotency-demo-001", payload: draft("client-draft-001") });
  const two = await createDrive(ctx, { key: "idempotency-demo-002", payload: draft("client-draft-002") });
  await completeAndPublish(ctx, one.body.drive); await completeAndPublish(ctx, two.body.drive);
  const page = parse(await ctx.handler(event("GET /feed", { query: { limit: "1" } })));
  assert.equal(page.items.length, 1); assert.equal(page.nextCursor, "cursor_demo_token_12345678901234567890");
  assert.equal(JSON.stringify(page).includes("FeedPK"), false); assert.equal(JSON.stringify(page).includes("ownerId"), false);
  const next = parse(await ctx.handler(event("GET /feed", { query: { limit: "1", cursor: page.nextCursor } })));
  assert.equal(next.items.length, 1); assert.notEqual(next.items[0].id, page.items[0].id);
  const tampered = await ctx.handler(event("GET /feed", { query: { limit: "1", cursor: `${page.nextCursor}x` } }));
  assert.equal(tampered.statusCode, 400); assert.equal(parse(tampered).error.code, "INVALID_CURSOR");
});

test("detail hides drafts from other users and returns short-lived asset URLs for published drives", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const hidden = await ctx.handler(event("GET /drives/{driveId}", { userId: "user-demo-b", driveId: drive.id }));
  assert.equal(hidden.statusCode, 404);
  await completeAndPublish(ctx, drive);
  const detail = parse(await ctx.handler(event("GET /drives/{driveId}", { userId: "user-demo-b", driveId: drive.id }))).drive;
  assert.match(detail.coverUrl, /^https:\/\/read\.invalid/); assert.match(detail.routeUrl, /^https:\/\/read\.invalid/);
  assert.equal("ownerId" in detail, false); assert.equal("assets" in detail, false);
});

test("patch and delete are owner-only, versioned, and delete is idempotent", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const denied = await ctx.handler(event("PATCH /drives/{driveId}", { userId: "user-demo-b", driveId: drive.id, body: { expectedVersion: 1, title: "변경" } }));
  assert.equal(denied.statusCode, 404);
  const updated = parse(await ctx.handler(event("PATCH /drives/{driveId}", { driveId: drive.id, body: { expectedVersion: 1, title: "안전한 새 제목" } }))).drive;
  assert.equal(updated.version, 2); assert.equal(updated.title, "안전한 새 제목");
  const deleted = await ctx.handler(deleteEvent(drive.id));
  const repeated = await ctx.handler(deleteEvent(drive.id));
  assert.equal(deleted.statusCode, 204); assert.equal(repeated.statusCode, 204);
});

test("delete requires one stable lowercase SHA-256 idempotency key", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const missing = await ctx.handler(event("DELETE /drives/{driveId}", { driveId: drive.id }));
  const malformed = await ctx.handler(deleteEvent(drive.id, "D".repeat(64)));
  assert.equal(missing.statusCode, 400); assert.equal(parse(missing).error.code, "IDEMPOTENCY_KEY_REQUIRED");
  assert.equal(malformed.statusCode, 400); assert.equal(parse(malformed).error.code, "IDEMPOTENCY_KEY_REQUIRED");

  assert.equal((await ctx.handler(deleteEvent(drive.id))).statusCode, 204);
  const conflictingReplay = await ctx.handler(deleteEvent(drive.id, "e".repeat(64)));
  assert.equal(conflictingReplay.statusCode, 409);
  assert.equal(parse(conflictingReplay).error.code, "IDEMPOTENCY_CONFLICT");
});

test("deleting a published drive removes it from feed and detail and cleans every asset", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  await completeAndPublish(ctx, drive);
  assert.equal(parse(await ctx.handler(event("GET /feed"))).items.length, 1);

  const deleted = await ctx.handler(deleteEvent(drive.id));

  assert.equal(deleted.statusCode, 204);
  assert.equal((await ctx.repository.getDrive(drive.id)).status, "DELETED");
  assert.equal(parse(await ctx.handler(event("GET /feed"))).items.length, 0);
  assert.equal((await ctx.handler(event("GET /drives/{driveId}", { driveId: drive.id }))).statusCode, 404);
  assert.deepEqual(ctx.assets.deleted, new Set([`${drive.id}:COVER`, `${drive.id}:ROUTE`]));
});

test("validation never echoes request data or infrastructure errors", async () => {
  const ctx = harness(); const unsafe = draft(); unsafe.assets[0].contentType = "text/html";
  const result = await createDrive(ctx, { payload: unsafe }); const serialized = JSON.stringify(result.body);
  assert.equal(result.result.statusCode, 415); assert.equal(serialized.includes(digest), false); assert.equal(serialized.includes("DynamoDB"), false); assert.equal(serialized.includes("S3"), false);
});

test("rejects non-object JSON as a safe client error", async () => {
  const ctx = harness();
  const result = await ctx.handler(event("POST /drives", { body: null, headers: { "Idempotency-Key": "idempotency-demo-001" } }));
  assert.equal(result.statusCode, 400); assert.equal(parse(result).error.code, "INVALID_JSON");
});

test("delete remains successful and retryable when private asset cleanup is temporarily unavailable", async () => {
  const ctx = harness(); const created = await createDrive(ctx); const drive = created.body.drive;
  const originalDelete = ctx.assets.deleteAssets.bind(ctx.assets);
  ctx.assets.deleteAssets = async () => { throw new Error("private provider detail"); };
  const first = await ctx.handler(deleteEvent(drive.id));
  assert.equal(first.statusCode, 204);
  assert.ok(ctx.logs.some((entry) => entry.errorCode === "ASSET_CLEANUP_PENDING"));
  assert.equal(JSON.stringify(ctx.logs).includes("private provider detail"), false);
  ctx.assets.deleteAssets = originalDelete;
  const second = await ctx.handler(deleteEvent(drive.id));
  assert.equal(second.statusCode, 204); assert.ok(ctx.assets.deleted.has(`${drive.id}:COVER`));
});
