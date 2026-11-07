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

test("WorkManager uses target-only on-demand initialization without a test APK provider", () => {
  const mainManifest = read("app/src/main/AndroidManifest.xml");
  const testManifest = read("app/src/androidTest/AndroidManifest.xml");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const build = read("app/build.gradle.kts");
  const instrumentation = read("app/src/androidTest/java/com/socialdrive/app/WorkManagerOnDemandInitializationTest.kt");
  assert.match(mainManifest, /xmlns:tools="http:\/\/schemas\.android\.com\/tools"/);
  assert.match(mainManifest, /android:name="androidx\.startup\.InitializationProvider"/);
  assert.match(mainManifest, /android:authorities="\$\{applicationId\}\.androidx-startup"/);
  assert.match(mainManifest, /tools:node="merge"/);
  assert.match(mainManifest, /android:name="androidx\.work\.WorkManagerInitializer"[\s\S]*tools:node="remove"/);
  assert.doesNotMatch(mainManifest, /<provider(?=[^>]*android:name="androidx\.startup\.InitializationProvider")[^>]*tools:node="remove"/);
  assert.doesNotMatch(testManifest, /InitializationProvider|WorkManagerInitializer|<provider/);
  assert.match(testManifest, /separate package and must not declare the target app's/);
  assert.match(application, /class SocialDriveApplication : Application\(\), Configuration\.Provider/);
  assert.match(application, /override val workManagerConfiguration: Configuration/);
  assert.match(instrumentation, /SocialDriveTestApplication/);
  assert.match(instrumentation, /workManagerConfiguration\.workerFactory is PublishWorkerFactory/);
  assert.doesNotMatch(build, /lint\s*\{[\s\S]*(?:disable|baseline)/);
});

test("Room owns durable recordings drafts and a non-destructive migration path", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const daos = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const database = read("app/src/main/java/com/socialdrive/app/data/local/SocialDriveDatabase.kt");
  for (const table of ["recording_sessions", "drive_drafts", "upload_queue"]) assert.match(entities, new RegExp(table));
  assert.match(database, /exportSchema\s*=\s*true/);
  assert.match(database, /version\s*=\s*8/);
  assert.match(database, /Migration\(1, 2\)/);
  assert.match(database, /Migration\(2, 3\)/);
  assert.match(database, /Migration\(3, 4\)/);
  assert.match(database, /REMOTE_VERSION_REQUIRED/);
  assert.match(database, /status IN \('QUEUED', 'RUNNING', 'PAUSED_MOTION', 'FAILED'\)/);
  assert.match(database, /stage NOT IN \('COMPLETE', 'PUBLISHED', 'DELETED'\)/);
  assert.match(database, /remoteDriveId IS NOT NULL AND remoteVersion IS NULL/);
  assert.match(database, /addMigrations\(\*RoomMigrations\.ALL\)/);
  assert.doesNotMatch(database, /fallbackToDestructiveMigration/);
  assert.doesNotMatch(daos, /@Delete|DELETE FROM drive_drafts/);
  assert.match(daos, /state = 'DRAFT'/);
  assert.doesNotMatch(daos, /state != 'PUBLISHED'/);
  const schema1 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/1.json"));
  const schema2 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/2.json"));
  const schema3 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/3.json"));
  const schema4 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/4.json"));
  const schema5 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/5.json"));
  const schema6 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/6.json"));
  const schema7 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/7.json"));
  const schema8 = JSON.parse(read("app/schemas/com.socialdrive.app.data.local.SocialDriveDatabase/8.json"));
  assert.equal(schema1.database.version, 1);
  assert.equal(schema2.database.version, 2);
  assert.equal(schema3.database.version, 3);
  assert.equal(schema4.database.version, 4);
  assert.equal(schema5.database.version, 5);
  assert.equal(schema6.database.version, 6);
  assert.equal(schema7.database.version, 7);
  assert.equal(schema8.database.version, 8);
  const queue1 = schema1.database.entities.find((entity) => entity.tableName === "upload_queue");
  const queue2 = schema2.database.entities.find((entity) => entity.tableName === "upload_queue");
  assert.equal(queue1.fields.some((field) => field.columnName === "lastErrorCode"), false);
  assert.equal(queue2.fields.some((field) => field.columnName === "lastErrorCode"), true);
  const draft3 = schema3.database.entities.find((entity) => entity.tableName === "drive_drafts");
  const queue3 = schema3.database.entities.find((entity) => entity.tableName === "upload_queue");
  assert.equal(draft3.fields.some((field) => field.columnName === "remoteVersion"), true);
  assert.equal(queue3.fields.some((field) => field.columnName === "uploadRewindCount"), true);
  assert.equal(queue3.fields.find((field) => field.columnName === "uploadRewindCount").defaultValue, "0");
  const migrationTest = read("app/src/androidTest/java/com/socialdrive/app/RoomMigrationTest.kt");
  assert.match(migrationTest, /runMigrationsAndValidate\(DATABASE, 3, true, \*RoomMigrations\.ALL\)/);
  assert.match(migrationTest, /originalContentRef/);
  assert.match(migrationTest, /remoteVersion/);
  assert.match(migrationTest, /uploadRewindCount/);
  assert.match(migrationTest, /failed-upload/);
  assert.match(migrationTest, /failed-publish/);
  assert.match(migrationTest, /"PUBLISHED"/);
  assert.match(migrationTest, /"DELETED"/);
  assert.match(migrationTest, /migrateThreeToFourAddsPrivacyStateWithoutChangingPublishRows/);
  for (const table of ["route_privacy_state", "normalized_route_points", "published_delete_requests"]) {
    assert.ok(schema4.database.entities.some((entity) => entity.tableName === table), table);
  }
  const conflict5 = schema5.database.entities.find((entity) => entity.tableName === "publish_conflicts");
  for (const column of ["clientRequestId", "attemptedVersion", "replayCount", "fetchGeneration"]) {
    assert.ok(conflict5.fields.some((field) => field.columnName === column), column);
  }
  assert.match(database, /Migration\(4, 5\)/);
  assert.match(migrationTest, /migrateFourToFiveBackfillsReviewableAndUnreviewableConflictsWithoutChangingDrafts/);
  const delete6 = schema6.database.entities.find((entity) => entity.tableName === "published_delete_requests");
  for (const column of ["clientRequestId", "remoteDriveId", "idempotencyKeyHash", "attempts", "nextAttemptAtEpochMs", "lastErrorCode", "confirmedAtEpochMs"]) {
    assert.ok(delete6.fields.some((field) => field.columnName === column), column);
  }
  assert.match(database, /Migration\(5, 6\)/);
  assert.match(migrationTest, /migrateFiveToSixMakesLegacyDeletePendingFailClosed/);
  const recording7 = schema7.database.entities.find((entity) => entity.tableName === "recording_sessions");
  const distance = recording7.fields.find((field) => field.columnName === "distanceMeters");
  assert.equal(distance.notNull, true);
  assert.equal(distance.defaultValue, "0");
  assert.match(database, /Migration\(6, 7\)/);
  assert.match(migrationTest, /migrateSixToSevenAddsPrivacySafeAggregateDistance/);
  const privacy8 = schema8.database.entities.find((entity) => entity.tableName === "route_privacy_state");
  assert.ok(privacy8.fields.some((field) => field.columnName === "consentPolicyVersion"));
  assert.match(database, /Migration\(7, 8\)/);
  assert.match(database, /SET consentState = 'NotAsked', routeSource = 'DemoPreset'/);
  assert.match(migrationTest, /migrateSevenToEightInvalidatesUnversionedLocationConsent/);
});

test("publish queue is stable restart-safe and keeps ephemeral credentials out of Room", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const repositories = read("app/src/main/java/com/socialdrive/app/data/OfflineRepositories.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteDriveApi.kt");
  const demoRemote = read("app/src/demo/java/com/socialdrive/app/data/remote/FakeRemoteDriveApi.kt");
  assert.doesNotMatch(entities, /presigned|credential|accessToken|uploadUrl/i);
  assert.match(repositories, /findByRecording\(id\)/);
  assert.match(repositories, /clientRequestId = "drive-\$\{ids\.create\(\)\}"/);
  assert.match(repositories, /motionState != MotionState\.Parked/);
  assert.match(scheduler, /chainEnqueuer\.enqueue\(draftId, ExistingWorkPolicy\.KEEP\)/);
  assert.match(scheduler, /beginUniqueWork\(PublishScheduler\.uniqueName\(draftId\), policy, create\)/);
  assert.match(scheduler, /recoverAfterProcessRestart/);
  assert.match(application, /recoverQueuedWork\(\)/);
  assert.match(application, /publishScheduler\.recoverAfterProcessRestart/);
  const workChainTest = read("app/src/androidTest/java/com/socialdrive/app/PublishWorkChainTest.kt");
  const workLifecycle = read("app/src/androidTest/java/com/socialdrive/app/TestWorkManagerLifecycle.kt");
  assert.match(workChainTest, /awaitUniqueWorkFinished/);
  assert.match(workChainTest, /withContext\(Dispatchers\.IO\)[\s\S]*withTimeout\(5_000\)/);
  assert.match(workChainTest, /TestWorkManagerLifecycle\.close/);
  assert.match(workLifecycle, /cancelAllWork\(\)[\s\S]*getWorkInfos\(active\)[\s\S]*pruneWork\(\)[\s\S]*closeWorkDatabase\(\)/);
  for (const worker of ["CreateDriveWorker", "UploadAssetsWorker", "PublishDriveWorker"]) assert.match(scheduler, new RegExp(worker));
  assert.match(workers, /original draft is retained after acknowledgement/);
  assert.equal((workers.match(/executionGate\.execute\(/g) ?? []).length, 3);
  assert.match(scheduler, /coordinateRecovery\([\s\S]*pauseRecoverableForMotion/);
  assert.match(scheduler, /RecoveryEnqueueMode\.REPLACE -> ExistingWorkPolicy\.REPLACE/);
  assert.match(read("app/src/main/java/com/socialdrive/app/data/work/PublishRecoveryPolicy.kt"), /status == "PAUSED_MOTION"/);
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
  assert.match(demoRemote, /putIfAbsent\(session, command\.checksumSha256Base64\)/);
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
  const posix = read("gradlew");
  const localLauncher = read("scripts/gradle-local.sh");
  const doctor = read("scripts/android-toolchain-doctor.sh");
  assert.match(windows, /Get-FileHash -Algorithm SHA256/);
  assert.match(windows, /Expand-Archive/);
  assert.match(windows, /--offline forbids downloading/);
  assert.match(posix, /GRADLE_USER_HOME/);
  assert.match(posix, /--offline forbids downloading/);
  assert.match(posix, /--proto '=https' --tlsv1\.2/);
  assert.match(localLauncher, /java_home -v 17/);
  assert.match(localLauncher, /GRADLE_USER_HOME="\$runtime_root\/gradle-user-home"/);
  assert.match(localLauncher, /TMPDIR="\$runtime_root\/tmp"/);
  assert.match(doctor, /Android platform 35 is missing/);
  assert.doesNotMatch(doctor, /sdkmanager|install|yes \|/);
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
  assert.match(app, /memoryStory = MemoryStoryPresentation\.browsing\(draft\.toLocalDriveSummary\(fallback\)\)[\s\S]*\.finishDraft\(draft\.highlightCount\)/);
  assert.match(app, /if \(motion == MotionState\.Parked\)[\s\S]*AppDestination\.Publish[\s\S]*초안으로 안전하게 보존/);
});

test("instrumented safety suite covers lock back confirmation and parked publish", () => {
  const relative = "app/src/androidTest/java/com/socialdrive/app/AutomotiveSafetyUiTest.kt";
  assert.equal(fs.existsSync(path.join(root, relative)), true);
  const ui = read(relative);
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const conflictUi = read("app/src/androidTest/java/com/socialdrive/app/ConflictReviewUiTest.kt");
  const host = read("app/src/androidTest/java/com/socialdrive/app/PleosComposeHost.kt");
  const hostTest = read("app/src/androidTest/java/com/socialdrive/app/PleosReadinessParserTest.kt");
  const readme = read("README.md");
  for (const marker of [
    "MotionState.Driving", "MotionState.Unknown", "assertDoesNotExist",
    "기록을 종료할까요?", "parkedUserCanReachPublishThroughDetailOnly", "드라이브 게시",
    "NavigationContractContent", "기록 중", "이 드라이브 게시하기",
    "activityBackDispatcherDuringRecordingShowsStopConfirmation", "host.dispatchBackToActivity()",
  ]) assert.ok(ui.includes(marker), marker);
  assert.doesNotMatch(ui, /pressBack|UiDevice|input keyevent|systemBack/);
  const contract = ui.slice(ui.indexOf("private fun NavigationContractContent"));
  for (const marker of [
    "AppDestination", "SafetyPolicy.canNavigate", "BackHandler", "HomeScreen", "RecordingScreen",
    "RecordingStopDialog", "DetailScreen", "PublishScreen", "SocialDriveTheme",
    "MotionState.Parked", "FakeDriveRepository()", "CapabilityState.Unavailable",
    "IntegrationSource.Demo", 'label = "테스트 내비게이션"',
    "NavigationState.Idle(navigationCapability)", "UploadState.Draft", "RouteSource.DemoPreset",
  ]) assert.ok(contract.includes(marker), `navigation contract: ${marker}`);
  assert.deepEqual(
    [...contract.matchAll(/var (\w+) by rememberSaveable/g)].map((match) => match[1]),
    ["destination", "selectedDrive", "stopConfirmationRequested"],
  );
  assert.doesNotMatch(
    contract,
    /SocialDriveApp\(|SocialDriveViewModel|WorkManager|SocialDriveDatabase|FlavorIntegrationFactory/,
  );
  assert.match(contract, /onDetail = \{ selectedDrive = it; navigate\(AppDestination\.Detail\) \}/);
  assert.match(contract, /onRecord = \{ navigate\(AppDestination\.Recording\) \}/);
  assert.match(contract, /onPublish = \{ navigate\(AppDestination\.Publish\) \}/);
  assert.match(contract, /BackHandler\(destination != AppDestination\.Home\)[\s\S]*destination == AppDestination\.Recording\) stopConfirmationRequested = true/);
  assert.match(ui, /host\.setContent \{ NavigationContractContent\(\) \}[\s\S]*compose\.waitForIdle\(\)/);
  assert.doesNotMatch(ui, /(?:Thread|SystemClock)\.sleep|waitUntil|@Ignore/);
  assert.match(app, /onDetail = \{ id ->[\s\S]*memoryStory = memoryStory\.select\(drive\)[\s\S]*navigate\(AppDestination\.Detail\)/);
  assert.match(app, /onRecord = \{[\s\S]*?navigate\(AppDestination\.Recording\)/);
  assert.match(app, /AppDestination\.Detail -> DetailScreen\([\s\S]*?onPublish = \{ navigate\(AppDestination\.Publish\) \}/);
  assert.match(app, /BackHandler\(destination != AppDestination\.Home\)[\s\S]*destination == AppDestination\.Recording\) stopConfirmationRequested = true/);
  for (const source of [ui, conflictUi]) {
    assert.match(source, /createEmptyComposeRule\(\)/);
    assert.match(source, /PleosComposeHost\(\)/);
    assert.match(source, /compose\.waitForIdle\(\)/);
    assert.doesNotMatch(source, /createComposeRule/);
    assert.doesNotMatch(source, /createAndroidComposeRule/);
    assert.doesNotMatch(
      source,
      /import androidx\.compose\.ui\.test\.assertDoesNotExist/,
      "Compose 1.7 exposes assertDoesNotExist as a SemanticsNodeInteraction member, not a top-level import",
    );
    assert.match(source, /\.assertDoesNotExist\(\)/);
  }
  assert.match(host, /awaitStableGeneration\(\)[\s\S]*assertPrelaunchStable\(\)[\s\S]*enterChildActive\(\)[\s\S]*ActivityScenario\.launch\(MainActivity::class\.java\)/);
  assert.equal((host.match(/ActivityScenario\.launch\(MainActivity::class\.java\)/g) ?? []).length, 1);
  assert.doesNotMatch(host, /moveToState\(/);
  assert.match(host, /ActivityScenario\.launch\(MainActivity::class\.java\)[\s\S]*state == Lifecycle\.State\.RESUMED[\s\S]*onActivity[\s\S]*lifecycle\.currentState == Lifecycle\.State\.RESUMED[\s\S]*assertChildLogHealthy/);
  assert.match(host, /fun observe\([\s\S]*homePidBefore: String,[\s\S]*dump: String,[\s\S]*homePidAfter: String,[\s\S]*currentUserId: Int\?/);
  assert.match(host, /PleosOrganizerSnapshot\(after, ids\.map \{ it\.single\(\) \}\)/);
  assert.match(host, /TASK_HEADER[\s\S]*ACTIVITY_RECORD/);
  assert.ok(host.includes('^\\\\s*\\\\*\\\\s+Task\\\\{'));
  assert.match(host, /mVisible=\(true\|false\)/);
  assert.match(host, /state=\(CREATED\|STARTED\|RESUMED\|PAUSED\|STOPPED\|DESTROYED\)/);
  assert.match(host, /groupBy\(TaskBlock::id\)/);
  assert.match(host, /task_representation_inconsistent/);
  assert.match(host, /PER_USER_UID_RANGE = 100_000/);
  assert.match(host, /fromUid\(android\.os\.Process\.myUid\(\)\)/);
  assert.doesNotMatch(host, /myUserHandle\(\)\.identifier/);
  assert.match(host, /task_record_context_mismatch/);
  assert.match(host, /HIST_RECORD[\s\S]*HIST_ZERO/);
  assert.ok(host.includes('Hist\\\\s+#[0-9]+:'));
  assert.match(host, /Global Resumed\/mFocusedApp\/application-token summaries use their own tNNN/);
  assert.doesNotMatch(host, /default_task_not_visible_active|required\.drop\(1\)/);
  assert.match(host, /if \(current == previous\) return true/);
  assert.match(host, /pidof ai\.umos\.homescreen/);
  assert.doesNotMatch(host, /pidof ai\.umos\.(?:drivingview|maps)/);
  assert.match(host, /dumpsys activity activities/);
  assert.match(host, /logcat -d -v epoch -T/);
  assert.doesNotMatch(host, /logcat -c|force-stop|\bsu\s+-c\b|adoptShellPermissionIdentity/);
  assert.match(host, /duplicate_listener/);
  assert.match(host, /fun setContent[\s\S]*withChildLogCheck[\s\S]*Lifecycle\.State\.RESUMED[\s\S]*activity\.setContent[\s\S]*Lifecycle\.State\.RESUMED/);
  assert.match(host, /fun dispatchBackToActivity\(\)[\s\S]*withChildLogCheck[\s\S]*onActivity[\s\S]*Lifecycle\.State\.RESUMED[\s\S]*onBackPressedDispatcher\.onBackPressed\(\)/);
  assert.match(readme, /System Back delivery to the embedded SocialDrive task \| \*\*UNRESOLVED\*\*/);
  assert.match(readme, /synthetic Back was observed routing to the Home organizer/);
  assert.match(readme, /TaskView focused[\s\S]*real control once[\s\S]*dialog must appear/);
  assert.match(host, /fun close\(\)[\s\S]*PleosCloseAudit\.run[\s\S]*assertChildLogHealthy[\s\S]*finishAndUnregister\(active\)[\s\S]*assertChildLogHealthy/);
  assert.match(host, /fun finishAndUnregister[\s\S]*LifecycleEventObserver[\s\S]*Lifecycle\.Event\.ON_DESTROY[\s\S]*activity\.finish\(\)[\s\S]*destroyed\.await\([\s\S]*waitForIdleSync\(\)[\s\S]*state == Lifecycle\.State\.DESTROYED[\s\S]*active\.close\(\)/);
  assert.doesNotMatch(host, /finishAndRemoveTask/);
  const childHealth = host.match(/fun assertChildLogHealthy\(\)[\s\S]*?\n    }/)?.[0] ?? "";
  assert.match(childHealth, /currentHomePid\(\)[\s\S]*logCheck/);
  assert.doesNotMatch(childHealth, /assertStableGenerationHealthy|observe\(|parseTasks|ACTIVITY_COMMAND/);
  assert.match(host, /fun assertPrelaunchStable\(\)[\s\S]*assertStableGenerationHealthy/);
  assert.match(host, /enum class PleosReadinessPhase \{ PrelaunchOrganizer, ChildActive \}/);
  assert.match(host, /mutableSetOf\(prelaunchHomePid, android\.os\.Process\.myPid\(\)\.toString\(\)\)/);
  assert.match(host, /present\.firstOrNull \{ it\.isCriticalHarnessEvidence\(\) \}/);
  assert.match(host, /InstrumentationActivityInvoker[\\\\]+\\\$EmptyActivity/);
  assert.match(host, /home_package_missing/);
  assert.match(host, /finally \{[\s\S]*scenario = null[\s\S]*readiness = null/);
  assert.match(host, /\^\(\?:logcat:\|\/system\/bin\/sh:/);
  assert.match(host, /if \(active\.state == Lifecycle\.State\.DESTROYED\)[\s\S]*active\.close\(\)/);
  assert.doesNotMatch(host, /(?:Thread|SystemClock)\.sleep|createAndroidComposeRule/);
  for (const marker of [
    "shortAndFullComponentsMapToOneVisibleGeneration",
    "duplicateMissingWrongUserHomeStateAndPidFlipFailClosed",
    "initializingDefaultTasksAreAcceptedOnlyAfterStableTaskIds",
    "stableTrackerRequiresTwoCoherentObservationsAndResetsOnNullOrChange",
    "fatalGuardIgnoresOldBaselineAndRejectsNewRelevantFatalOrDuplicate",
    "closeAuditAlwaysCleansAndPrioritizesPostFatalEvidence",
    "readinessSessionUsesOrganizerChecksOnlyBeforeChildLaunch",
    "currentUserDerivesFromPublicProcessUidRange",
  ]) assert.ok(hostTest.includes(marker), marker);
  assert.match(hostTest, /Task with ID 20 not found/);
  assert.match(hostTest, /fixture\(\) \+ fixture\(\)/);
  assert.match(hostTest, /task_representation_inconsistent/);
  assert.match(hostTest, /DrivingViewActivity t99/);
  assert.match(hostTest, /capturedStyleDump/);
  assert.match(hostTest, /Hist  #0/);
  assert.match(hostTest, /rootOfTask=true task=Task/);
  assert.match(hostTest, /topDisplayFocusedRootTask=Task/);
  assert.match(hostTest, /replace\("Hist  #", "Hist #"\)/);
  assert.match(hostTest, /1000299[\s\S]*1000318[\s\S]*1000304/);
  assert.match(hostTest, /drivingVisible = false/);
  assert.match(hostTest, /drivingState = "INITIALIZING"[\s\S]*naviState = "STOPPED"/);
  assert.match(hostTest, /fixture\(drivingUserId = 0\)/);
  assert.match(hostTest, /changedDrivingId[\s\S]*drivingId = "21"[\s\S]*assertFalse\(tracker\.record\(changedDrivingId\)\)/);
});

test("content and upload states are observable without judge-facing state controls", () => {
  const home = read("app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt");
  const publish = read("app/src/main/java/com/socialdrive/app/ui/screens/PublishScreen.kt");
  const fixtures = read("app/src/debug/java/com/socialdrive/app/ui/visual/VisualStateCatalog.kt");
  assert.doesNotMatch(home, /DemoContentMode\.entries/);
  assert.doesNotMatch(publish, /UploadState\.entries/);
  for (const scenario of ["HomeContent", "HomeLoading", "HomeEmpty", "HomeOfflineCached", "HomeError"]) {
    assert.match(fixtures, new RegExp(`VisualScenario\\.${scenario}`));
  }
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
  assert.deepEqual(permissions, [
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "pleos.car.permission.CAR_DRIVE",
    "pleos.car.permission.NAVI_ROUTE_SEARCH",
    "pleos.car.permission.FUSED_LOCATION",
    "pleos.car.permission.TTS_SERVICE",
    "pleos.car.permission.STT_SERVICE",
  ]);
  for (const forbidden of ["CONTROL_CAR_", "PRIVATE_CAR_"]) {
    assert.doesNotMatch(build + pleosManifest, new RegExp(forbidden));
  }
});

test("PLEOS TTS and STT permissions fail closed and remain recoverable", () => {
  const contracts = read("app/src/main/java/com/socialdrive/app/domain/IntegrationContracts.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  assert.match(contracts, /val speechPermissions: List<String>/);
  assert.match(contracts, /fun refreshSpeechPermissions\(\)/);
  assert.match(app, /RequestMultiplePermissions\(\)/);
  assert.match(app, /Lifecycle\.Event\.ON_RESUME[\s\S]*refreshSpeechPermissions\(\)/);
  assert.match(settings, /음성 권한 다시 확인/);
  assert.match(pleos, /TTS_PERMISSION[\s\S]*STT_PERMISSION/);
  assert.match(pleos, /if \(!hasPermission\(TTS_PERMISSION\)\)[\s\S]*permissionDenied/);
  assert.match(pleos, /if \(!hasPermission\(STT_PERMISSION\)\)[\s\S]*permissionDenied/);
  assert.match(pleos, /ttsInitialized[\s\S]*sttInitialized/);
  assert.match(pleos, /override fun speakFeedback\([\s\S]*if \(released\) return false/);
  assert.match(pleos, /override fun startSpeechRecognition\([\s\S]*if \(released\) return false/);
  assert.match(pleos, /if \(isFinal\)[\s\S]*finishRecognition\([\s\S]*runCatching \{ callback\?\.invoke\(text, true\) \}/);
  assert.match(pleos, /callback\?\.invoke\(text, false\)[\s\S]*finishRecognition\(SpeechRecognitionState\.Failed\)/);
  assert.match(pleos, /locationOperationLock[\s\S]*registerLocationCallback\([\s\S]*synchronized\(locationOperationLock\)/);
  assert.match(pleos, /val shouldRelease = synchronized\(speechOperationLock\)[\s\S]*if \(!shouldRelease\) return/);
  assert.match(pleos, /RawRouteCoordinate\.fromOrNull\(location\.longitude, location\.latitude\) \?: return/);
  assert.match(pleos, /BoundedRouteBuffer\(MAX_ROUTE_POINTS\)/);
});

test("a new publish session clears stale speech recognition feedback", () => {
  const contracts = read("app/src/main/java/com/socialdrive/app/domain/IntegrationContracts.kt");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  assert.match(contracts, /fun resetSpeechRecognitionState\(\)/);
  assert.match(pleos, /override fun resetSpeechRecognitionState\(\)[\s\S]*!mutableRecognitionActive\.value[\s\S]*SpeechRecognitionState\.Idle/);
  assert.match(viewModel, /fun resetDictationFeedback\(\)[\s\S]*resetSpeechRecognitionState\(\)/);
  assert.match(app, /target == AppDestination\.Publish[\s\S]*resetDictationFeedback\(\)/);
});

test("actual adapters fail closed and never fall back to demo state", () => {
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  const gearSmoke = read("app/src/androidTest/java/com/socialdrive/app/PleosGearRuntimeSmokeTest.kt");
  const demo = read("app/src/demo/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");
  assert.match(application, /FlavorIntegrationFactory\.create\(this\)/);
  assert.match(application, /vehicleMotionSource\.status\.collect[\s\S]*parkedExecutionGate\.transitionTo\(status\.motion\)/);
  assert.match(pleos, /PleosGearSignalPort\(context\)/);
  assert.match(pleos, /checkSelfPermission\(PleosPermission\.CAR_DRIVE\)/);
  assert.match(pleos, /gearApi = vehicle\.getGear\(\)/);
  assert.match(pleos, /gearApi\.getCurrentGearSelectionState/);
  assert.match(pleos, /gearApi\.registerGearSelection/);
  assert.match(pleos, /gearApi(?:::|\.)unregisterGearSelection/);
  assert.match(pleos, /VehicleGear\.GEAR_PARK -> GearSignal\.Park/);
  assert.match(pleos, /requestSimulation\(motion: MotionState\): Boolean = false/);
  assert.doesNotMatch(pleos, /DemoVehicle|FakePleos|println\(|Log\./);
  assert.match(demo, /requestSimulation[\s\S]*mutableStatus\.value/);
  assert.match(viewModel, /vehicleMotion\.value\.motion != MotionState\.Parked[\s\S]*NavigationRequestResult\.Rejected/);
  assert.match(app, /LaunchedEffect\(motion\)[\s\S]*motion != MotionState\.Parked[\s\S]*destination = AppDestination\.Home/);
  assert.match(gearSmoke, /val arguments = InstrumentationRegistry\.getArguments\(\)[\s\S]*assumeTrue\([\s\S]*arguments\.getString\(REQUIRE_PLEOS_GEAR_ARGUMENT\) == "true"[\s\S]*val host = PleosComposeHost\(\)/);
  assert.match(gearSmoke, /expectedPleosMotion/);
  assert.match(gearSmoke, /"parked"[\s\S]*MotionState\.Parked/);
  assert.match(gearSmoke, /"driving" -> MotionState\.Driving/);
  assert.match(gearSmoke, /withTimeoutOrNull\(PARK_CALLBACK_TIMEOUT_MILLIS\)/);
  assert.match(gearSmoke, /IntegrationSource\.Actual/);
  assert.match(gearSmoke, /CapabilityState\.Available/);
  assert.match(gearSmoke, /closeWithinDeadline\(host\)/);
  assert.match(gearSmoke, /isDaemon = true/);
  assert.match(gearSmoke, /completed\.await\(CLOSE_TIMEOUT_SECONDS, TimeUnit\.SECONDS\)/);
  assert.match(gearSmoke, /AtomicReference<Throwable\?>/);
  assert.match(gearSmoke, /CLOSE_TIMEOUT_SECONDS = 30L/);
  for (const stage of ["start", "host_launched", "source_acquired", "motion_observed", "asserted", "close_start", "close_done"]) {
    assert.ok(gearSmoke.includes(`stage=${stage}`), stage);
  }
  assert.doesNotMatch(gearSmoke, /source\.release\(|source\.initialize\(|println\(|printStackTrace|VehicleGear|status\.motion.*Log/);
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
    "route started is successful terminal evidence", "double callbacks", "timeout", "late callback",
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
  assert.match(coreRunner, /expectedTests = 94/);
  assert.match(readme, /does not compile Android framework code,[\s\S]*the PLEOS vendor adapters,[\s\S]*assemble an APK,[\s\S]*replace a PLEOS emulator run/);
});

test("cloud API core isolates auth from presigned uploads and actual flavor never falls back to fake", () => {
  const contracts = read("app/src/main/java/com/socialdrive/app/data/remote/CloudDriveContracts.kt");
  const client = read("app/src/main/java/com/socialdrive/app/data/remote/CloudDriveClient.kt");
  const adapter = read("app/src/main/java/com/socialdrive/app/data/remote/CloudRemoteDriveApi.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteDriveApi.kt");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const demoFactory = read("app/src/demo/java/com/socialdrive/app/data/remote/FlavorRemoteDriveApiFactory.kt");
  const demoFake = read("app/src/demo/java/com/socialdrive/app/data/remote/FakeRemoteDriveApi.kt");
  const actualFactory = read("app/src/pleos/java/com/socialdrive/app/data/remote/FlavorRemoteDriveApiFactory.kt");
  const cloudRuntime = read("app/src/pleos/java/com/socialdrive/app/data/remote/PleosCloudRuntime.kt");
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const coreScript = read("scripts/test-kotlin-core.sh");
  const suite = read("app/src/test/java/com/socialdrive/app/data/remote/CloudDriveClientTest.kt");
  const json = read("app/src/main/java/com/socialdrive/app/data/remote/JsonCodec.kt");
  const readme = read("README.md");

  assert.match(contracts, /fun interface AuthSessionProvider/);
  assert.match(contracts, /freshBearerToken/);
  assert.match(contracts, /fun interface ApiHttpTransport/);
  assert.match(contracts, /fun interface ObjectUploadTransport/);
  assert.match(client, /"Authorization" to token\.authorizationHeader\(\)/);
  assert.match(client, /check\(request\.headers\.keys\.none \{ it\.equals\("authorization"/);
  assert.match(client, /allowedUploadHosts/);
  assert.match(client, /ASSET_HOST_NOT_ALLOWED/);
  assert.match(client, /"content-type"/);
  assert.match(client, /"x-amz-checksum-sha256"/);
  for (const marker of ["AWS4-HMAC-SHA256", "X-Amz-Credential", "X-Amz-Date", "X-Amz-Expires", "X-Amz-Signature"]) {
    assert.ok(client.includes(marker), marker);
  }
  assert.match(client, /MAX_RESPONSE_BYTES = 2 \* 1024 \* 1024/);
  for (const marker of ["MAX_DEPTH = 32", "MAX_NODES = 10_000", "MAX_STRING_CHARS = 16_384"]) assert.ok(json.includes(marker), marker);
  assert.match(client, /SIGNED_UPLOAD_HEADER_ORDER/);
  assert.match(client, /fun refreshUploads/);
  assert.match(client, /fun feed/);
  assert.match(client, /fun getDrive/);
  assert.match(client, /fun updateDrive/);
  assert.match(client, /fun deleteDrive/);
  assert.match(adapter, /clientRequestId = clientRequestId/);
  assert.match(adapter, /client\.refreshUploads/);
  assert.match(adapter, /override suspend fun uploadAssets/);
  assert.match(adapter, /MAX_UPLOAD_SESSION_ATTEMPTS = 2/);
  assert.match(adapter, /commands\.map\(AssetUploadCommand::manifest\)[\s\S]*client\.refreshUploads\(remoteDriveId, manifests\)/);
  assert.match(workers, /remote\.uploadAssets\(remoteId, draft\.snapshot\(\)\.assetUploads\(\)\)/);
  assert.doesNotMatch(workers, /assetUploads\(\)\.forEach/);
  assert.match(adapter, /publishDrive\(remoteDriveId, expectedVersion\)/);
  assert.doesNotMatch(adapter, /getDrive\(remoteDriveId\)/);
  assert.match(remote, /AssetKind\.COVER/);
  assert.match(remote, /AssetKind\.ROUTE/);
  assert.match(application, /FlavorRemoteDriveApiFactory\.create\(\)/);
  assert.match(demoFactory, /FakeRemoteDriveApi/);
  assert.match(demoFake, /class FakeRemoteDriveApi/);
  assert.doesNotMatch(remote, /class FakeRemoteDriveApi/);
  assert.doesNotMatch(actualFactory, /FakeRemoteDriveApi/);
  assert.match(actualFactory, /SessionAwareRemoteDriveApi/);
  assert.match(cloudRuntime, /AtomicReference<CognitoSessionSource\?>\(null\)/);
  assert.match(cloudRuntime, /sessionSource\.compareAndSet\(session, null\)/);
  assert.match(remote, /current\(\) \?: UnavailableRemoteDriveApi\(\)/);
  assert.doesNotMatch(entities, /bearer|token|presigned|uploadUrl|authorization/i);
  assert.doesNotMatch(client + contracts + adapter, /android\.util\.Log|println\(|printStackTrace/);
  assert.match(adapter, /distanceMeters = draft\.distanceMeters/);
  assert.match(adapter, /durationSeconds = draft\.durationSeconds/);
  assert.match(adapter, /highlightCount = draft\.highlightCount/);
  assert.match(readme, /freezes actual duration, normalized route distance, and highlight count/);
  assert.doesNotMatch(readme, /highlightCount = 0|must remain unavailable until approved derived publication metadata/);
  for (const source of ["CloudDriveClient.kt", "CloudDriveClientTest.kt", "CloudRemoteDriveApi.kt", "PublicationCover.kt"]) {
    assert.ok(coreScript.includes(source), source);
  }
  for (const marker of [
    "fresh bearer token", "stable request id", "exact manifest", "only signed headers",
    "foreign host", "opaque cursor", "typed conflicts", "indistinguishable",
  ]) assert.ok(suite.includes(marker), marker);
});

test("PLEOS cloud build gate validates all public deployment outputs without secret fields", () => {
  const build = read("app/build.gradle.kts");
  const handoff = read("../docs/aws-app-connection.md");
  const auth = read("app/src/pleos/java/com/socialdrive/app/data/remote/AmplifyCognitoAuth.kt");
  const inputPolicy = read("app/src/main/java/com/socialdrive/app/data/remote/CloudAuthInputPolicy.kt");
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  assert.match(build, /tasks\.register\("verifyPleosCloudConfiguration"\)/);
  assert.match(build, /tasks\.named\("prePleosReleaseBuild"\)[\s\S]*dependsOn\("verifyPleosCloudConfiguration"\)/);
  assert.match(build, /"pleosImplementation"\("com\.amplifyframework:aws-auth-cognito:2\.27\.4"\)/);
  for (const marker of [
    "apiBaseUrl", "uploadHosts", "consentPolicyVersion", "cognitoRegion", "cognitoUserPoolId", "cognitoClientId",
  ]) assert.ok(build.includes(`\"${marker}\"`), marker);
  assert.match(build, /PLEOS cloud public configuration verified/);
  assert.match(build, /!isValidCloudHost\(apiUri\.host\.lowercase\(\)\)/);
  assert.doesNotMatch(build, /cloud.*(?:secret|password|accessKey)/i);
  assert.match(auth, /CloudAuthInputPolicy\.acceptsCredentials/);
  assert.match(auth, /CloudAuthInputPolicy\.acceptsChallenge/);
  assert.match(inputPolicy, /MAX_PASSWORD_CHARS = 256/);
  assert.match(inputPolicy, /MAX_CHALLENGE_CHARS = 2_048/);
  assert.match(settings, /CloudAuthInputPolicy\.limitUsername/);
  assert.match(settings, /CloudAuthInputPolicy\.limitPassword/);
  assert.match(settings, /CloudAuthInputPolicy\.limitChallenge/);
  assert.match(handoff, /\.\/gradlew verifyPleosCloudConfiguration/);
  assert.match(auth, /authenticationFlowType", "USER_SRP_AUTH"/);
  assert.match(auth, /Amplify\.Auth\.fetchAuthSession/);
  assert.match(auth, /userPoolTokensResult\?\.value\?\.accessToken/);
  assert.match(auth, /challengeLabel\(\)[\s\S]*clearAmplifySession\(\)/);
  assert.doesNotMatch(auth, /clientSecret|android\.util\.Log|println\(|printStackTrace/);
});

test("Cognito sign-out distinguishes complete partial and failed SDK results", () => {
  const auth = read("app/src/pleos/java/com/socialdrive/app/data/remote/AmplifyCognitoAuth.kt");
  assert.match(auth, /AWSCognitoAuthSignOutResult\.CompleteSignOut[\s\S]*resume\(true\)/);
  assert.match(auth, /AWSCognitoAuthSignOutResult\.PartialSignOut[\s\S]*resume\(false\)/);
  assert.match(auth, /AWSCognitoAuthSignOutResult\.FailedSignOut[\s\S]*resumeWithException/);
  assert.match(auth, /val globallyComplete = withCognitoOperationTimeout[\s\S]*clearSessionSource\(\)/);
  assert.match(auth, /CloudAuthPhase\.SIGNED_OUT[\s\S]*전체 로그아웃 상태/);
});

test("cloud auth secrets are cleared at both UI and ViewModel ownership boundaries", () => {
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  assert.match(settings, /val password = secret\.toCharArray\(\)[\s\S]*secret = ""[\s\S]*onSignIn\(username, password\)/);
  assert.match(settings, /val response = secret\.toCharArray\(\)[\s\S]*secret = ""[\s\S]*onConfirm\(response\)/);
  assert.match(viewModel, /signInToCloud[\s\S]*CoroutineStart\.UNDISPATCHED[\s\S]*finally[\s\S]*password\.fill\('\\u0000'\)/);
  assert.match(viewModel, /confirmCloudSignIn[\s\S]*CoroutineStart\.UNDISPATCHED[\s\S]*finally[\s\S]*response\.fill\('\\u0000'\)/);
});

test("publish concurrency and upload recovery retain frozen versions and bounded durable rewinds", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const daos = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const policy = read("app/src/main/java/com/socialdrive/app/data/work/PublishRecoveryPolicy.kt");
  const policyTest = read("app/src/test/java/com/socialdrive/app/data/work/PublishRecoveryPolicyTest.kt");
  assert.match(entities, /val remoteVersion: Int\?/);
  assert.match(entities, /val uploadRewindCount: Int/);
  assert.match(daos, /remoteVersion = :remoteVersion/);
  assert.match(daos, /rewindPublishToUpload/);
  assert.match(daos, /uploadRewindCount = uploadRewindCount \+ 1/);
  assert.match(daos, /stage = 'PUBLISH' AND status = 'RUNNING'[\s\S]*attempts = :attempts/);
  assert.match(daos, /failPublishAtUploadLimit/);
  assert.match(daos, /failRunningStage/);
  assert.match(daos, /claimStage/);
  assert.match(daos, /pauseRunningStage/);
  assert.match(daos, /completeCreateStage/);
  assert.match(daos, /completeUploadStage/);
  assert.match(daos, /completePublishStage/);
  assert.match(daos, /applyCreateResultCas/);
  assert.match(daos, /markPublishedCas/);
  assert.match(workers, /stalePublishResult/);
  assert.match(workers, /remoteVersion \?: return fail/);
  assert.match(workers, /acceptsCreateReplayIdentity\(draft\.remoteDriveId, replay\.id\)/);
  assert.match(workers, /draft\.remoteDriveId != null && draft\.remoteVersion != null[\s\S]*remote\.createDrive/);
  assert.match(workers, /publishDrive\(remoteId, expectedVersion, draft\.clientRequestId\)/);
  assert.match(workers, /markPublishedCas/);
  assert.doesNotMatch(workers, /updateStage\(/);
  assert.match(workers, /replaceFromUpload\(id\)/);
  assert.match(scheduler, /ExistingWorkPolicy\.REPLACE/);
  assert.match(scheduler, /PublishRecoveryPolicy\.recoveryEnqueueMode/);
  assert.match(policy, /MAX_UPLOAD_REWINDS = 2/);
  for (const marker of [
    "version conflict always stops", "rewinds only within", "expired upload remains", "commits before",
    "restart recovery", "stale rewind", "one exact lease", "stale create cannot overwrite",
    "stale upload pause and failure", "stale publish acknowledgement", "legacy create replay fails closed",
    "failed legacy row repaired to create",
  ]) {
    assert.ok(policyTest.includes(marker), marker);
  }
});

test("integration UI is explicit about actual demo and unavailable features", () => {
  const components = read("app/src/main/java/com/socialdrive/app/ui/components/RouteComponents.kt");
  const contentState = read("app/src/main/java/com/socialdrive/app/ui/components/ContentStatePanel.kt");
  const home = read("app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt");
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  for (const marker of ["IntegrationSource.Actual", "IntegrationSource.Demo", "IntegrationSource.Unavailable"]) {
    assert.ok(components.includes(marker), marker);
  }
  for (const marker of ["위치 연동", "음성 안내", "음성 인식", "차량 제어", "안전 정책 · 읽기 전용"]) {
    assert.ok(settings.includes(marker), marker);
  }
  assert.ok(!settings.includes("Icons.Rounded.CheckCircle"), "unavailable rows must not use a success icon");
  assert.match(components, /"데모 \$contextLabel"/);
  assert.match(home, /"차량 상태"/);
  assert.match(settings, /IntegrationRow\("AWS 클라우드"/);
  assert.doesNotMatch(settings, /Coming Soon/);
  assert.doesNotMatch(settings, /차량 상태 시뮬레이션/);
  assert.doesNotMatch(contentState, /progress\s*=/, "loading must not invent a determinate percentage");
});

test("preview matrix includes day night driving recording and failed upload", () => {
  const previews = read("app/src/main/java/com/socialdrive/app/ui/SocialDrivePreview.kt");
  assert.ok((previews.match(/@Preview/g) ?? []).length >= 5);
  for (const marker of ["ThemeMode.Day", "ThemeMode.Night", "MotionState.Driving", "RecordingScreen", "UploadState.Failed"]) assert.match(previews, new RegExp(marker.replace(".", "\\.")));
});

test("Demo and PLEOS screenshot variants keep the same complete baseline matrix", () => {
  const baselineNames = (variant) => {
    const directory = path.join(
      root,
      `app/src/screenshotTest${variant}Debug/reference/com/socialdrive/app/VisualScreenshotTestKt`,
    );
    assert.equal(fs.existsSync(directory), true, directory);
    return fs.readdirSync(directory).filter((name) => name.endsWith(".png")).sort();
  };
  const demo = baselineNames("Demo");
  const pleos = baselineNames("Pleos");
  assert.equal(demo.length, 27);
  assert.deepEqual(pleos, demo);
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

  const viewModel = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveViewModel.kt");
  assert.equal((viewModel.match(/@OptIn\(ExperimentalCoroutinesApi::class\)/g) ?? []).length, 3);
  assert.equal((viewModel.match(/\.flatMapLatest \{/g) ?? []).length, 3);
  for (const screen of ["DetailScreen", "PublishScreen", "ConflictReviewScreen", "SettingsScreen"]) {
    const source = read(`app/src/main/java/com/socialdrive/app/ui/screens/${screen}.kt`);
    assert.match(source, /androidx\.compose\.material\.icons\.automirrored\.rounded\.ArrowBack/);
    assert.match(source, /Icons\.AutoMirrored\.Rounded\.ArrowBack, contentDescription = "뒤로가기"/);
    assert.doesNotMatch(source, /icons\.rounded\.ArrowBack|Icons\.Rounded\.ArrowBack/);
  }
});

test("unknown vehicle motion fails closed for parked-only actions", () => {
  const safety = read("app/src/main/java/com/socialdrive/app/safety/SafetyPolicy.kt");
  assert.match(safety, /MotionState\.Unknown\s*->\s*SafetyDecision\(false/);
  assert.match(safety, /motion\s*==\s*MotionState\.Parked/);
});

test("route source consent withdrawal and delete pending stay privacy safe", () => {
  const domain = read("app/src/main/java/com/socialdrive/app/domain/RoutePrivacy.kt");
  const repository = read("app/src/main/java/com/socialdrive/app/data/RoutePrivacyRepository.kt");
  const offline = read("app/src/main/java/com/socialdrive/app/data/OfflineRepositories.kt");
  const application = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const routeFake = read("app/src/main/java/com/socialdrive/app/data/fake/FakeDriveRepository.kt");
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const daos = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteDriveApi.kt");
  const suite = read("app/src/test/java/com/socialdrive/app/domain/RoutePrivacyTest.kt");
  const mainManifest = read("app/src/main/AndroidManifest.xml");
  const build = read("app/build.gradle.kts");
  const pleos = read("app/src/pleos/java/com/socialdrive/app/integration/FlavorIntegrationFactory.kt");

  for (const marker of [
    "RouteSource", "DemoPreset", "LiveUnavailable", "SDK_COMING_SOON", "PERMISSION_DENIED",
    "CAPABILITY_MISSING", "LiveAvailable", "RouteConsentState", "NotAsked", "Granted", "Withdrawn",
  ]) assert.ok(domain.includes(marker), marker);
  assert.match(domain, /data object LiveAvailable : RouteSource/);
  assert.match(pleos, /collectionAuthorized && recordingRequested && !locationCollecting/);
  assert.match(pleos, /if \(locationCollecting\) rawLocations\.offer\(coordinate\)/);
  assert.match(domain, /class BoundedRouteBuffer[\s\S]*if \(points\.size >= maximumSize\) compact\(\)/);
  assert.match(domain, /if \(compacted\.last\(\) != latest\) compacted \+= latest/);
  assert.match(domain, /stopAndAwait\(\)[\s\S]*withdrawAndPurge\(\)[\s\S]*pendingUploads\.cancel/);
  assert.match(offline, /database\.withTransaction[\s\S]*effectiveRouteConsentState\([\s\S]*RouteConsentState\.Granted[\s\S]*collectedRoute\.points\.takeIf/);
  assert.match(repository, /RoomRoutePrivacyMutationStore/);
  assert.match(repository, /currentMotion = currentMotion/);
  assert.match(application, /currentMotion = \{ vehicleMotionSource\.status\.value\.motion \}/);
  assert.match(domain, /motion != MotionState\.Parked[\s\S]*MotionRejected/);
  assert.doesNotMatch(repository, /RemoteDriveApi|CloudDriveClient|deleteDrive/);
  assert.match(entities, /normalized_route_points/);
  assert.match(entities, /published_delete_requests/);
  assert.match(daos, /DELETE FROM normalized_route_points WHERE publicationState != 'PUBLISHED'/);
  assert.match(daos, /status = 'PRIVACY_WITHDRAWN'/);
  assert.match(daos, /stage NOT IN \('COMPLETE', 'CANCELLED'\)/);
  assert.match(scheduler, /ROUTE_UPLOAD_TAG/);
  assert.ok(remote.includes('return "{\\"schemaVersion\\":1,\\"path\\":[$path],\\"highlights\\":[]}"'));
  assert.doesNotMatch(remote, /latitude|longitude/);
  assert.doesNotMatch(build, /play-services-location|FusedLocationProvider|androidx\.core\.location/);
  assert.doesNotMatch(mainManifest, /ACCESS_(?:FINE|COARSE|BACKGROUND)_LOCATION/);
  assert.equal((routeFake.match(/routeSource = RouteSource\.DemoPreset/g) ?? []).length, 3);
  for (const marker of [
    "zero points before explicit consent", "atomically purges and tombstones", "late fake point",
    "parked published delete requires confirmation", "driving direct delete call", "unknown direct delete call",
    "changing to driving before confirm",
  ]) assert.ok(suite.includes(marker), marker);
});

test("demo route source remains visible across every route surface", () => {
  for (const relative of [
    "app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/PublishScreen.kt",
    "app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt",
  ]) {
    const source = read(relative);
    assert.match(source, /DemoDataBadge|RouteSourceBadge|데모 데이터/, relative);
  }
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  assert.match(settings, /enabled = motion == MotionState\.Parked/);
  assert.match(settings, /SDK 준비 중 · 향후 제공 예정/);
});

test("published delete is parked durable idempotent bounded and fail closed", () => {
  const lifecycle = read("app/src/main/java/com/socialdrive/app/domain/DeleteLifecycle.kt");
  const entity = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const dao = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const worker = read("app/src/main/java/com/socialdrive/app/data/work/DeleteWork.kt");
  const cancellationGuard = read("app/src/main/java/com/socialdrive/app/data/work/DeleteCancellationGuard.kt");
  const client = read("app/src/main/java/com/socialdrive/app/data/remote/CloudDriveClient.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteDeleteApi.kt");
  const app = read("app/src/main/java/com/socialdrive/app/SocialDriveApplication.kt");
  const actualFactory = read("app/src/pleos/java/com/socialdrive/app/data/remote/FlavorRemoteDeleteApiFactory.kt");
  const settings = read("app/src/main/java/com/socialdrive/app/ui/screens/SettingsScreen.kt");
  const androidTest = read("app/src/androidTest/java/com/socialdrive/app/PublishedDeleteWorkTest.kt");
  const offlineTest = read("app/src/androidTest/java/com/socialdrive/app/OfflinePersistenceTest.kt");
  const cancellationSuite = read("app/src/test/java/com/socialdrive/app/data/work/DeleteCancellationGuardTest.kt");
  const coreScript = read("scripts/test-kotlin-core.sh");
  assert.match(lifecycle, /MAX_ATTEMPTS = 4/);
  assert.match(lifecycle, /NOT_FOUND_UNCONFIRMED/);
  assert.match(lifecycle, /AUTHENTICATION, DeleteFailureCode\.FORBIDDEN/);
  assert.match(lifecycle, /stableKeyHash/);
  assert.match(entity, /idempotencyKeyHash/);
  assert.doesNotMatch(entity, /deleteReceipt|bearer|token|presigned|authorization/i);
  assert.match(dao, /state = 'RUNNING'.*attempts = :runningAttempt/s);
  assert.match(dao, /NOT EXISTS \([\s\S]*published_delete_requests/);
  assert.match(worker, /executionGate\.execute/);
  assert.match(worker, /DeleteCancellationGuard\.run[\s\S]*handoffInterruptedLease = \{ persistInterruptedLease/);
  assert.match(cancellationGuard, /catch \(cancelled: CancellationException\)[\s\S]*withContext\(NonCancellable\)[\s\S]*handoffInterruptedLease/);
  assert.match(cancellationSuite, /cancellation while persisting retry failure hands off running lease/);
  assert.match(cancellationSuite, /cancellation while persisting terminal 409 hands off running lease/);
  assert.match(coreScript, /DeleteCancellationGuardTest\.kt/);
  assert.match(worker, /persistInterruptedLease[\s\S]*"INTERRUPTED"/);
  assert.match(worker, /RemoteDeleteConfirmed|confirmCas/);
  assert.match(worker, /local original draft is deliberately retained/);
  assert.match(client, /"Idempotency-Key" to stableKeyHash/);
  assert.match(client, /terminalDeleteConflict = true/);
  assert.match(client, /terminalDeleteConflict && response\.status == 409[\s\S]*retryable = false/);
  assert.match(remote, /404 must be surfaced as NOT_FOUND/);
  assert.match(app, /deleteWorkScheduler\.recoverAfterProcessRestart\(\)[\s\S]*publishScheduler\.recoverAfterProcessRestart\(\)/);
  assert.match(actualFactory, /UnavailableRemoteDeleteApi/);
  assert.doesNotMatch(actualFactory, /FakeRemoteDeleteApi/);
  assert.match(settings, /삭제 완료로 간주하지 않았습니다/);
  assert.match(settings, /로컬 원본은 유지/);
  assert.match(androidTest, /awaitRealCondition[\s\S]*withContext\(Dispatchers\.IO\)[\s\S]*withTimeout\(5_000\)/);
  assert.match(androidTest, /TestWorkManagerLifecycle\.close/);
  assert.match(offlineTest, /assertEquals\("drive-request-11", request\.clientRequestId\)/);
  for (const marker of [
    "backend204ConfirmsTombstoneAndRetainsLocalOriginal",
    "backend404IsNotTreatedAsDeletionReceipt",
    "allDelete409VariantsStopWithoutAutomaticRetry",
    "motionChangeBeforeExecutionMakesZeroRemoteCallsAndRestoresAttempt",
    "tombstoneBlocksPublishClaimEvenIfQueueRowExists",
    "processRestartNormalizesRunningLeaseBeforeUniqueRecovery",
    "externalCancellationDurablyHandsOffLeaseAndSameProcessReplayConfirms",
  ]) assert.ok(androidTest.includes(marker), marker);
});

test("fake data fixtures contain no credentials and navigation uses an explicit public destination", () => {
  const sourceRoot = path.join(root, "app/src/main/java/com/socialdrive/app/data/fake");
  const source = kotlinFiles(sourceRoot).map((file) => fs.readFileSync(file, "utf8")).join("\n");
  assert.doesNotMatch(source, /\b(?:access[_-]?key|secret[_-]?key)\b/i);
  assert.doesNotMatch(source, /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/);
  assert.match(source, /name = "공개 데모 목적지"/);
});

test("hex colors stay inside the theme color source", () => {
  const sourceRoot = path.join(root, "app/src/main/java");
  for (const file of kotlinFiles(sourceRoot)) {
    if (file.endsWith(path.join("theme", "Color.kt"))) continue;
    assert.doesNotMatch(fs.readFileSync(file, "utf8"), /0x[0-9A-Fa-f]{8}/, file);
  }
});

test("memory story reuses privacy-safe artwork from recording through publish", () => {
  const components = read("app/src/main/java/com/socialdrive/app/ui/components/RouteComponents.kt");
  const repository = read("app/src/main/java/com/socialdrive/app/data/fake/FakeDriveRepository.kt");
  const home = read("app/src/main/java/com/socialdrive/app/ui/screens/HomeScreen.kt");
  const recording = read("app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt");
  const detail = read("app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt");
  const publish = read("app/src/main/java/com/socialdrive/app/ui/screens/PublishScreen.kt");
  const app = read("app/src/main/java/com/socialdrive/app/ui/SocialDriveApp.kt");
  const presentation = read("app/src/main/java/com/socialdrive/app/ui/MemoryStoryPresentation.kt");
  const screenshots = read("app/src/screenshotTest/kotlin/com/socialdrive/app/VisualScreenshotTest.kt");

  assert.match(components, /fun RouteMemoryArtwork/);
  assert.match(components, /MemoryPalette\.Coast[\s\S]*MemoryPalette\.Mountain[\s\S]*MemoryPalette\.City/);
  assert.match(components, /개인 위치가 제거된 데모 경로/);
  assert.doesNotMatch(components, /latitude|longitude/i);
  assert.match(repository, /coastRoute[\s\S]*mountainRoute[\s\S]*cityRoute/);
  assert.doesNotMatch(home, /카드 태그 · 바다 · 산 · 도심/);
  assert.match(home, /OfflineCacheBanner[\s\S]*DriveGrid\(filterDrives\(state\.cached, selectedFilter\), onDetail, Modifier\.weight\(1f\)\)/);
  assert.match(recording, /순간 \$count 저장됨 · 주차 후 이야기에서 확인/);
  assert.match(recording, /points = snapshot\.route/);
  assert.doesNotMatch(recording, /val demoRoute/);
  assert.match(detail, /selectedMomentIndex[\s\S]*순간 \$\{selectedMomentIndex \+ 1}/);
  assert.match(publish, /RouteMemoryArtwork/);
  assert.doesNotMatch(publish, /ROUTE ARTWORK/);
  assert.match(presentation, /fun beginRecording[\s\S]*fun recordingProgress[\s\S]*fun finishDraft[\s\S]*fun present/);
  assert.match(app, /RecordingSnapshot\([\s\S]*storyId = presentedStory\.id[\s\S]*route = recordingArtworkRoute\(routePrivacy\.displayedSource, displayedRoute\)[\s\S]*highlights = presentedStory\.highlights/);
  assert.match(app, /AppDestination\.Publish[\s\S]*route = localDraftDrive\?\.route \?: displayedRoute[\s\S]*highlightCount = displayedDraft\?\.highlightCount \?: presentedStory\.highlights/);
  assert.match(app, /AppDestination\.Detail -> DetailScreen\([\s\S]*drive = presentedStory/);
  for (const marker of ["PublishDraftDay", "PublishDraftNight", "PublishDraftLargeText"]) {
    assert.ok(screenshots.includes(marker), marker);
  }
});

test("day highlight time foreground meets WCAG text contrast", () => {
  const colors = read("app/src/main/java/com/socialdrive/app/ui/theme/Color.kt");
  const theme = read("app/src/main/java/com/socialdrive/app/ui/theme/Theme.kt");
  const detail = read("app/src/main/java/com/socialdrive/app/ui/screens/DetailScreen.kt");
  const namedRgb = (name) => {
    const match = colors.match(new RegExp(`val ${name} = Color\\(0xFF([0-9A-Fa-f]{6})\\)`));
    assert.ok(match, `${name} must be a literal opaque theme color`);
    return [0, 2, 4].map((offset) => Number.parseInt(match[1].slice(offset, offset + 2), 16));
  };
  const luminance = (rgb) => rgb
    .map((channel) => channel / 255)
    .map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
    .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
  const contrast = (foreground, background) => {
    const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
    return (lighter + 0.05) / (darker + 0.05);
  };
  const foreground = namedRgb("MomentAmberForegroundDay");
  for (const surface of ["DaySurface", "DaySurfaceRaised", "MomentAmberContainerDay"]) {
    assert.ok(contrast(foreground, namedRgb(surface)) >= 4.5, `${surface} contrast must be at least 4.5:1`);
  }
  assert.match(theme, /onTertiaryContainer = MomentAmberForegroundDay/);
  assert.match(detail, /moment\.time,[\s\S]*color = MaterialTheme\.colorScheme\.onTertiaryContainer/);
  assert.doesNotMatch(detail, /Text\(moment\.time, color = MaterialTheme\.colorScheme\.tertiary/);
});

test("recording stop action has a visible automotive button boundary", () => {
  const recording = read("app/src/main/java/com/socialdrive/app/ui/screens/RecordingScreen.kt");
  assert.match(recording, /onClick = onRequestStop,[\s\S]*heightIn\(min = 72\.dp\)[\s\S]*containerColor = MaterialTheme\.colorScheme\.surface,[\s\S]*contentColor = MaterialTheme\.colorScheme\.primary,[\s\S]*border = BorderStroke\(2\.dp, MaterialTheme\.colorScheme\.primary\)/);
});

test("version conflicts are durable explicit parked-only reviews", () => {
  const entities = read("app/src/main/java/com/socialdrive/app/data/local/Entities.kt");
  const daos = read("app/src/main/java/com/socialdrive/app/data/local/Daos.kt");
  const workers = read("app/src/main/java/com/socialdrive/app/data/work/PublishWorkers.kt");
  const repository = read("app/src/main/java/com/socialdrive/app/data/ConflictReviewRepository.kt");
  const scheduler = read("app/src/main/java/com/socialdrive/app/data/work/PublishScheduler.kt");
  const remote = read("app/src/main/java/com/socialdrive/app/data/remote/RemoteConflictReviewApi.kt");
  const actualReview = read("app/src/pleos/java/com/socialdrive/app/data/remote/FlavorRemoteConflictReviewApiFactory.kt");
  const demoReview = read("app/src/demo/java/com/socialdrive/app/data/remote/FlavorRemoteConflictReviewApiFactory.kt");
  const screen = read("app/src/main/java/com/socialdrive/app/ui/screens/ConflictReviewScreen.kt");
  assert.match(entities, /publish_conflicts/);
  assert.match(entities, /val clientRequestId: String[\s\S]*val replayCount: Int[\s\S]*val fetchGeneration: Int/);
  assert.doesNotMatch(entities, /presigned|authorization|accessToken|uploadUrl/i);
  assert.match(daos, /fetchGeneration = fetchGeneration \+ 1/);
  assert.match(daos, /fetchGeneration = :fetchGeneration AND state = 'FETCHING'/);
  assert.match(daos, /replayCount = replayCount \+ 1/);
  assert.match(daos, /replayCount < :maxReplays/);
  assert.match(daos, /NOT \(:expectedStatus = 'FAILED' AND COALESCE\(lastErrorCode, ''\) = 'VERSION_CONFLICT'\)/);
  assert.match(workers, /withContext\(NonCancellable\)[\s\S]*failVersionConflictCas[\s\S]*insertIfAbsent/);
  assert.match(repository, /executionGate\.execute/);
  assert.match(repository, /catch \(cancelled: CancellationException\)[\s\S]*withContext\(NonCancellable\)[\s\S]*"INTERRUPTED"/);
  assert.match(daos, /normalizeInterruptedFetches/);
  assert.match(repository, /recoverInterruptedFetches/);
  assert.match(repository, /claimFetch[\s\S]*getOwnedDrive[\s\S]*storeSnapshotCas/);
  assert.match(repository, /queueReplayCas[\s\S]*adoptConflictVersionCas[\s\S]*queueConflictReplayCas/);
  assert.match(scheduler, /replacePublishOnly/);
  const workChainTest = read("app/src/androidTest/java/com/socialdrive/app/PublishWorkChainTest.kt");
  assert.match(workChainTest, /restartedPublishWorkerCannotClaimCommittedConflictFailure/);
  assert.match(workChainTest, /assertEquals\(0, remote\.publishCalls\)/);
  assert.match(workChainTest, /transientFailedRowRemainsClaimableWithinWorkerRetryPolicy/);
  assert.match(workChainTest, /replayQueuedRestartCreatesPublishOnlyChain[\s\S]*assertEquals\(1, remote\.publishCalls\)/);
  assert.match(remote, /short-lived links, response headers and request IDs never cross it/);
  assert.match(actualReview, /UnavailableRemoteConflictReviewApi/);
  assert.doesNotMatch(actualReview, /DemoRemoteConflictReviewApi/);
  assert.match(demoReview, /DemoRemoteConflictReviewApi/);
  assert.match(screen, /서버의 새 버전이 있어요/);
  assert.match(screen, /자동으로 덮어쓰지 않았습니다\. 초안은 이 기기에 그대로 있습니다\./);
  assert.match(screen, /motion == MotionState\.Parked/);
  assert.match(screen, /최신 버전 확인/);
  assert.match(screen, /최신 버전을 기준으로 다시 게시/);
  assert.match(screen, /로컬 초안 유지/);
});

test("local Android backend harness stays real, socket-free and privacy bounded", () => {
  const bridge = read("app/src/test/java/com/socialdrive/app/data/remote/LocalBackendContractBridge.kt");
  const suite = read("app/src/test/java/com/socialdrive/app/data/remote/AndroidBackendContractHarnessTest.kt");
  const runner = read("scripts/test-contract-harness.sh");
  const server = fs.readFileSync(path.join(root, "../infra/test/contract-harness-server.mjs"), "utf8");

  assert.match(bridge, /ProcessBuilder\("node"/);
  assert.doesNotMatch(bridge, /ServerSocket|HttpServer|\bSocket\s*\(|System\.getenv/);
  assert.match(server, /createHandler/);
  assert.match(server, /MemoryRepository/);
  assert.doesNotMatch(server, /production-adapters|\bAWS_|process\.env|createServer|\.listen\s*\(/);
  assert.match(runner, /testDemoDebugUnitTest/);
  assert.match(runner, /testPleosDebugUnitTest/);
  for (const marker of [
    "privacy-safe lifecycle with deterministic conflicts and delete replay",
    "object adapter rejects MIME size and checksum mismatches",
    "loading authentication retryable transport and malformed response",
    "bounded complete manifest is rejected locally",
    "malformed and oversized request bodies return safe handler envelopes",
  ]) assert.ok(suite.includes(marker), marker);
});
