#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
repository_root="$(cd "$android_root/.." && pwd -P)"
submission_root="$android_root/build/submission"
required_base_commit="dcffaad"

fail() {
  printf 'Release pipeline stopped: %s\n' "$1" >&2
  exit 1
}

status() {
  printf '[release] %s\n' "$1"
}

normalize_fingerprint() {
  printf '%s' "$1" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]'
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print tolower($1)}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print tolower($1)}'
  else
    fail 'sha256sum or shasum is required.'
  fi
}

inspect_apk_for_sensitive_material() {
  local apk_path="$1"
  command -v unzip >/dev/null 2>&1 || fail 'unzip is required for APK content inspection.'
  command -v strings >/dev/null 2>&1 || fail 'strings is required for APK content inspection.'
  if unzip -Z1 "$apk_path" | grep -Ei '(^|/)(\.env([^/]*|/)|[^/]+\.(jks|keystore|p12|pfx|pem))$' >/dev/null; then
    fail 'APK contains a forbidden credential or environment file.'
  fi
  if unzip -p "$apk_path" | strings | grep -Ei \
    'AKIA[0-9A-Z]{16}|aws_secret_access_key[[:space:]]*[=:][[:space:]]*[A-Za-z0-9/+=]{40}([^A-Za-z0-9/+=]|$)|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|https?://[^[:space:]]*(webhook|/hooks/)|https?://(localhost|127\.0\.0\.1|10\.0\.2\.2)([:/]|$)' >/dev/null; then
    fail 'APK contains a credential, webhook, private key, or debug endpoint marker.'
  fi
}

inspect_apk_manifest_policy() {
  local apk_path="$1"
  local manifest_report
  manifest_report="$($aapt2_bin dump xmltree "$apk_path" --file AndroidManifest.xml)" ||
    fail 'APK manifest inspection failed.'
  if printf '%s\n' "$manifest_report" | grep -Eq ':debuggable\([^)]*\)=true'; then
    fail 'release APK is debuggable.'
  fi
  printf '%s\n' "$manifest_report" | grep -Eq ':allowBackup\([^)]*\)=false' ||
    fail 'release APK must disable Android backup.'
  printf '%s\n' "$manifest_report" | grep -Eq ':usesCleartextTraffic\([^)]*\)=false' ||
    fail 'release APK must disable cleartext traffic.'
  if printf '%s\n' "$manifest_report" | grep -Eq 'PreviewActivity|androidx\.activity\.ComponentActivity|E: instrumentation'; then
    fail 'release APK contains a debug or test component.'
  fi
}

require_clean_source() {
  [[ -z "$(git -C "$repository_root" status --porcelain --untracked-files=all)" ]] ||
    fail 'the worktree must be clean, including untracked files.'
}

required_signing_variables=(
  SOCIAL_DRIVE_RELEASE_STORE_FILE
  SOCIAL_DRIVE_RELEASE_STORE_PASSWORD
  SOCIAL_DRIVE_RELEASE_KEY_ALIAS
  SOCIAL_DRIVE_RELEASE_KEY_PASSWORD
  SOCIAL_DRIVE_RELEASE_CERT_SHA256
)
for variable_name in "${required_signing_variables[@]}"; do
  [[ -n "${!variable_name:-}" ]] || fail "release signing environment is incomplete ($variable_name)."
done

command -v node >/dev/null 2>&1 || fail 'Node.js is required.'
keystore_path="$(node -e 'const fs = require("node:fs"); process.stdout.write(fs.realpathSync(process.argv[1]));' "$SOCIAL_DRIVE_RELEASE_STORE_FILE" 2>/dev/null)" ||
  fail 'release keystore path does not exist.'
