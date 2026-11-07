import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

process.env.LC_ALL = "C";
process.env.LC_CTYPE = "C";
process.env.LANG = "C";

const androidRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.dirname(androidRoot);
const buildScript = fs.readFileSync(path.join(androidRoot, "scripts/build-submission-release.sh"), "utf8");
const installScript = fs.readFileSync(path.join(androidRoot, "scripts/verify-pleos-clean-install.sh"), "utf8");
const gradleBuild = fs.readFileSync(path.join(androidRoot, "app/build.gradle.kts"), "utf8");

const fingerprint = "a".repeat(64);
const realGit = execFileSync("which", ["git"], { encoding: "utf8" }).trim();

function writeExecutable(target, content) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, content, { mode: 0o755 });
}

function makeFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "social-drive-release-test-"));
  const repo = path.join(root, "repo");
  const android = path.join(repo, "android");
  const bin = path.join(root, "bin");
  fs.mkdirSync(path.join(android, "scripts"), { recursive: true });
  fs.mkdirSync(bin, { recursive: true });
  fs.writeFileSync(path.join(repo, ".gitignore"), "android/**/build/\nandroid/.gradle/\n");
  writeExecutable(path.join(android, "scripts", "build-submission-release.sh"), buildScript);
  writeExecutable(path.join(android, "scripts", "gradle-local.sh"), `#!/usr/bin/env bash
set -euo pipefail
rm -rf "${android}/build" "${android}/app/build"
mkdir -p "${android}/app/build/outputs/apk/demo/release"
mkdir -p "${android}/app/build/outputs/apk/pleos/release"
printf demo > "${android}/app/build/outputs/apk/demo/release/app-demo-release.apk"
if [[ -n "\${MOCK_IDENTICAL_ARTIFACTS:-}" ]]; then
  printf demo > "${android}/app/build/outputs/apk/pleos/release/app-pleos-release.apk"
else
  printf pleos > "${android}/app/build/outputs/apk/pleos/release/app-pleos-release.apk"
fi
`);
  writeExecutable(path.join(bin, "git"), `#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *" merge-base --is-ancestor dcffaad HEAD "* ]]; then
  [[ -z "\${MOCK_MISSING_PR2_ANCESTRY:-}" ]]
  exit
fi
exec ${JSON.stringify(realGit)} "$@"
`);
  writeExecutable(path.join(bin, "apksigner"), `#!/usr/bin/env bash
set -euo pipefail
printf 'Verifies\n'
printf 'Signer #1 certificate DN: %s\n' "${"${MOCK_SIGNER_SUBJECT:-CN=Social Drive Release}"}"
printf 'Signer #1 certificate SHA-256 digest: %s\n' "${"${MOCK_SIGNER_SHA:-}"}"
`);
  writeExecutable(path.join(bin, "unzip"), `#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == "-Z1" ]]; then
  if [[ -n "${"${MOCK_SENSITIVE_ENTRY:-}"}" ]]; then printf '.env\n'; else printf 'AndroidManifest.xml\nclasses.dex\n'; fi
else
  if [[ -n "${"${MOCK_SENSITIVE_CONTENT:-}"}" ]]; then printf 'http://127.0.0.1/debug\n'; else printf 'safe fixture content\n'; fi
fi
`);
  writeExecutable(path.join(bin, "aapt2"), `#!/usr/bin/env bash
set -euo pipefail
case "\${*: -1}" in
  *demo*) package="\${MOCK_DEMO_PACKAGE:-com.socialdrive.app.demo}" ;;
  *pleos*) package="\${MOCK_PLEOS_PACKAGE:-com.socialdrive.app}" ;;
  *) exit 9 ;;
esac
printf "package: name='%s' versionCode='1' versionName='0.1.0'\\n" "$package"
`);
  execFileSync("git", ["init", "-q"], { cwd: repo });
  execFileSync("git", ["config", "user.email", "release-test@example.invalid"], { cwd: repo });
  execFileSync("git", ["config", "user.name", "Release Test"], { cwd: repo });
  execFileSync("git", ["add", "."], { cwd: repo });
  execFileSync("git", ["commit", "-qm", "fixture"], { cwd: repo });
  const keystore = path.join(root, "release.jks");
  fs.writeFileSync(keystore, "not-a-real-keystore");
  const env = {
    ...process.env,
    LC_ALL: "C",
    LANG: "C",
    PATH: `${bin}:${process.env.PATH}`,
    SOCIAL_DRIVE_RELEASE_STORE_FILE: keystore,
    SOCIAL_DRIVE_RELEASE_STORE_PASSWORD: "fixture-store-password",
    SOCIAL_DRIVE_RELEASE_KEY_ALIAS: "release",
    SOCIAL_DRIVE_RELEASE_KEY_PASSWORD: "fixture-key-password",
    SOCIAL_DRIVE_RELEASE_CERT_SHA256: fingerprint,
    MOCK_SIGNER_SHA: fingerprint,
  };
  return { root, repo, android, keystore, env };
}

