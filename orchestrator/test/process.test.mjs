import test from "node:test";
import assert from "node:assert/strict";
import { safeWorkerEnvironment } from "../src/process.mjs";

test("worker environment strips host and cloud credentials", () => {
  const keys = ["GITHUB_TOKEN", "DISCORD_WEBHOOK_URL", "AWS_ACCESS_KEY_ID", "AZURE_CLIENT_SECRET", "DATABASE_PASSWORD"];
  const previous = Object.fromEntries(keys.map((key) => [key, process.env[key]]));
  for (const key of keys) process.env[key] = "must-not-leak";
  process.env.SAFE_BUILD_FLAG = "visible";
  try {
    const environment = safeWorkerEnvironment();
    for (const key of keys) assert.equal(environment[key], undefined, key);
    assert.equal(environment.SAFE_BUILD_FLAG, "visible");
  } finally {
    for (const key of keys) previous[key] === undefined ? delete process.env[key] : process.env[key] = previous[key];
    delete process.env.SAFE_BUILD_FLAG;
  }
});