[[ -f "$keystore_path" ]] || fail 'release keystore must be an existing regular file.'
case "$keystore_path" in
  "$repository_root"|"$repository_root"/*) fail 'release keystore must be outside the repository.' ;;
esac

approved_fingerprint="$(normalize_fingerprint "$SOCIAL_DRIVE_RELEASE_CERT_SHA256")"
[[ "$approved_fingerprint" =~ ^[0-9a-f]{64}$ ]] ||
  fail 'approved certificate SHA-256 must contain exactly 64 hexadecimal digits.'

command -v git >/dev/null 2>&1 || fail 'git is required.'
command -v node >/dev/null 2>&1 || fail 'Node.js is required to write metadata safely.'
source_sha="$(git -C "$repository_root" rev-parse --verify HEAD)"
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || fail 'HEAD is not a full Git commit SHA.'
git -C "$repository_root" merge-base --is-ancestor "$required_base_commit" HEAD 2>/dev/null ||
  fail "HEAD must contain the required PR #2 merge commit $required_base_commit."
require_clean_source

if command -v apksigner >/dev/null 2>&1; then
  apksigner_bin="$(command -v apksigner)"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  apksigner_bin="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -perm -u+x 2>/dev/null | sort | tail -1)"
else
  apksigner_bin=""
fi
[[ -n "$apksigner_bin" && -x "$apksigner_bin" ]] || fail 'Android SDK apksigner is required.'

if command -v aapt2 >/dev/null 2>&1; then
  aapt2_bin="$(command -v aapt2)"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  aapt2_bin="$(find "$ANDROID_HOME/build-tools" -type f -name aapt2 -perm -u+x 2>/dev/null | sort | tail -1)"
else
  aapt2_bin=""
fi
[[ -n "$aapt2_bin" && -x "$aapt2_bin" ]] || fail 'Android SDK aapt2 is required.'

final_dir="$submission_root/$source_sha"
[[ ! -e "$final_dir" ]] || fail "submission output already exists for source $source_sha."
mkdir -p "$android_root/.gradle"
release_lock="$android_root/.gradle/submission-release.lock"
mkdir "$release_lock" 2>/dev/null || fail 'another submission release pipeline is already running.'
stage_dir=""
cleanup() {
  if [[ -n "$stage_dir" && -d "$stage_dir" ]]; then rm -rf "$stage_dir"; fi
  rmdir "$release_lock" 2>/dev/null || true
}
trap cleanup EXIT

status 'Building Demo and Pleos release variants from a clean source.'
bash "$android_root/scripts/gradle-local.sh" --no-daemon clean assembleDemoRelease assemblePleosRelease
[[ "$(git -C "$repository_root" rev-parse --verify HEAD)" == "$source_sha" ]] ||
  fail 'HEAD changed during the release build.'
require_clean_source

mkdir -p "$submission_root"
[[ ! -e "$final_dir" ]] || fail "submission output appeared during the build for source $source_sha."
stage_dir="$(mktemp -d "$submission_root/.stage-${source_sha}.XXXXXX")"

declare -a variants=(demoRelease pleosRelease)
declare -a input_apks=(
  "$android_root/app/build/outputs/apk/demo/release/app-demo-release.apk"
  "$android_root/app/build/outputs/apk/pleos/release/app-pleos-release.apk"
)
declare -a output_names=(SocialDrive_demo_release.apk SocialDrive_pleos_release.apk)
declare -a expected_packages=(com.socialdrive.app.demo com.socialdrive.app)
declare -a artifact_hashes=()

for index in 0 1; do
  input_apk="${input_apks[$index]}"
  output_name="${output_names[$index]}"
  [[ -f "$input_apk" ]] || fail "expected ${variants[$index]} APK was not produced."
  inspect_apk_for_sensitive_material "$input_apk"

  package_report="$($aapt2_bin dump badging "$input_apk")" ||
    fail "${variants[$index]} APK package inspection failed."
  package_name="$(printf '%s\n' "$package_report" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
  [[ "$package_name" == "${expected_packages[$index]}" ]] ||
    fail "${variants[$index]} APK package does not match the expected product flavor."
  inspect_apk_manifest_policy "$input_apk"

  signer_report="$($apksigner_bin verify --verbose --print-certs "$input_apk")" ||
    fail "${variants[$index]} APK signature verification failed."
  signer_count="$(printf '%s\n' "$signer_report" | grep -Ec '^Signer #[0-9]+ certificate SHA-256 digest:')"
  [[ "$signer_count" == "1" ]] || fail "${variants[$index]} must have exactly one signer certificate."
  signer_fingerprint="$(printf '%s\n' "$signer_report" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
  signer_fingerprint="$(normalize_fingerprint "$signer_fingerprint")"
  [[ "$signer_fingerprint" == "$approved_fingerprint" ]] ||
    fail "${variants[$index]} signer does not match the approved certificate."
  signer_subject="$(printf '%s\n' "$signer_report" | sed -n 's/^Signer #1 certificate DN: //p')"
  [[ ! "$signer_subject" =~ [Aa]ndroid[[:space:]][Dd]ebug ]] ||
    fail "${variants[$index]} uses an Android Debug certificate."

  cp "$input_apk" "$stage_dir/$output_name"
  artifact_hashes+=("$(sha256_file "$stage_dir/$output_name")")
done

[[ "${artifact_hashes[0]}" != "${artifact_hashes[1]}" ]] ||
  fail 'Demo and Pleos release APKs must not be identical.'

node - "$stage_dir/metadata.json" "$source_sha" "${artifact_hashes[0]}" "${artifact_hashes[1]}" <<'NODE'
const fs = require("node:fs");
const [output, sourceSha, demoSha, pleosSha] = process.argv.slice(2);
const metadata = {
  schemaVersion: 1,
  artifacts: [
    { sourceSha, variant: "demoRelease", filename: "SocialDrive_demo_release.apk", sha256: demoSha },
    { sourceSha, variant: "pleosRelease", filename: "SocialDrive_pleos_release.apk", sha256: pleosSha },
  ],
};
fs.writeFileSync(output, `${JSON.stringify(metadata, null, 2)}\n`, { mode: 0o600 });
NODE

require_clean_source
[[ "$(git -C "$repository_root" rev-parse --verify HEAD)" == "$source_sha" ]] ||
  fail 'HEAD changed before metadata finalization.'
mv "$stage_dir" "$final_dir"
stage_dir=""
rmdir "$release_lock"
trap - EXIT
status "Verified release set created at android/build/submission/$source_sha."
