#!/usr/bin/env bash
set -euo pipefail

android_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v node >/dev/null 2>&1 || {
  printf 'Node.js is required for the local Android-backend contract harness.\n' >&2
  exit 2
}

cd "$android_root"
(
  bash scripts/gradle-local.sh \
    :app:testDemoDebugUnitTest \
    --rerun-tasks \
    --tests 'com.socialdrive.app.data.remote.AndroidBackendContractHarnessTest'
  bash scripts/gradle-local.sh \
    :app:testPleosDebugUnitTest \
    --rerun-tasks \
    --tests 'com.socialdrive.app.data.remote.AndroidBackendContractHarnessTest'
) &
harness_pid=$!

(
  sleep 120
  if kill -0 "$harness_pid" 2>/dev/null; then
    printf 'Contract harness exceeded the 120-second limit.\n' >&2
    kill -TERM "$harness_pid" 2>/dev/null || true
  fi
) &
watchdog_pid=$!

if wait "$harness_pid"; then
  harness_status=0
else
  harness_status=$?
fi
kill "$watchdog_pid" 2>/dev/null || true
wait "$watchdog_pid" 2>/dev/null || true
exit "$harness_status"
