#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_root="${SOCIAL_DRIVE_RUNTIME_DIR:-$android_root/.gradle/local-runtime}"

if [[ -n "${SOCIAL_DRIVE_JAVA_HOME:-}" ]]; then
  selected_java_home="$SOCIAL_DRIVE_JAVA_HOME"
elif [[ "$(uname -s)" == "Darwin" ]] && [[ -x /usr/libexec/java_home ]]; then
  selected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null)" || {
    printf 'JDK 17 was not found. Install it or set SOCIAL_DRIVE_JAVA_HOME.\n' >&2
    exit 2
  }
else
  selected_java_home="${JAVA_HOME:-}"
fi

[[ -x "$selected_java_home/bin/java" ]] || {
  printf 'A valid JDK 17 is required; set SOCIAL_DRIVE_JAVA_HOME.\n' >&2
  exit 2
}
java_version="$($selected_java_home/bin/java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.version = //p' | head -1)"
case "$java_version" in
  17|17.*) ;;
  *) printf 'Expected JDK 17, found %s at %s.\n' "$java_version" "$selected_java_home" >&2; exit 2 ;;
esac

mkdir -p "$runtime_root/gradle-user-home" "$runtime_root/tmp" "$runtime_root/android-user-home"
export JAVA_HOME="$selected_java_home"
export GRADLE_USER_HOME="$runtime_root/gradle-user-home"
export ANDROID_USER_HOME="$runtime_root/android-user-home"
export TMPDIR="$runtime_root/tmp"
export GRADLE_OPTS="${GRADLE_OPTS:+$GRADLE_OPTS }-Dorg.gradle.project.kotlin.compiler.execution.strategy=in-process"

if [[ -z "${ANDROID_HOME:-}" ]] && [[ "$(uname -s)" == "Darwin" ]]; then
  system_user_home="${HOME:?}"
  if [[ -d "$system_user_home/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$system_user_home/Library/Android/sdk"
  fi
fi

cd "$android_root"
exec "$android_root/gradlew" \
  -p "$android_root" \
  -Pkotlin.compiler.execution.strategy=in-process \
  "$@"