function run(fixture, env = fixture.env) {
  return spawnSync("bash", [path.join(fixture.android, "scripts/build-submission-release.sh")], {
    cwd: fixture.repo,
    env,
    encoding: "utf8",
  });
}

function makeInstallFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "social-drive-install-test-"));
  const bin = path.join(root, "bin");
  const output = path.join(root, "submission", "b".repeat(40));
  fs.mkdirSync(bin, { recursive: true });
  fs.mkdirSync(output, { recursive: true });
  const demo = path.join(output, "SocialDrive_demo_release.apk");
  const pleos = path.join(output, "SocialDrive_pleos_release.apk");
  fs.writeFileSync(demo, "demo-release");
  fs.writeFileSync(pleos, "pleos-release");
  const hash = (file) => execFileSync("shasum", ["-a", "256", file], { encoding: "utf8" }).split(/\s+/)[0];
  fs.writeFileSync(path.join(output, "metadata.json"), `${JSON.stringify({
    schemaVersion: 1,
    artifacts: [
      { sourceSha: "b".repeat(40), variant: "demoRelease", filename: path.basename(demo), sha256: hash(demo) },
      { sourceSha: "b".repeat(40), variant: "pleosRelease", filename: path.basename(pleos), sha256: hash(pleos) },
    ],
  }, null, 2)}\n`);
  writeExecutable(path.join(bin, "apksigner"), `#!/usr/bin/env bash
printf 'Verifies\nSigner #1 certificate DN: CN=Social Drive Release\nSigner #1 certificate SHA-256 digest: %s\n' "${fingerprint}"
`);
  writeExecutable(path.join(bin, "aapt2"), `#!/usr/bin/env bash
printf "package: name='com.socialdrive.app' versionCode='1' versionName='0.1.0'\n"
`);
  writeExecutable(path.join(bin, "sleep"), "#!/usr/bin/env bash\nexit 0\n");
  const state = path.join(root, "adb-state");
  writeExecutable(path.join(bin, "adb"), `#!/usr/bin/env bash
set -euo pipefail
state="${state}"
if [[ "\${1:-}" == "devices" ]]; then
  if [[ -n "\${MOCK_ADB_HANG:-}" ]]; then /bin/sleep 5; fi
  printf 'List of devices attached\\n'
  printf '%b' "\${MOCK_ADB_DEVICES:-PLEOS-1\\tdevice\\n}"
  exit 0
fi
[[ "\${1:-}" == "-s" && "\${2:-}" == "PLEOS-1" ]] || exit 9
shift 2
if [[ "\${1:-}" == "install" ]]; then printf 'installed' > "\$state"; exit 0; fi
if [[ "\${1:-}" == "pull" ]]; then cp "${pleos}" "\${3}"; exit 0; fi
[[ "\${1:-}" == "shell" ]] || exit 9
shift
case "\${1:-} \${2:-}" in
  'getprop sys.boot_completed') printf '1\\n' ;;
  'getprop ro.build.version.sdk') printf '34\\n' ;;
  'getprop ro.product.name') printf 'PLEOS_Connect\\n' ;;
  'getprop ro.build.display.id') printf '%s\\n' "\${MOCK_BUILD_VERSION:-PLEOS-2.1.0}" ;;
  'am get-current-user') printf '10\\n' ;;
  'pm path') if [[ -n "\${MOCK_EXISTING_PACKAGE:-}" || -f "\$state" ]]; then printf 'package:/data/app/base.apk\\n'; fi ;;
  'dumpsys package') printf ' versionCode=1 minSdk=28 targetSdk=35\\n versionName=0.1.0\\n' ;;
  'am start')
    previous="\$(cat "\$state" 2>/dev/null || true)"
    if [[ -n "\${MOCK_SAME_PID:-}" ]]; then pid=101
    elif [[ "\$previous" == *last=101* ]]; then pid=202
    else pid=101
    fi
    printf 'installed last=%s pid=%s' "\$pid" "\$pid" > "\$state"
    printf 'Status: ok\\nActivity: com.socialdrive.app/.MainActivity\\n'
    ;;
  'pidof -s') sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' "\$state" 2>/dev/null ;;
  'am force-stop') sed 's/ pid=[0-9][0-9]*//' "\$state" > "\$state.tmp"; mv "\$state.tmp" "\$state" ;;
  'dumpsys activity')
    if [[ "\${3:-}" == "activities" ]]; then
      [[ -n "\${MOCK_NOT_RESUMED:-}" ]] || printf 'mResumedActivity: ActivityRecord com.socialdrive.app/.MainActivity\\n'
    elif [[ "\${3:-}" == "exit-info" && -n "\${MOCK_CRASH_EXIT:-}" ]]; then
      printf 'ApplicationExitInfo reason=4 (CRASH)\\n'
    fi
    ;;
  *) exit 9 ;;
esac
`);
  const env = {
    ...process.env,
    LC_ALL: "C",
    LANG: "C",
    PATH: `${bin}:${process.env.PATH}`,
    SOCIAL_DRIVE_RELEASE_CERT_SHA256: fingerprint,
  };
  const runInstall = (overrides = {}) => spawnSync("bash", [
    path.join(androidRoot, "scripts/verify-pleos-clean-install.sh"),
    "--serial", "PLEOS-1", "--apk", pleos,
  ], { cwd: root, env: { ...env, ...overrides }, encoding: "utf8" });
  return { root, output, demo, pleos, runInstall };
}

