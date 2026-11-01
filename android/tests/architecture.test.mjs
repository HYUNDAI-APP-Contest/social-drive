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
  assert.match(build, /getByName\("androidTest"\)\.assets\.srcDir\("\$projectDir\/schemas"\)/);
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
  assert.match(daos, /state = 'DRAFT'/);
  assert.doesNotMatch(daos, /state != 'PUBLISHED'/);
  const schema1 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/1.json"));
  const schema2 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/2.json"));
  assert.equal(schema1.database.version, 1);
  assert.equal(schema2.database.version, 2);
  const queue1 = schema1.database.entities.find((entity) => entity.tableName === "upload_queue");
  const queue2 = schema2.database.entities.find((entity) => entity.tableName === "upload_queue");
  assert.equal(queue1.fields.some((field) => field.columnName === "lastErrorCode"), false);
  assert.equal(queue2.fields.some((field) => field.columnName === "lastErrorCode"), true);
  const migrationTest = read("app/src/androidTest/java/com/socialdrive/app/RoomMigrationTest.kt");
  assert.match(migrationTest, /runMigrationsAndValidate\(DATABASE, 2, true, RoomMigrations\.MIGRATION_1_2\)/);
  assert.match(migrationTest, /originalContentRef/);
});

test("publish queue is stable restart-safe and keeps ephemeral credentials out of Room", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const repositories = read("app/src/main/java/com/socialdrive/app/data/OfflineRepositories.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteDriveApi.kt");
  assert.doesNotMatch(entities, /presigned|credential|accessToken|uploadUrl/i);
  assert.match(repositories, /findByRecording\(id\)/);
  assert.match(repositories, /clientRequestId = "drive-\$\{ids\.create\(\)\}"/);
  assert.match(repositories, /motionState != MotionState\.Parked/);
  assert.match(scheduler, /schedule\(draftId, ExistingWorkPolicy\.KEEP\)/);
  assert.match(scheduler, /beginUniqueWork\(uniqueName\(draftId\), policy, create\)/);
  assert.match(scheduler, /recoverAfterProcessRestart/);
  assert.match(application, /recoverQueuedWork\(\)/);
  assert.match(application, /publishScheduler\.recoverAfterProcessRestart/);
  for (const worker of ["CreateDriveWorker", "UploadAssetsWorker", "PublishDriveWorker"]) assert.match(scheduler, new RegExp(worker));
  assert.match(workers, /original draft is retained after acknowledgement/);
  assert.equal((workers.match(/executionGate\.execute\(/g) ?? []).length, 3);
  assert.match(scheduler, /coordinateRecovery\([\s\S]*pauseRecoverableForMotion/);
  assert.match(scheduler, /PAUSED_MOTION[\s\S]*ExistingWorkPolicy\.REPLACE/);
  assert.equal((workers.match(/ParkedExecutionResult\.Paused[^\n]*return Result\.failure\(\)/g) ?? []).length, 3);
  const gate = read("app/src/main/java/com/socialdrive/app/safety/ParkedExecutionGate.kt");
  assert.match(gate, /CoroutineStart\.UNDISPATCHED[\s\S]*bodyEntered\.complete\(Unit\)[\s\S]*startGate\.await\(\)/);
  assert.match(gate, /ActiveExecution\(child, settled\)[\s\S]*active \+= registration[\s\S]*bodyEntered\.await\(\)[\s\S]*afterChildRegistered\(\)[\s\S]*startGate\.complete\(Unit\)/);
  assert.match(gate, /children\.forEach \{ it\.remoteChild\.cancel\(MotionTransitionCancellation\(\)\) \}/);
  assert.match(gate, /pauseRecorded\.compareAndSet\(false, true\)/);
  assert.match(gate, /withContext\(NonCancellable\)[\s\S]*settled\.complete\(Unit\)[\s\S]*registryMutex\.withLock \{ active\.remove\(registration\) \}/);
  const gateTest = read("app/src/test/java/com/socialdrive/app/safety/ParkedExecutionGateTest.kt");
  for (const stage of ["create", "upload", "publish"]) assert.match(gateTest, new RegExp(`"${stage}"`));
  assert.match(gateTest, /transition after registration cancels before remote block starts/);
  assert.match(gateTest, /assertEquals\("PAUSED_MOTION", queueState\)/);
  const workTest = read("app/src/androidTest/java/com/socialdrive/app/PublishWorkChainTest.kt");
  assert.match(workTest, /recoverAfterProcessRestart\(\)[\s\S]*resumed\.count \{ !it\.state\.isFinished \}/);
  for (const marker of ["idempotencyKey", "checksumSha256Base64", "same checksum as success", "permanent conflict"]) assert.match(remote, new RegExp(marker));
  assert.match(remote, /putIfAbsent\(session, command\.checksumSha256Base64\)/);
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
  assert.match(home, /if \(motion != MotionState\.Parked\) \{\s*DrivingHome\(motion, vehicleStatus, blockedMessage, onRecord\)\s*return\s*\}/);
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
  assert.match(detail, /navigationCapability\.capability == CapabilityState\.Available/);
  assert.match(detail, /NavigationState\.Requesting/);
  assert.match(detail, /외부 공유를 실행하지 않아요/);
});

test("Pleos dependencies permissions and flavors are least-privilege isolated", () => {
  const settings = read("settings.gradle.kts");
  const build = read("app/build.gradle.kts");
  const mainManifest = read("app/src/main/AndroidManifest.xml");
  const pleosManifest = read("app/src/pleos/AndroidManifest.xml");
  assert.match(settings, /https:\/\/nexus-playground\.pleos\.ai\/repository\/maven-releases\//);
  assert.match(settings, /includeGroup\("ai\.pleos\.playground"\)/);
  assert.match(build, /flavorDimensions \+= "integration"/);
  assert.match(build, /create\("demo"\)/);
  assert.match(build, /create\("pleos"\)/);
  assert.match(build, /"pleosImplementation"\("ai\.pleos\.playground:Vehicle:2\.3\.2"\)/);
  assert.match(build, /"pleosImplementation"\("ai\.pleos\.playground:NaviHelper:3\.0\.5"\)/);
  assert.doesNotMatch(build, /(?:implementation|demoImplementation).*?(?:Vehicle|NaviHelper)/);
  assert.doesNotMatch(mainManifest, /uses-permission/);
  const permissions = [...pleosManifest.matchAll(/uses-permission android:name="([^"]+)"/g)].map((match) => match[1]);
  assert.deepEqual(permissions, ["pleos.car.permission.NAVI_ROUTE_SEARCH"]);
  assert.doesNotMatch(pleosManifest, /CAR_DRIVE/);
  for (const forbidden of ["FusedLocation", "TextToSpeech", "SpeechToText", "CONTROL_CAR_"]) {
    assert.doesNotMatch(build + pleosManifest, new RegExp(forbidden));
  }
});

test("actual adapters fail closed and never fall back to demo state", () => {
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  const demo = read("app/src/demo/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  assert.match(application, /FlavorIntegrationFactory\.create\(this\)/);
  assert.match(application, /vehicleMotionSource\.status\.collect[\s\S]*parkedExecutionGate\.transitionTo\(status\.motion\)/);
  assert.match(pleos, /Vehicle\(context\)/);
  assert.match(pleos, /vehicle\.initialize\(\)/);
  assert.match(pleos, /lifecycleOnlyResult\(\)/);
  assert.match(pleos, /VehicleMotionStatus\(MotionState\.Unknown, actualUnavailable\)/);
  assert.match(pleos, /requestSimulation\(motion: MotionState\): Boolean = false/);
  assert.doesNotMatch(pleos, /DemoVehicle|FakePleos|VehicleMotionStatus\(MotionState\.Parked/);
  assert.match(demo, /requestSimulation[\s\S]*mutableStatus\.value/);
  assert.match(viewModel, /vehicleMotion\.value\.motion != MotionState\.Parked[\s\S]*NavigationRequestResult\.Rejected/);
  assert.match(app, /LaunchedEffect\(motion\)[\s\S]*motion != MotionState\.Parked[\s\S]*destination = AppDestination\.Home/);
});

test("navigation validates named coordinates and serializes requests without logging private places", () => {
  const contract = read("app/src/main/java/com/socialdrive/app/domain/IntegrationContracts.kt");
  const controller = read("app/src/main/java/com/socialdrive/app/integration/NavigationRequestController.kt");
  const coordinator = read("app/src/main/java/com/socialdrive/app/integration/NavigationGatewayCoordinator.kt");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  const detail = read("app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt");
  for (const marker of ["val name: String", "val latitude: Double", "val longitude: Double", "isFinite()", "-90.0..90.0", "-180.0..180.0", "MAX_WAYPOINTS"]) {
    assert.ok(contract.includes(marker), marker);
  }
  assert.match(controller, /lifecycleEpoch/);
  assert.match(controller, /requestGeneration/);
  assert.match(controller, /fun timeout\(/);
  assert.match(controller, /matchesActive\(token\) && active\?\.pendingTerminal == null/);
  assert.match(controller, /fun settle\(/);
  assert.match(controller, /quarantineToken/);
  assert.match(controller, /fun endQuarantine\(/);
  assert.match(coordinator, /RequestStart\.Busy -> return NavigationRequestResult\.Busy/);
  assert.match(coordinator, /requestTimeoutMillis: Long = 15_000L/);
  assert.match(coordinator, /minimumFeedbackMillis: Long = 250L/);
  assert.match(coordinator, /staleEventQuarantineMillis: Long = 750L/);
  assert.match(coordinator, /requestListener\(session, start\.token\)/);
  assert.match(coordinator, /current === session[\s\S]*session\.request\?\.token == token/);
  assert.match(coordinator, /val old = current\?\.let\(::detachLocked\)[\s\S]*controller\.beginInitialization\(\)/);
  assert.match(coordinator, /synchronized\(session\.vendorLock\) \{ operation\(session\.resources\.port\) \}/);
  assert.match(coordinator, /if \(update !is ControllerUpdate\.Applied\) return[\s\S]*detachLocked\(session\)/);
  assert.match(coordinator, /cancelTimeout = session\.request\?\.timeoutTask[\s\S]*timeoutTask = null/);
  assert.match(pleos, /NavigationGatewayCoordinator\(/);
  assert.match(pleos, /PleosNaviPort\(NaviHelper\(applicationContext\)\)/);
  assert.match(pleos, /NaviHelperEventListener/);
  assert.match(pleos, /safeUserMessage/);
  assert.doesNotMatch(pleos, /android\.util\.Log|println\(|printStackTrace/);
  assert.match(detail, /drive\.navigationRequest == null/);
  assert.match(detail, /실제 좌표가 포함되지 않아/);
});

test("Kotlin controller tests cover callback order timeout release reinitialize exception and stale events", () => {
  const relative = "app/src/test/java/com/socialdrive/app/integration/NavigationRequestControllerTest.kt";
  assert.equal(fs.existsSync(path.join(root, relative)), true);
  const suite = read(relative);
  for (const marker of [
    "route started then completed", "double callbacks", "timeout", "late callback",
    "release", "old initialize callback", "invocation exception", "new lifecycle can recover",
  ]) assert.ok(suite.includes(marker), marker);
  assert.match(suite, /controller\.routingCompleted\(old\)[\s\S]*ControllerUpdate\.Ignored/);
});

test("production coordinator fake-port tests execute synchronous callbacks races and release ordering", () => {
  const relative = "app/src/test/java/com/socialdrive/app/integration/NavigationGatewayCoordinatorTest.kt";
  assert.equal(fs.existsSync(path.join(root, relative)), true);
  const suite = read(relative);
  for (const marker of [
    "late initialization failure is ignored", "release is not recursive", "fourteen point nine seconds",
    "synchronous request callbacks", "missing callback times out", "stale listener", "request exception",
    "new helper", "removes listener before release",
  ]) assert.ok(suite.includes(marker), marker);
  assert.match(suite, /class FakePort : NavigationVendorPort/);
  assert.match(suite, /class ManualScheduleScope : NavigationScheduleScope/);
  const coreScript = read("scripts/test-kotlin-core.sh");
  const coreRunner = read("tests/kotlin/CoreTestRunner.kt");
  const readme = read("README.md");
  for (const source of ["NavigationRequestController.kt", "NavigationGatewayCoordinator.kt", "NavigationGatewayCoordinatorTest.kt"]) {
    assert.ok(coreScript.includes(source), source);
  }
  assert.doesNotMatch(coreScript, /app\/src\/pleos|gradlew|curl|https?:\/\//);
  assert.match(coreRunner, /expectedTests = 15/);
  assert.match(readme, /does not compile the Android\/Pleos vendor adapter, assemble an APK, or replace a Pleos emulator run/);
});

test("integration UI is explicit about actual demo unavailable and coming-soon features", () => {
  const components = read("app/src/main/java/com/socialdrive/app/ui/components/RouteComponents.kt");
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  for (const marker of ["IntegrationSource.Actual", "IntegrationSource.Demo", "IntegrationSource.Unavailable"]) {
    assert.ok(components.includes(marker), marker);
  }
  for (const marker of ["Fused Location", "TTS / STT", "Vehicle Control", "Coming Soon"]) {
    assert.ok(settings.includes(marker), marker);
  }
  assert.match(settings, /vehicleStatus\.source == IntegrationSource\.Demo/);
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
    "app/src/main/java/com/socialdrive/app/domain/IntegrationContracts.kt",
    "app/src/main/java/com/socialdrive/app/integration/NavigationRequestController.kt",
    "app/src/main/java/com/socialdrive/app/integration/NavigationGatewayCoordinator.kt",
    "app/src/demo/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt",
    "app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt",
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

test("fake data fixtures contain no precise geographic coordinates or credentials", () => {
  const sourceRoot = path.join(root, "app/src/main/java/com/socialdrive/app/data/fake");
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
