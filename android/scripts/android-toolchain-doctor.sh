#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="${SOCIAL_DRIVE_RUNTIME_DIR:-$android_root/.gradle/local-runtime}"
failed=0

pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failed=1; }
info() { printf 'INFO  %s\n' "$1"; }

if [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
  java17_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
else
  java17_home="${SOCIAL_DRIVE_JAVA_HOME:-${JAVA_HOME:-}}"
fi
if [[ -x "$java17_home/bin/java" ]]; then
  version="$($java17_home/bin/java -version 2>&1 | head -1)"
  [[ "$version" == *'17.'* ]] && pass "JDK 17: $java17_home" || fail "JDK 17 required; found $version"
else
  fail "JDK 17 not found"
fi

if [[ -f "$android_root/gradle/wrapper/gradle-wrapper.jar" ]]; then
  pass "Gradle wrapper JAR present"
else
  info "Gradle wrapper JAR is absent; verified distribution bootstrap is required"
  gradle_bin="$runtime_root/gradle-user-home/wrapper-bootstrap/gradle-8.9-bin/gradle-8.9/bin/gradle"
  [[ -x "$gradle_bin" ]] && pass "Pinned Gradle 8.9 cached" || fail "Pinned Gradle 8.9 not cached at $gradle_bin"
fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk_root" ]] && [[ "$(uname -s)" == "Darwin" ]]; then
  system_user_home="${HOME:?}"
  [[ -d "$system_user_home/Library/Android/sdk" ]] && sdk_root="$system_user_home/Library/Android/sdk"
fi
if [[ -d "$sdk_root" ]]; then
  pass "Android SDK: $sdk_root"
  [[ -d "$sdk_root/platforms/android-35" ]] && pass "Android platform 35" || fail "Android platform 35 is missing"
else
  fail "Android SDK not found; set ANDROID_HOME"
fi

if [[ "$failed" -ne 0 ]]; then
  printf 'Toolchain is not ready. No package, credential, or SDK mutation was attempted.\n' >&2
  exit 1
fi
pass "Local Android toolchain is ready"
