import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const read = (relative) => fs.readFileSync(path.join(root, relative), "utf8");
const kotlinFiles = (directory) => fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
  const target = path.join(directory, entry.name);
  return entry.isDirectory() ? kotlinFiles(target) : target.endsWith(".kt") ? [target] : [];
});

test("Android contract targets the automotive-compatible API floor", () => {
  const build = read("app/build.gradle.kts");
  assert.match(build, /minSdk\s*=\s*28/);
  assert.match(build, /buildFeatures\s*\{\s*compose\s*=\s*true/);
  assert.match(build, /androidx\.compose\.material3:material3/);
});

test("Gradle bootstrap pins and verifies its distribution", () => {
  const wrapper = read("gradle/wrapper/gradle-wrapper.properties");
  assert.match(wrapper, /gradle-8\.9-bin\.zip/);
  assert.match(wrapper, /distributionSha256Sum=[a-f0-9]{64}/);
});

test("all required product boundaries and screens exist", () => {
  for (const relative of [
    "app/src/main/java/com/socialdrive/app/domain/Models.kt",
    "app/src/main/java/com/socialdrive/app/data/fake/FakeDriveRepository.kt",
    "app/src/main/java/com/socialdrive/app/safety/SafetyPolicy.kt",
    "app/src/main/java/com/socialdrive/app/integration/PleosGateway.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/PublishScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt",
  ]) assert.equal(fs.existsSync(path.join(root, relative)), true, relative);
});

test("unknown vehicle motion fails closed for parked-only actions", () => {
  const safety = read("app/src/main/java/com/socialdrive/app/safety/SafetyPolicy.kt");
  assert.match(safety, /MotionState\.Unknown\s*->\s*SafetyDecision\(false/);
  assert.match(safety, /motion\s*==\s*MotionState\.Parked/);
});

test("fake app source contains no precise geographic coordinates or credentials", () => {
  const sourceRoot = path.join(root, "app/src/main/java");
  const source = kotlinFiles(sourceRoot).map((file) => fs.readFileSync(file, "utf8")).join("\n");
  assert.doesNotMatch(source, /\b(?:latitude|longitude|access[_-]?key|secret[_-]?key)\b/i);
  assert.doesNotMatch(source, /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/);
});

test("hex colors stay inside the theme color source", () => {
  const sourceRoot = path.join(root, "app/src/main/java");
  for (const file of kotlinFiles(sourceRoot)) {
    if (file.endsWith(path.join("theme", "Color.kt"))) continue;
    assert.doesNotMatch(fs.readFileSync(file, "utf8"), /0x[0-9A-Fa-f]{8}/, file);
  }
});
