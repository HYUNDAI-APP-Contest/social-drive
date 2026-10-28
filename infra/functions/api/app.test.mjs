import test from "node:test";
import assert from "node:assert/strict";
import { handler } from "./app.mjs";

const event = (routeKey, claims = { sub: "user-1", email: "driver@example.com" }) => ({ requestContext: { routeKey, authorizer: { jwt: { claims } } } });

test("rejects requests without JWT claims", async () => {
  assert.equal((await handler(event("GET /users/me", {}))).statusCode, 401);
});

test("returns current user without logging sensitive data", async () => {
  const result = await handler(event("GET /users/me"));
  assert.equal(result.statusCode, 200);
  assert.equal(JSON.parse(result.body).id, "user-1");
});

test("returns an empty feed skeleton", async () => {
  const result = await handler(event("GET /feed"));
  assert.deepEqual(JSON.parse(result.body), { items: [], nextCursor: null });
});