test("Gradle fails closed from the resolved release artifact task graph", () => {
  assert.match(gradleBuild, /gradle\.taskGraph\.whenReady/);
  assert.match(gradleBuild, /allTasks\.filter/);
  assert.match(gradleBuild, /assemble\|bundle\|package\|install/);
  assert.match(gradleBuild, /\^publish\.\*Release/);
  assert.match(gradleBuild, /package\|sign/);
  assert.match(gradleBuild, /Release signing environment is incomplete/);
  assert.match(gradleBuild, /must be outside the repository/);
  assert.match(gradleBuild, /replace\(Regex\("\[:\\\\s\]"\)/);
  assert.match(gradleBuild, /getByName\("release"\)/);
  assert.doesNotMatch(gradleBuild, /debug.*signingConfig|signingConfigs\.getByName\("debug"\)/s);
});

test("resolved Gradle abbreviations cannot bypass missing release signing", () => {
  const env = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !key.startsWith("SOCIAL_DRIVE_RELEASE_")),
  );
  env.LC_ALL = "C";
  env.LANG = "C";
  const result = spawnSync("bash", [
    path.join(androidRoot, "scripts/gradle-local.sh"), "--offline", "--dry-run", ":app:aDR",
  ], { cwd: androidRoot, env, encoding: "utf8" });
  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(`${result.stdout}\n${result.stderr}`, /Release signing environment is incomplete/);

  const lint = spawnSync("bash", [
    path.join(androidRoot, "scripts/gradle-local.sh"), "--offline", "--dry-run", ":app:lintDemoRelease",
  ], { cwd: androidRoot, env, encoding: "utf8" });
  assert.equal(lint.status, 0, `${lint.stdout}\n${lint.stderr}`);
});

