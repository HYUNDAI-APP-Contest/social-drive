import test from "node:test";
import assert from "node:assert/strict";
import { validateRouteDocument } from "./route-validation.mjs";

const safeRoute = () => ({
  schemaVersion: 1,
  path: [
    { horizontal: 0.1, vertical: 0.8, progress: 0 },
    { horizontal: 0.5, vertical: 0.4, progress: 0.5 },
    { horizontal: 0.9, vertical: 0.2, progress: 1 },
  ],
  highlights: [{ progress: 0.5, title: "좋았던 순간" }],
});

test("accepts a bounded non-geographic route-memory document", () => {
  assert.deepEqual(validateRouteDocument(JSON.stringify(safeRoute())), { valid: true });
});

test("rejects precise geography and raw vehicle fields at any nesting depth", () => {
  for (const forbidden of ["latitude", "longitude", "speed", "vin", "vehicleId"]) {
    const route = safeRoute(); route.highlights[0][forbidden] = "not-allowed";
    assert.equal(validateRouteDocument(JSON.stringify(route)).valid, false, forbidden);
  }
});

test("rejects oversized paths, unknown fields, and invalid normalized values", () => {
  const oversized = safeRoute(); oversized.path = Array.from({ length: 501 }, () => ({ horizontal: 0.5, vertical: 0.5 }));
  assert.equal(validateRouteDocument(JSON.stringify(oversized)).valid, false);
  const unknown = safeRoute(); unknown.raw = true;
  assert.equal(validateRouteDocument(JSON.stringify(unknown)).valid, false);
  const outside = safeRoute(); outside.path[0].horizontal = 2;
  assert.equal(validateRouteDocument(JSON.stringify(outside)).valid, false);
});
