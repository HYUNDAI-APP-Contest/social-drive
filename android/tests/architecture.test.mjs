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
  assert.match(build, /androidx\.room:room-runtime:2\.6\.1/);
  assert.match(build, /androidx\.work:work-runtime-ktx:2\.9\.1/);
  assert.match(build, /room\.schemaLocation/);
});

test("Room owns durable recordings drafts and a non-destructive migration path", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const daos = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const database = read("app/src/main/java/com/socialdrive/app/data/local/SocialDriveDatabase.kt");
  for (const table of ["recording_sessions", "drive_drafts", "upload_queue"]) assert.match(entities, new RegExp(table));
  assert.match(database, /exportSchema\s*=\s*true/);
  assert.match(database, /Migration\(1, 2\)/);
  assert.match(database, /addMigrations\(RoomMigrations\.MIGRATION_1_2\)/);
  assert.doesNotMatch(database, /fallbackToDestructiveMigration/);
  assert.doesNotMatch(daos, /@Delete|DELETE FROM drive_drafts/);
});

test("publish queue is stable restart-safe and keeps ephemeral credentials out of Room", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const repositories = read("app/src/main/java/com/socialdrive/app/data/OfflineRepositories.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  assert.doesNotMatch(entities, /presigned|credential|accessToken|uploadUrl/i);
  assert.match(repositories, /findByRecording\(id\)/);
  assert.match(repositories, /clientRequestId = "drive-\$\{ids\.create\(\)\}"/);
  assert.match(repositories, /motionState != MotionState\.Parked/);
  assert.match(scheduler, /beginUniqueWork\([\s\S]*ExistingWorkPolicy\.KEEP/);
  assert.match(scheduler, /recoverAfterProcessRestart/);
  assert.match(application, /recoverQueuedWork\(\)/);
  assert.match(application, /publishScheduler\.recoverAfterProcessRestart/);
  for (const worker of ["CreateDriveWorker", "UploadAssetsWorker", "PublishDriveWorker"]) assert.match(scheduler, new RegExp(worker));
  assert.match(workers, /original draft is retained after acknowledgement/);
});

test("Compose observes Room state and Android tests cover persistence and unique work", () => {
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  assert.match(app, /collectAsStateWithLifecycle/);
  assert.match(app, /offlineViewModel\.finishActiveRecording/);
  assert.match(app, /offlineViewModel\.enqueuePublish\(motion\)/);
  assert.match(app, /offlineViewModel\.updateDraftCopy\(title, description\)/);
  assert.match(viewModel, /publishQueueRepository\.observeState/);
  for (const relative of [
    "app/src/androidTest/java/com/socialdrive/app/OfflinePersistenceTest.kt",
    "app/src/androidTest/java/com/socialdrive/app/PublishWorkChainTest.kt",
  ]) assert.equal(fs.existsSync(path.join(root, relative)), true, relative);
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
  assert.match(home, /if \(motion != MotionState\.Parked\) \{\s*DrivingHome\(motion, blockedMessage, onRecord\)\s*return\s*\}/);
  assert.doesNotMatch(home, /onDemoParked|주차 상태로 전환/);
  assert.match(home, /private fun DriveGrid[\s\S]*LazyVerticalGrid/);
});

test("recording stop is confirmed and non-parked drafts cannot auto-publish", () => {
  const recording = read("app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  assert.match(recording, /fun RecordingStopDialog[\s\S]*AlertDialog/);
  assert.match(recording, /초안으로 저장/);
  assert.match(app, /BackHandler[\s\S]*destination == AppDestination\.Recording\) stopConfirmationRequested = true/);
  assert.match(app, /onRequestStop = \{ stopConfirmationRequested = true \}/);
  assert.match(app, /onConfirm = ::completeRecording/);
  assert.match(app, /DriveSessionState\.DraftReady/);
  assert.match(app, /if \(motion == MotionState\.Parked\)[\s\S]*AppDestination\.Publish[\s\S]*초안으로 안전하게 보존/);
});

test("instrumented safety suite covers lock back confirmation and parked publish", () => {
  const relative = "app/src/androidTest/java/com/socialdrive/app/AutomotiveSafetyUiTest.kt";
  assert.equal(fs.existsSync(path.join(root, relative)), true);
  const ui = read(relative);
  for (const marker of [
    "MotionState.Driving", "MotionState.Unknown", "assertDoesNotExist", "pressBack()",
    "기록을 종료할까요?", "parkedUserCanReachPublishThroughDetailOnly", "드라이브 게시",
  ]) assert.ok(ui.includes(marker), marker);
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