test("missing, partial, and empty signing environments fail before build", (t) => {
  const fixture = makeFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  for (const overrides of [
    Object.fromEntries(Object.keys(fixture.env).filter((key) => !key.startsWith("SOCIAL_DRIVE_RELEASE_")).map((key) => [key, fixture.env[key]])),
    { ...fixture.env, SOCIAL_DRIVE_RELEASE_KEY_PASSWORD: undefined },
    { ...fixture.env, SOCIAL_DRIVE_RELEASE_KEY_ALIAS: "" },
  ]) {
    const result = run(fixture, overrides);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /signing environment is incomplete/);
  }
});

test("keystore path rejects repository files, missing files, and directories", (t) => {
  const fixture = makeFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  const internal = path.join(fixture.repo, "internal.jks");
  fs.writeFileSync(internal, "internal");
  execFileSync("git", ["add", "internal.jks"], { cwd: fixture.repo });
  execFileSync("git", ["commit", "-qm", "add internal fixture"], { cwd: fixture.repo });
  const symlink = path.join(fixture.root, "linked-release.jks");
  fs.symlinkSync(internal, symlink);
  for (const store of [internal, symlink, path.join(fixture.root, "missing.jks"), fixture.root]) {
    const result = run(fixture, { ...fixture.env, SOCIAL_DRIVE_RELEASE_STORE_FILE: store });
    assert.notEqual(result.status, 0, `expected rejection for ${store}: ${result.stdout}${result.stderr}`);
    assert.match(result.stderr, /keystore/, `expected keystore error for ${store}`);
  }
});

test("dirty source, signer mismatch, and Android Debug subject are rejected", (t) => {
  const dirty = makeFixture();
  t.after(() => fs.rmSync(dirty.root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(dirty.repo, "untracked.txt"), "dirty");
  assert.match(run(dirty).stderr, /worktree must be clean/);

  const mismatch = makeFixture();
  t.after(() => fs.rmSync(mismatch.root, { recursive: true, force: true }));
  assert.match(run(mismatch, { ...mismatch.env, MOCK_SIGNER_SHA: "b".repeat(64) }).stderr, /does not match/);

  const debug = makeFixture();
  t.after(() => fs.rmSync(debug.root, { recursive: true, force: true }));
  assert.match(run(debug, { ...debug.env, MOCK_SIGNER_SUBJECT: "CN=Android Debug,O=Android" }).stderr, /Android Debug certificate/);
});

test("release source must contain the required PR #2 merge commit", (t) => {
  const fixture = makeFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  const result = run(fixture, { ...fixture.env, MOCK_MISSING_PR2_ANCESTRY: "1" });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /required PR #2 merge commit dcffaad/);
  assert.doesNotMatch(result.stdout, /Building Demo and Pleos/);
});

test("release rejects identical artifacts and APKs from the wrong product flavor", (t) => {
  for (const [overrides, message] of [
    [{ MOCK_IDENTICAL_ARTIFACTS: "1" }, /must not be identical/],
    [{ MOCK_DEMO_PACKAGE: "com.socialdrive.app" }, /expected product flavor/],
    [{ MOCK_PLEOS_PACKAGE: "com.socialdrive.app.demo" }, /expected product flavor/],
  ]) {
    const fixture = makeFixture();
    t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
    const result = run(fixture, { ...fixture.env, ...overrides });
    assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stderr, message);
    const sourceSha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: fixture.repo, encoding: "utf8" }).trim();
    assert.equal(fs.existsSync(path.join(fixture.android, "build/submission", sourceSha)), false);
  }
});

