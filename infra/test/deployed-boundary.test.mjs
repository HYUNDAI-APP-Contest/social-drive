import test from "node:test";
import assert from "node:assert/strict";
import { verifyDeployedBoundary } from "../scripts/verify-deployed-boundary.mjs";

test("accepts only an anonymous 401 from the HTTPS feed boundary", async () => {
  let observed;
  await verifyDeployedBoundary("https://api.example.com/dev", async (url, options) => {
    observed = { url: String(url), options };
    return new Response("Unauthorized", { status: 401 });
  });
  assert.equal(observed.url, "https://api.example.com/dev/feed");
  assert.equal(observed.options.redirect, "error");
  assert.equal(observed.options.headers.Authorization, undefined);
});

test("rejects insecure URLs, redirects represented as non-401, and oversized responses", async () => {
  await assert.rejects(() => verifyDeployedBoundary("http://api.example.com/dev", fetch), /HTTPS/);
  await assert.rejects(
    () => verifyDeployedBoundary("https://api.example.com/dev", async () => new Response("redirect", { status: 302 })),
    /received 302/,
  );
  await assert.rejects(
    () => verifyDeployedBoundary("https://api.example.com/dev", async () => new Response("x".repeat(65 * 1024), { status: 401 })),
    /too large/,
  );
});
