import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const api = fs.readFileSync(new URL("../functions/api/app.mjs", import.meta.url), "utf8");
const production = fs.readFileSync(new URL("../functions/api/production-adapters.mjs", import.meta.url), "utf8");
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
  assert.match(production, /ChecksumSHA256/);
});

test("publish validates a bounded non-geographic route schema", () => {
  assert.match(production, /validateRouteDocument/);
  for (const forbidden of ["latitude", "longitude", "speed", "vin", "vehicleid"]) assert.ok(routeValidation.includes(`"${forbidden}"`));
  assert.match(routeValidation, /document\.path\.length > 500/);
  assert.match(api, /ROUTE_ASSET_INVALID/);
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