test("credential-like APK entries and content are rejected", (t) => {
  for (const marker of ["MOCK_SENSITIVE_ENTRY", "MOCK_SENSITIVE_CONTENT"]) {
    const fixture = makeFixture();
    t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
    const result = run(fixture, { ...fixture.env, [marker]: "1" });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /APK contains/);
  }
});

test("successful dual release writes ordered metadata with final APK checksums", (t) => {
  const fixture = makeFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  const result = run(fixture);
  assert.equal(result.status, 0, result.stderr);
  const sha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: fixture.repo, encoding: "utf8" }).trim();
  const output = path.join(fixture.android, "build/submission", sha);
  const metadata = JSON.parse(fs.readFileSync(path.join(output, "metadata.json"), "utf8"));
  assert.equal(metadata.schemaVersion, 1);
  assert.deepEqual(metadata.artifacts.map(({ variant }) => variant), ["demoRelease", "pleosRelease"]);
  assert.deepEqual(metadata.artifacts.map(({ filename }) => filename), ["SocialDrive_demo_release.apk", "SocialDrive_pleos_release.apk"]);
  for (const artifact of metadata.artifacts) {
    assert.deepEqual(Object.keys(artifact), ["sourceSha", "variant", "filename", "sha256"]);
    assert.equal(artifact.sourceSha, sha);
    const independent = execFileSync("shasum", ["-a", "256", path.join(output, artifact.filename)], { encoding: "utf8" }).split(/\s+/)[0];
    assert.equal(artifact.sha256, independent);
  }
  const repeated = run(fixture);
  assert.notEqual(repeated.status, 0);
  assert.match(repeated.stderr, /submission output already exists/);
});

test("release finalization serializes concurrent pipelines", (t) => {
  const fixture = makeFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  fs.mkdirSync(path.join(fixture.android, ".gradle/submission-release.lock"), { recursive: true });
  const result = run(fixture);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /already running/);
  assert.equal(fs.existsSync(path.join(fixture.android, "build/submission")), false);
});

test("clean-install verifier encodes strict device, metadata, install, signer, and relaunch gates", () => {
  assert.match(installScript, /exactly one adb device may be connected/);
  assert.match(installScript, /demo\.sourceSha !== pleos\.sourceSha/);
  assert.match(installScript, /Demo APK checksum does not match metadata/);
  assert.match(installScript, /api_level" == "34/);
  assert.match(installScript, /build_version" == \*"2\.1\.0"\*/);
  assert.match(installScript, /package is already installed/);
  assert.match(installScript, /aapt2_bin dump badging/);
  assert.match(installScript, /installed package version differs/);
  assert.match(installScript, /aapt2_bin dump badging/);
  assert.match(installScript, /installed package version differs/);
  assert.match(installScript, /install "\$apk"/);
  assert.doesNotMatch(installScript, /install -r/);
  assert.match(installScript, /installed APK signer differs/);
  assert.match(installScript, /APK uses an Android Debug certificate/);
  assert.match(installScript, /force-stop/);
  assert.match(installScript, /second_pid.*first_pid/);
  assert.match(installScript, /assert_app_stable/);
  assert.match(installScript, /mResumedActivity/);
  assert.match(installScript, /activity exit-info/);
  assert.match(installScript, /recorded an app crash/);
  assert.match(installScript, /run_adb/);
  assert.match(installScript, /ln "\$evidence_temp" "\$evidence_file"/);
  assert.doesNotMatch(installScript, /logcat|screencap|screenrecord/);
  assert.match(buildScript, /inspect_apk_for_sensitive_material/);
});

test("clean-install rejects an offline second device and recorded launch crashes", (t) => {
  const devices = makeInstallFixture();
  t.after(() => fs.rmSync(devices.root, { recursive: true, force: true }));
  const extraDevice = devices.runInstall({ MOCK_ADB_DEVICES: "PLEOS-1\tdevice\nphone-2\toffline\n" });
  assert.notEqual(extraDevice.status, 0);
  assert.match(extraDevice.stderr, /exactly one adb device/);

  const crash = makeInstallFixture();
  t.after(() => fs.rmSync(crash.root, { recursive: true, force: true }));
  const crashResult = crash.runInstall({ MOCK_CRASH_EXIT: "1" });
  assert.notEqual(crashResult.status, 0, crashResult.stdout);
  assert.match(crashResult.stderr, /recorded an app crash/);
});

test("clean-install rejects the wrong AVD, an existing package, and PID reuse", (t) => {
  for (const [overrides, message] of [
    [{ MOCK_BUILD_VERSION: "PLEOS-2.0.0" }, /not version 2\.1\.0/],
    [{ MOCK_EXISTING_PACKAGE: "1" }, /already installed/],
    [{ MOCK_SAME_PID: "1" }, /new live process/],
  ]) {
    const fixture = makeInstallFixture();
    t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
    const result = fixture.runInstall(overrides);
    assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stderr, message);
    assert.equal(fs.existsSync(path.join(fixture.output, "pleos-clean-install-evidence.json")), false);
  }
});

