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
  const windows = read("gradlew.bat");
  assert.match(windows, /Get-FileHash -Algorithm SHA256/);
  assert.match(windows, /Expand-Archive/);
});

test("driving home returns before parked feed and settings UI", () => {
  const home = read("app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt");
  assert.match(home, /if \(motion != MotionState\.Parked\) \{\s*DrivingHome\(motion, blockedMessage, onRecord, onDemoParked\)\s*return\s*\}/);
  assert.match(home, /private fun DriveGrid[\s\S]*LazyVerticalGrid/);
});

test("recording stop is confirmed and non-parked drafts cannot auto-publish", () => {
  const recording = read("app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  assert.match(recording, /AlertDialog/);
  assert.match(recording, /초안으로 저장/);
  assert.match(app, /DriveSessionState\.DraftReady/);
  assert.match(app, /if \(motion == MotionState\.Parked\)[\s\S]*AppDestination\.Publish[\s\S]*초안으로 안전하게 보존/);
});

test("content and upload states are observable in the demo", () => {
  const home = read("app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt");
  const publish = read("app/src/main/java/com/socialdrive/app/ui/screens/PublishScreen.kt");
  assert.match(home, /DemoContentMode\.entries/);
  assert.match(publish, /UploadState\.entries/);
  for (const state of ["Draft", "Queued", "Uploading", "Failed", "Published"]) assert.match(publish, new RegExp(`UploadState\\.${state}`));
});

test("detail actions provide capability and privacy feedback", () => {
  const detail = read("app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt");
  assert.match(detail, /ModalBottomSheet/);
  assert.match(detail, /navigationCapability == CapabilityState\.Available/);
  assert.match(detail, /외부 공유를 실행하지 않아요/);
});

test("preview matrix includes day night driving recording and failed upload", () => {
  const previews = read("app/src/main/java/com/socialdrive/app/ui/SocialDrivePreview.kt");
  assert.ok((previews.match(/@Preview/g) ?? []).length >= 5);
  for (const marker of ["ThemeMode.Day", "ThemeMode.Night", "MotionState.Driving", "RecordingScreen", "UploadState.Failed"]) assert.match(previews, new RegExp(marker.replace(".", "\\.")));
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
