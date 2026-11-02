#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
serial=""
apk=""
adb_timeout_seconds="${SOCIAL_DRIVE_ADB_TIMEOUT_SECONDS:-20}"

fail() { printf 'Clean-install verification stopped: %s\n' "$1" >&2; exit 1; }
status() { printf '[clean-install] %s\n' "$1"; }
normalize_fingerprint() { printf '%s' "$1" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'; }
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print tolower($1)}'
  else fail 'sha256sum or shasum is required.'; fi
}

run_adb() {
  LC_ALL=C LANG=C perl -e '
    my $timeout = shift @ARGV;
    my $pid = fork();
    die "could not fork adb\n" unless defined $pid;
    if ($pid == 0) {
      setpgrp(0, 0);
      exec @ARGV or die "could not execute adb\n";
    }
    setpgrp($pid, $pid);
    my $timed_out = 0;
    $SIG{ALRM} = sub {
      $timed_out = 1;
      kill "TERM", -$pid;
      select undef, undef, undef, 0.2;
      kill "KILL", -$pid;
    };
    alarm $timeout;
    waitpid($pid, 0);
    alarm 0;
    exit 124 if $timed_out;
    exit(($? & 127) ? 128 + ($? & 127) : $? >> 8);
  ' "$adb_timeout_seconds" adb "$@"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial) [[ $# -ge 2 ]] || fail '--serial requires a value.'; serial="$2"; shift 2 ;;
    --apk) [[ $# -ge 2 ]] || fail '--apk requires a value.'; apk="$2"; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$serial" && -n "$apk" ]] || fail 'usage: --serial <PLEOS_SERIAL> --apk <Pleos release APK>.'
[[ -f "$apk" ]] || fail 'APK does not exist.'
[[ "$(basename "$apk")" == "SocialDrive_pleos_release.apk" ]] || fail 'only the Pleos release artifact is accepted.'
metadata="$(dirname "$apk")/metadata.json"
[[ -f "$metadata" ]] || fail 'metadata.json must be beside the APK.'
command -v adb >/dev/null 2>&1 || fail 'adb is required.'
command -v apksigner >/dev/null 2>&1 || fail 'apksigner is required.'
command -v node >/dev/null 2>&1 || fail 'Node.js is required.'
command -v perl >/dev/null 2>&1 || fail 'Perl is required for bounded adb commands.'
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || fail 'adb timeout must be a positive number of seconds.'
if command -v aapt2 >/dev/null 2>&1; then
  aapt2_bin="$(command -v aapt2)"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  aapt2_bin="$(find "$ANDROID_HOME/build-tools" -type f -name aapt2 -perm -u+x 2>/dev/null | sort | tail -1)"
else
  aapt2_bin=""
fi
[[ -n "$aapt2_bin" && -x "$aapt2_bin" ]] || fail 'Android SDK aapt2 is required.'

metadata_values="$(node - "$metadata" <<'NODE'
const fs = require("node:fs");
const metadata = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const exactKeys = (value, expected) =>
  value && typeof value === "object" && !Array.isArray(value) &&
  Object.keys(value).sort().join("\0") === [...expected].sort().join("\0");
if (!exactKeys(metadata, ["schemaVersion", "artifacts"]) || metadata.schemaVersion !== 1 ||
    !Array.isArray(metadata.artifacts) || metadata.artifacts.length !== 2) process.exit(2);
const [demo, pleos] = metadata.artifacts;
for (const artifact of [demo, pleos]) {
  if (!exactKeys(artifact, ["sourceSha", "variant", "filename", "sha256"]) ||
      !/^[0-9a-f]{40}$/.test(artifact.sourceSha) || !/^[0-9a-f]{64}$/.test(artifact.sha256)) process.exit(2);
}
if (demo.variant !== "demoRelease" || demo.filename !== "SocialDrive_demo_release.apk" ||
    pleos.variant !== "pleosRelease" || pleos.filename !== "SocialDrive_pleos_release.apk" ||
    demo.sourceSha !== pleos.sourceSha) process.exit(2);
process.stdout.write(`${pleos.sourceSha}\n${demo.filename}\n${demo.sha256}\n${pleos.sha256}\n`);
NODE
)" || fail 'metadata contract is invalid.'
source_sha="$(printf '%s\n' "$metadata_values" | sed -n '1p')"
[[ "$(basename "$(dirname "$apk")")" == "$source_sha" ]] || fail 'artifact directory does not match metadata sourceSha.'
evidence_file="$(dirname "$apk")/pleos-clean-install-evidence.json"
[[ ! -e "$evidence_file" ]] || fail 'clean-install evidence already exists; it will not be overwritten.'
demo_filename="$(printf '%s\n' "$metadata_values" | sed -n '2p')"
expected_demo_sha="$(printf '%s\n' "$metadata_values" | sed -n '3p')"
expected_apk_sha="$(printf '%s\n' "$metadata_values" | sed -n '4p')"
demo_apk="$(dirname "$apk")/$demo_filename"
[[ -f "$demo_apk" ]] || fail 'Demo artifact declared by metadata is missing.'
[[ "$(sha256_file "$demo_apk")" == "$expected_demo_sha" ]] || fail 'Demo APK checksum does not match metadata.'
[[ "$(sha256_file "$apk")" == "$expected_apk_sha" ]] || fail 'APK checksum does not match metadata.'

signer_report="$(apksigner verify --verbose --print-certs "$apk")" || fail 'APK signature verification failed.'
signer_count="$(printf '%s\n' "$signer_report" | grep -Ec '^Signer #[0-9]+ certificate SHA-256 digest:')"
[[ "$signer_count" == "1" ]] || fail 'APK must have exactly one signer certificate.'
signer_subject="$(printf '%s\n' "$signer_report" | sed -n 's/^Signer #1 certificate DN: //p')"
[[ ! "$signer_subject" =~ [Aa]ndroid[[:space:]][Dd]ebug ]] || fail 'APK uses an Android Debug certificate.'
approved_fingerprint="$(normalize_fingerprint "${SOCIAL_DRIVE_RELEASE_CERT_SHA256:-}")"
[[ "$approved_fingerprint" =~ ^[0-9a-f]{64}$ ]] || fail 'approved certificate SHA-256 environment is required.'
apk_fingerprint="$(printf '%s\n' "$signer_report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
apk_fingerprint="$(normalize_fingerprint "$apk_fingerprint")"
[[ "$apk_fingerprint" == "$approved_fingerprint" ]] || fail 'APK signer does not match the approved certificate.'

device_lines="$(run_adb devices | awk 'NR > 1 && NF {print $1 "\t" $2}')" ||
  fail 'bounded adb device discovery failed or timed out.'
device_count="$(printf '%s\n' "$device_lines" | sed '/^$/d' | wc -l | tr -d ' ')"
[[ "$device_count" == "1" ]] || fail 'exactly one adb device may be connected, including offline or unauthorized devices.'
connected_serial="$(printf '%s\n' "$device_lines" | awk 'NR == 1 {print $1}')"
connected_state="$(printf '%s\n' "$device_lines" | awk 'NR == 1 {print $2}')"
[[ "$connected_state" == "device" ]] || fail 'the connected adb device is not ready.'
[[ "$connected_serial" == "$serial" ]] || fail 'the only connected device does not match --serial.'
adb_target=(-s "$serial")

boot_completed="$(run_adb "${adb_target[@]}" shell getprop sys.boot_completed | tr -d '\r')" || fail 'boot-state query failed or timed out.'
api_level="$(run_adb "${adb_target[@]}" shell getprop ro.build.version.sdk | tr -d '\r')" || fail 'API-level query failed or timed out.'
product="$(run_adb "${adb_target[@]}" shell getprop ro.product.name | tr -d '\r\n')" || fail 'product query failed or timed out.'
build_version="$(run_adb "${adb_target[@]}" shell getprop ro.build.display.id | tr -d '\r\n')" || fail 'build-version query failed or timed out.'
user_id="$(run_adb "${adb_target[@]}" shell am get-current-user | tr -d '\r\n')" || fail 'Android user query failed or timed out.'
[[ "$boot_completed" == "1" ]] || fail 'AVD boot is incomplete.'
[[ "$api_level" == "34" ]] || fail 'PLEOS evidence requires API 34.'
[[ "$product" =~ [Pp][Ll][Ee][Oo][Ss] ]] || fail 'connected product is not identified as PLEOS.'
[[ "$build_version" == *"2.1.0"* ]] || fail 'connected PLEOS image is not version 2.1.0.'
[[ "$user_id" =~ ^[0-9]+$ ]] || fail 'current Android user ID is invalid.'

package_name="com.socialdrive.app"
apk_badging="$($aapt2_bin dump badging "$apk")" || fail 'APK package metadata could not be read.'
apk_package="$(printf '%s\n' "$apk_badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
apk_version_code="$(printf '%s\n' "$apk_badging" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
apk_version_name="$(printf '%s\n' "$apk_badging" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)"
[[ "$apk_package" == "$package_name" && -n "$apk_version_code" && -n "$apk_version_name" ]] ||
  fail 'APK package or version metadata is invalid.'
existing_package_path="$(run_adb "${adb_target[@]}" shell pm path "$package_name" 2>/dev/null)" ||
  fail 'existing-package query failed or timed out.'
if [[ -n "$existing_package_path" ]]; then
  fail 'package is already installed; wipe/factory-reset the AVD before verification.'
fi
status 'Installing the Pleos APK as a new package.'
run_adb "${adb_target[@]}" install "$apk" >/dev/null || fail 'fresh adb install failed or timed out.'
package_dump="$(run_adb "${adb_target[@]}" shell dumpsys package "$package_name")" || fail 'installed package query failed or timed out.'
version_name="$(printf '%s\n' "$package_dump" | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r')"
version_code="$(printf '%s\n' "$package_dump" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1 | tr -d '\r')"
[[ "$version_name" == "$apk_version_name" && "$version_code" == "$apk_version_code" ]] ||
  fail 'installed package version differs from the release artifact.'

temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/social-drive-installed.XXXXXX")"
cleanup() { rm -rf "$temp_dir"; }
trap cleanup EXIT
installed_path="$(run_adb "${adb_target[@]}" shell pm path "$package_name" | sed -n 's/^package://p' | head -1 | tr -d '\r')" || fail 'installed package path query failed or timed out.'
[[ -n "$installed_path" ]] || fail 'installed APK path was not reported.'
run_adb "${adb_target[@]}" pull "$installed_path" "$temp_dir/installed.apk" >/dev/null || fail 'installed APK could not be read for signer verification.'
installed_report="$(apksigner verify --verbose --print-certs "$temp_dir/installed.apk")" || fail 'installed APK signature verification failed.'
installed_fingerprint="$(printf '%s\n' "$installed_report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
installed_fingerprint="$(normalize_fingerprint "$installed_fingerprint")"
[[ "$installed_fingerprint" == "$apk_fingerprint" ]] || fail 'installed APK signer differs from the release artifact.'

assert_app_stable() {
  local expected_pid="$1"
  local phase="$2"
  sleep 3
  local stable_pid activity_state exit_info
  stable_pid="$(run_adb "${adb_target[@]}" shell pidof -s "$package_name" | tr -d '\r\n')" || fail "$phase process query failed or timed out."
  [[ "$stable_pid" == "$expected_pid" ]] || fail "$phase process did not remain stable."
  activity_state="$(run_adb "${adb_target[@]}" shell dumpsys activity activities "$package_name")" || fail "$phase Activity query failed or timed out."
  printf '%s\n' "$activity_state" | grep -Eq \
    'mResumedActivity.*com\.socialdrive\.app/(com\.socialdrive\.app\.)?\.?MainActivity' ||
    fail "$phase did not leave MainActivity resumed."
  exit_info="$(run_adb "${adb_target[@]}" shell dumpsys activity exit-info "$package_name")" || fail "$phase exit-info query failed or timed out."
  if printf '%s\n' "$exit_info" | grep -Eq 'reason=(4 \(CRASH|5 \(CRASH_NATIVE|CRASH|CRASH_NATIVE|REASON_CRASH)'; then
    fail "$phase recorded an app crash."
  fi
}

launch_main_activity() {
  local phase="$1"
  local launch_result
  launch_result="$(run_adb "${adb_target[@]}" shell am start -W -n "$package_name/.MainActivity")" || fail "$phase failed or timed out."
  printf '%s\n' "$launch_result" | grep -Eq '^Status:[[:space:]]+ok[[:space:]]*$' || fail "$phase did not report a successful Activity start."
}

status 'Launching MainActivity, checking stability, force-stopping, and relaunching with a new stable process.'
launch_main_activity 'first launch'
first_pid="$(run_adb "${adb_target[@]}" shell pidof -s "$package_name" | tr -d '\r\n')" || fail 'first-launch process query failed or timed out.'
[[ "$first_pid" =~ ^[0-9]+$ ]] || fail 'first launch did not create an app process.'
assert_app_stable "$first_pid" 'first launch'
run_adb "${adb_target[@]}" shell am force-stop "$package_name" || fail 'force-stop failed or timed out.'
post_stop_pid="$(run_adb "${adb_target[@]}" shell pidof -s "$package_name" | tr -d '\r\n')" || fail 'post-stop process query failed or timed out.'
[[ -z "$post_stop_pid" ]] || fail 'force-stop did not terminate the app process.'
launch_main_activity 'relaunch'
second_pid="$(run_adb "${adb_target[@]}" shell pidof -s "$package_name" | tr -d '\r\n')" || fail 'relaunch process query failed or timed out.'
[[ "$second_pid" =~ ^[0-9]+$ && "$second_pid" != "$first_pid" ]] || fail 'relaunch did not create a new live process.'
assert_app_stable "$second_pid" 'relaunch'

evidence_temp="$(mktemp "$(dirname "$apk")/.pleos-clean-install-evidence.XXXXXX")"
trap 'rm -rf "$temp_dir"; rm -f "$evidence_temp"' EXIT
node - "$evidence_temp" "$source_sha" "$(basename "$apk")" "$expected_apk_sha" "$apk_fingerprint" "$product" "$build_version" "$api_level" "$user_id" <<'NODE'
const fs = require("node:fs");
const [output, sourceSha, filename, sha256, certificateSha256, product, version, apiLevel, userId] = process.argv.slice(2);
const evidence = {
  schemaVersion: 1,
  sourceSha,
  artifact: { filename, sha256, certificateSha256 },
  avd: { product, version, apiLevel: Number(apiLevel), userId: Number(userId) },
  steps: {
    bootComplete: "passed", packageInitiallyAbsent: "passed", freshInstall: "passed",
    packageAndSignerMatch: "passed", firstLaunchStable: "passed", forceStop: "passed",
    relaunchWithNewStableProcess: "passed", noCrashExitRecorded: "passed",
  },
};
fs.writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`, { mode: 0o600 });
NODE
ln "$evidence_temp" "$evidence_file" 2>/dev/null || fail 'clean-install evidence appeared concurrently; it was not overwritten.'
rm -f "$evidence_temp"
trap cleanup EXIT
status "Evidence written to $(basename "$evidence_file")."
status 'This proves clean install and process relaunch only; SDK callbacks, physical Back, and cloud E2E remain separate gates.'
