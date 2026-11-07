#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
repository_root="$(cd "$android_root/.." && pwd -P)"
serial=""

fail() { printf 'PLEOS Navi runtime verification stopped: %s\n' "$1" >&2; exit 1; }
status() { printf '[pleos-navi-runtime] %s\n' "$1"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || fail '--serial requires a value.'; serial="$2"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" ]] || fail 'usage: --serial <PLEOS_API34_SERIAL>.'
command -v node >/dev/null 2>&1 || fail 'Node.js is required.'
command -v git >/dev/null 2>&1 || fail 'Git is required.'

if command -v adb >/dev/null 2>&1; then
  adb_bin="$(command -v adb)"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  adb_bin="$ANDROID_HOME/platform-tools/adb"
elif [[ -x "${HOME:?}/Library/Android/sdk/platform-tools/adb" ]]; then
  adb_bin="$HOME/Library/Android/sdk/platform-tools/adb"
else
  fail 'adb is required.'
fi
export ANDROID_HOME="${ANDROID_HOME:-$(cd "$(dirname "$adb_bin")/.." && pwd -P)}"
export PATH="$(dirname "$adb_bin"):$PATH"

[[ "$($adb_bin -s "$serial" get-state 2>/dev/null)" == "device" ]] || fail 'the selected device is unavailable.'
api_level="$($adb_bin -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r\n')"
[[ "$api_level" == "34" ]] || fail 'PLEOS Navi evidence requires API 34.'
avd_name="$($adb_bin -s "$serial" emu avd name 2>/dev/null | sed -n '1p' | tr -d '\r')"
[[ "$avd_name" == "Pleos_Connect_v2.1.0" ]] || fail 'the selected device is not Pleos_Connect_v2.1.0.'

[[ -z "$(git -C "$repository_root" status --porcelain --untracked-files=normal)" ]] ||
  fail 'the source tree must be clean so evidence is bound to the exact tested SHA.'
source_sha="$(git -C "$repository_root" rev-parse HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'source SHA is invalid.'
assert_source_unchanged() {
  [[ "$(git -C "$repository_root" rev-parse HEAD)" == "$source_sha" ]] ||
    fail 'HEAD changed during verification; evidence was not published.'
  [[ -z "$(git -C "$repository_root" status --porcelain --untracked-files=normal)" ]] ||
    fail 'the source tree changed during verification; evidence was not published.'
}
temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/social-drive-pleos-navi.XXXXXX")"
cleanup() {
  [[ -z "${temporary_root:-}" || ! -e "$temporary_root" ]] || rm -rf "$temporary_root"
}
trap cleanup EXIT
isolated_build="$temporary_root/app-build"
export SOCIAL_DRIVE_ANDROID_USER_HOME="$temporary_root/android-user-home"
export SOCIAL_DRIVE_TMPDIR="$temporary_root/tmp"
mkdir -p "$isolated_build" "$SOCIAL_DRIVE_ANDROID_USER_HOME" "$SOCIAL_DRIVE_TMPDIR"

# Older direct connected runs may have left full-device UTP logs in ignored build output.
# They are generated diagnostics, never durable review evidence.
for stale in \
  "$android_root/app/build/outputs/androidTest-results/connected" \
  "$android_root/app/build/reports/androidTests/connected"; do
  case "$stale" in "$android_root/app/build/"*) ;; *) fail 'refusing to remove an unsafe stale path.' ;; esac
  [[ ! -e "$stale" ]] || rm -rf "$stale"
done

status 'Running the exact connected smoke in an isolated, disposable result directory.'
ANDROID_SERIAL="$serial" bash "$android_root/scripts/gradle-local.sh" --offline --no-daemon \
  -PprivacySafeBuildDir="$isolated_build" \
  :app:connectedPleosDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.socialdrive.app.PleosNaviRuntimeSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.requirePleosNavi=true
assert_source_unchanged

evidence_directory="$android_root/build/pleos-runtime-evidence"
mkdir -p "$evidence_directory"
evidence_temp="$(mktemp "$evidence_directory/.${source_sha}.XXXXXX")"
trap 'cleanup; rm -f "$evidence_temp"' EXIT