test("clean-install validates the complete metadata set before touching a device", (t) => {
  for (const mutate of [
    (metadata) => { metadata.artifacts[0].sourceSha = "c".repeat(40); },
    (metadata) => { metadata.artifacts[0].unexpected = true; },
    (metadata) => { metadata.artifacts[0].sha256 = "0".repeat(64); },
  ]) {
    const fixture = makeInstallFixture();
    t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
    const metadataPath = path.join(fixture.output, "metadata.json");
    const metadata = JSON.parse(fs.readFileSync(metadataPath, "utf8"));
    mutate(metadata);
    fs.writeFileSync(metadataPath, `${JSON.stringify(metadata)}\n`);
    const result = fixture.runInstall();
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /metadata contract is invalid|Demo APK checksum does not match metadata/);
  }
});

test("clean-install records evidence only after two stable launches with a new PID", (t) => {
  const fixture = makeInstallFixture();
  t.after(() => fs.rmSync(fixture.root, { recursive: true, force: true }));
  const result = fixture.runInstall();
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  const evidence = JSON.parse(fs.readFileSync(path.join(fixture.output, "pleos-clean-install-evidence.json"), "utf8"));
  assert.equal(evidence.steps.firstLaunchStable, "passed");
  assert.equal(evidence.steps.relaunchWithNewStableProcess, "passed");
  assert.equal(evidence.steps.noCrashExitRecorded, "passed");
});

test("clean-install bounds adb commands and never overwrites existing evidence", (t) => {
  const hung = makeInstallFixture();
  t.after(() => fs.rmSync(hung.root, { recursive: true, force: true }));
  const timeout = hung.runInstall({ MOCK_ADB_HANG: "1", SOCIAL_DRIVE_ADB_TIMEOUT_SECONDS: "1" });
  assert.notEqual(timeout.status, 0);
  assert.match(timeout.stderr, /timed out/);
  assert.equal(fs.existsSync(path.join(hung.output, "pleos-clean-install-evidence.json")), false);

  const existing = makeInstallFixture();
  t.after(() => fs.rmSync(existing.root, { recursive: true, force: true }));
  const evidencePath = path.join(existing.output, "pleos-clean-install-evidence.json");
  fs.writeFileSync(evidencePath, "operator-owned evidence\n");
  const result = existing.runInstall();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /evidence already exists/);
  assert.equal(fs.readFileSync(evidencePath, "utf8"), "operator-owned evidence\n");
});
