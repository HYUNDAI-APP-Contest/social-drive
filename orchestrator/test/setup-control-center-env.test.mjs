import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { updateEnvFile } from "../../scripts/setup-control-center.mjs";

test("writes only non-secret project values while preserving secrets and mode", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-env-"));
  const file = path.join(directory, ".env");
  await fs.writeFile(file, "GITHUB_TOKEN=keep-secret\nGITHUB_PROJECT_ID=old\n", { mode: 0o600 });

  await updateEnvFile(file, {
    GITHUB_PROJECT_ID: "project-1",
    GITHUB_PROJECT_URL: "https://github.com/orgs/example/projects/1",
  });

  const result = await fs.readFile(file, "utf8");
  assert.match(result, /^GITHUB_TOKEN=keep-secret$/m);
  assert.match(result, /^GITHUB_PROJECT_ID=project-1$/m);
  assert.match(result, /^GITHUB_PROJECT_URL=https:\/\/github\.com\/orgs\/example\/projects\/1$/m);
  assert.equal((await fs.stat(file)).mode & 0o777, 0o600);
  await fs.rm(directory, { recursive: true, force: true });
});

test("rejects line injection in generated environment values", async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "social-drive-env-"));
  const file = path.join(directory, ".env");
  await fs.writeFile(file, "GITHUB_TOKEN=keep-secret\n", { mode: 0o600 });
  await assert.rejects(updateEnvFile(file, { GITHUB_PROJECT_URL: "safe\nINJECTED=value" }), /Unsafe environment value/);
  assert.equal(await fs.readFile(file, "utf8"), "GITHUB_TOKEN=keep-secret\n");
  await fs.rm(directory, { recursive: true, force: true });
});
