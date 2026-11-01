import test from "node:test";
import assert from "node:assert/strict";
import { isolatedWorkerEnvironment, safeWorkerEnvironment } from "../src/process.mjs";

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

test("worker environment cannot fall back to the host AWS profile", () => {
  const environment = isolatedWorkerEnvironment();
  assert.equal(environment.AWS_SHARED_CREDENTIALS_FILE, "/dev/null");
  assert.equal(environment.AWS_CONFIG_FILE, "/dev/null");
  assert.equal(environment.AWS_EC2_METADATA_DISABLED, "true");
  assert.equal(environment.AWS_PROFILE, undefined);
});
