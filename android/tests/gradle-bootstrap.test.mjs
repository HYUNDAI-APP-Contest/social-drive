import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("local Gradle launcher keeps Android user state inside the workspace runtime", () => {
  const launcher = fs.readFileSync(path.join(root, "scripts/gradle-local.sh"), "utf8");
  assert.match(launcher, /mkdir -p[^\n]*\$runtime_root\/android-user-home/);
  assert.match(launcher, /export ANDROID_USER_HOME="\$runtime_root\/android-user-home"/);
});

test("missing Gradle cache fails fast offline without a network attempt", () => {
  const cache = fs.mkdtempSync(path.join(os.tmpdir(), "social-drive-gradle-offline-"));
  try {
    const result = spawnSync("./gradlew", ["--offline", "--version"], {
      cwd: root,
      env: { ...process.env, GRADLE_USER_HOME: cache },
      encoding: "utf8",
      timeout: 10_000,
    });
    assert.equal(result.status, 2);
    assert.match(result.stderr, /--offline forbids downloading/);
    assert.doesNotMatch(result.stderr + result.stdout, /curl:|Downloading/);
    assert.equal(fs.existsSync(path.join(cache, "wrapper-bootstrap")), false);
  } finally {
    fs.rmSync(cache, { recursive: true, force: true });
  }
});
