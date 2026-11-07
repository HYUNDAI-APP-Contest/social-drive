#!/bin/bash
set -euo pipefail

ANDROID_ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
GPU_MODE="${PLEOS_GPU_MODE:-host}"
APK="$ANDROID_ROOT/app/build/outputs/apk/pleos/debug/app-pleos-debug.apk"
LOG_DIR="$ANDROID_ROOT/build/runtime"
EMULATOR_LOG="$LOG_DIR/pleos-emulator.log"

mkdir -p "$LOG_DIR"

if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  echo "Android SDK를 찾지 못했습니다. ANDROID_HOME을 확인해 주세요."
  exit 1
fi

"$ANDROID_ROOT/scripts/gradle-local.sh" :app:assemblePleosDebug

serial=$("$ADB" devices | awk 'NR > 1 && $1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')
emulator_pid=""
if [[ -n "$serial" ]] && [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
  echo "완료되지 않은 PLEOS 에뮬레이터를 정리하고 다시 시작합니다."
  "$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true
  serial=""
  sleep 3
fi
if [[ -z "$serial" ]]; then
  nohup "$EMULATOR" -avd Pleos_Connect_v2.1.0 -no-snapshot-load -no-metrics \
    -crash-report-mode disabled -gpu "$GPU_MODE" \
    >"$EMULATOR_LOG" 2>&1 &
  emulator_pid=$!
fi

for _ in $(seq 1 90); do
  serial=$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')
  if [[ -n "$serial" ]] && [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  sleep 2
done

if [[ -z "$serial" ]]; then
  echo "PLEOS 에뮬레이터 부팅을 확인하지 못했습니다."
  exit 1
fi

"$ADB" -s "$serial" install -r -t "$APK"
user_id=$("$ADB" -s "$serial" shell am get-current-user | tr -d '\r')
"$ADB" -s "$serial" shell am start --user "$user_id" --display 0 --windowingMode 1 \
  -n com.socialdrive.app/com.socialdrive.app.MainActivity

echo "PLEOS에서 Social Drive를 실행했습니다."
echo "에뮬레이터 로그: $EMULATOR_LOG"

if [[ -n "$emulator_pid" ]]; then
  echo "PLEOS 화면을 유지하려면 이 창을 열어 두세요. 에뮬레이터를 닫으면 창도 종료됩니다."
  wait "$emulator_pid"
fi
