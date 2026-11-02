import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const api = fs.readFileSync(new URL("../functions/api/app.mjs", import.meta.url), "utf8");
const production = fs.readFileSync(new URL("../functions/api/production-adapters.mjs", import.meta.url), "utf8");
const adapterCore = fs.readFileSync(new URL("../functions/api/aws-adapter-core.mjs", import.meta.url), "utf8");
const routeValidation = fs.readFileSync(new URL("../functions/api/route-validation.mjs", import.meta.url), "utf8");
const manifest = JSON.parse(fs.readFileSync(new URL("../functions/api/package.json", import.meta.url), "utf8"));
const lock = JSON.parse(fs.readFileSync(new URL("../functions/api/package-lock.json", import.meta.url), "utf8"));

test("keeps production adapters injectable and AWS dependencies exactly locked", () => {
  assert.match(api, /export function createHandler/);
  assert.match(api, /await import\("\.\/production-adapters\.mjs"\)/);
  for (const [name, version] of Object.entries(manifest.dependencies)) {
    assert.match(version, /^\d+\.\d+\.\d+$/);
    assert.equal(lock.packages[""].dependencies[name], version);
  }
  assert.match(production, /TransactWriteCommand/);
  assert.match(production, /commands: dynamoCommands/);
  assert.match(production, /presign: getSignedUrl/);
  assert.match(adapterCore, /ChecksumSHA256/);
});

test("publish validates a bounded non-geographic route schema", () => {
  assert.match(adapterCore, /validateRouteDocument/);
  for (const forbidden of ["latitude", "longitude", "speed", "vin", "vehicleid"]) assert.ok(routeValidation.includes(`"${forbidden}"`));
  assert.match(routeValidation, /document\.path\.length > 500/);
  assert.match(api, /ROUTE_ASSET_INVALID/);
});

test("binds upload type and checksum to the signature and verifies S3 checksum output", () => {
  assert.match(adapterCore, /signableHeaders: new Set\(\["content-type"\]\)/);
  assert.match(adapterCore, /unhoistableHeaders: new Set\(\["x-amz-checksum-sha256"\]\)/);
  assert.match(adapterCore, /ChecksumMode: "ENABLED"/);
  assert.match(adapterCore, /result\.ChecksumSHA256 !== expectedChecksum/);
  assert.doesNotMatch(adapterCore, /Metadata\?\.sha256/);
});

test("classifies transaction cancellation reasons before replay or retry", () => {
  assert.match(adapterCore, /CancellationReasons/);
  assert.match(adapterCore, /codes\[1\] === "ConditionalCheckFailed"/);
  assert.match(adapterCore, /codes\.includes\("TransactionConflict"\)/);
  assert.match(adapterCore, /ConsistentRead: true/);
  assert.match(adapterCore, /COMMIT_VISIBILITY_DELAY/);
});

test("stores opaque cursor hashes instead of serializing DynamoDB keys to clients", () => {
  assert.match(api, /repository\.saveCursor\(\{ tokenHash: sha256\(nextCursor\)/);
  assert.match(api, /repository\.loadCursor\(sha256\(cursor\)/);
  assert.doesNotMatch(api, /Buffer\.from\([^)]*LastEvaluatedKey/);
});

test("logs only allowlisted operational metadata", () => {
  assert.match(api, /logger\.info\?\.\(\{ requestId, routeKey, status/);
  assert.doesNotMatch(api, /logger\.(?:info|error).*event\?\.(?:body|headers)/s);
  assert.doesNotMatch(api, /claims\.email/);
});

test("rejects oversized and unsupported uploads before issuing URLs", () => {
  assert.match(api, /10 \* 1024 \* 1024/);
  assert.match(api, /ROUTE: \{ types: new Set\(\["application\/json"\]\), maxBytes: 1024 \* 1024/);
  assert.match(api, /ASSET_TOO_LARGE/);
  assert.match(api, /UNSUPPORTED_MEDIA/);
});