node - "$isolated_build" "$evidence_temp" "$source_sha" "$avd_name" "$api_level" <<'NODE'
const fs = require("node:fs");
const path = require("node:path");

const [buildRoot, output, sourceSha, device, apiLevel] = process.argv.slice(2);
const walk = (directory) => fs.existsSync(directory)
  ? fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
      const target = path.join(directory, entry.name);
      return entry.isDirectory() ? walk(target) : [target];
    })
  : [];
const files = walk(buildRoot);
const xmlFiles = files.filter((file) => path.basename(file).startsWith("TEST-") && file.endsWith(".xml"));
const exactClass = "com.socialdrive.app.PleosNaviRuntimeSmokeTest";
const exactMethod = "productionGatewayObservesCompletedVendorRouteAndCleanup";
const xmlFile = xmlFiles.find((file) => {
  const value = fs.readFileSync(file, "utf8");
  return value.includes(exactClass) && value.includes(exactMethod);
});
if (!xmlFile) throw new Error("exact PLEOS Navi JUnit result is missing");
const xml = fs.readFileSync(xmlFile, "utf8");
const suite = xml.match(/<testsuite\b[^>]*>/)?.[0] ?? "";
const count = (name) => Number(suite.match(new RegExp(`${name}="(\\d+)"`))?.[1] ?? -1);
const results = {
  tests: count("tests"), failures: count("failures"), errors: count("errors"), skipped: count("skipped"),
};
if (JSON.stringify(results) !== JSON.stringify({ tests: 1, failures: 0, errors: 0, skipped: 0 })) {
  throw new Error("exact PLEOS Navi JUnit result did not pass 1/0/0/0");
}

const logFiles = files.filter((file) => path.basename(file).startsWith("logcat-") && file.endsWith(".txt"));
if (logFiles.length === 0) throw new Error("temporary UTP logcat is missing");
const logcat = logFiles.map((file) => fs.readFileSync(file, "utf8")).join("\n");
const observed = [...logcat.matchAll(/PleosNaviSmoke:\s+stage=(start|initialized|requested|terminal_completed|released)\b/g)]
  .map((match) => match[1]);
const stages = observed.filter((stage, index) => index === 0 || stage !== observed[index - 1]);
const requiredStages = ["start", "initialized", "requested", "terminal_completed", "released"];
let cursor = -1;
for (const required of requiredStages) {
  cursor = stages.indexOf(required, cursor + 1);
  if (cursor < 0) throw new Error(`privacy-safe stage is missing: ${required}`);
}
const completionCallback = logcat.includes("onRouteEnded")
  ? "legacy_onRouteEnded"
  : logcat.includes("onRoutingCompleted") ? "onRoutingCompleted" : null;
if (!completionCallback) throw new Error("vendor completion callback was not observed");

const evidence = {
  schemaVersion: 1,
  sourceSha,
  device,
  apiLevel: Number(apiLevel),
  results,
  stages: requiredStages,
  completionCallback,
  assertions: { listenerRemoved: true, released: true, rawVendorLogRetained: false },
};
fs.writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
NODE

cleanup
[[ ! -e "$temporary_root" ]] || fail 'disposable UTP output could not be destroyed; evidence was not published.'

for forbidden in 'latitude=' 'longitude=' '"lat"' '"lon"' 'RouteInfo(' 'GeoPoint(' 'poiName' '공개 검증 목적지'; do
  if grep -Fq "$forbidden" "$evidence_temp"; then
    fail 'allowlisted evidence unexpectedly contains route details.'
  fi
done

evidence_file="$evidence_directory/${source_sha}.json"
assert_source_unchanged
if [[ -e "$evidence_file" ]]; then
  cmp -s "$evidence_temp" "$evidence_file" || fail 'existing evidence differs for the same source SHA.'
  rm -f "$evidence_temp"
else
  mv "$evidence_temp" "$evidence_file"
fi
trap cleanup EXIT
status "Privacy-safe evidence retained at $evidence_file; raw UTP output was destroyed."
