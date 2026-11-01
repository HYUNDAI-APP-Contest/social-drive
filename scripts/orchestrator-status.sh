#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plist="$HOME/Library/LaunchAgents/com.socialdrive.codex-orchestrator.plist"
dashboard="$(cd "$repo_root" && node --env-file=.env --input-type=module -e 'import { loadConfig } from "./orchestrator/src/config.mjs"; process.stdout.write(loadConfig({ requireSecrets: false }).dashboardPath)')"
dashboard_interval_seconds="$(cd "$repo_root" && node --env-file=.env --input-type=module -e 'import { loadConfig } from "./orchestrator/src/config.mjs"; process.stdout.write(String(loadConfig({ requireSecrets: false }).dashboardIntervalMs / 1000))')"
previous_dashboard_inode="${ORCHESTRATOR_PREVIOUS_DASHBOARD_INODE:-}"
maximum_heartbeat_age=$((dashboard_interval_seconds * 3))
if [[ "$maximum_heartbeat_age" -lt 30 ]]; then maximum_heartbeat_age=30; fi

service="$(launchctl print "gui/$(id -u)/com.socialdrive.codex-orchestrator" 2>/dev/null)" || {
  printf '오케스트레이터 LaunchAgent가 로드되지 않았습니다.\n' >&2
  exit 1
}
if ! grep -q 'state = running' <<<"$service" || ! grep -Eq 'pid = [1-9][0-9]*' <<<"$service"; then
  printf '%s\n' "$service" | sed -n '1,80p'
  printf '오케스트레이터 프로세스가 running 상태가 아닙니다.\n' >&2
  exit 1
fi
pid="$(sed -n 's/^[[:space:]]*pid = \([1-9][0-9]*\).*/\1/p' <<<"$service" | head -1)"
sleep 2
service_after="$(launchctl print "gui/$(id -u)/com.socialdrive.codex-orchestrator" 2>/dev/null)" || {
  printf '오케스트레이터가 상태 확인 중 종료됐습니다.\n' >&2
  exit 1
}
pid_after="$(sed -n 's/^[[:space:]]*pid = \([1-9][0-9]*\).*/\1/p' <<<"$service_after" | head -1)"
if [[ -z "$pid" || "$pid" != "$pid_after" ]] || ! grep -q 'state = running' <<<"$service_after"; then
  printf '오케스트레이터가 안정적으로 실행되지 않고 재시작 중입니다.\n' >&2
  exit 1
fi
if ! grep -q 'social-drive-orchestrator-v1' "$dashboard" 2>/dev/null; then
  printf '새 오케스트레이터의 dashboard heartbeat를 찾지 못했습니다.\n' >&2
  exit 1
fi
dashboard_mtime="$(stat -f %m "$dashboard")"
dashboard_inode="$(stat -f %i "$dashboard")"
now="$(date +%s)"
if [[ -n "$previous_dashboard_inode" && "$dashboard_inode" == "$previous_dashboard_inode" ]]; then
  printf 'dashboard heartbeat가 재시작 후 교체되지 않았습니다.\n' >&2
  exit 1
fi
if [[ $((now - dashboard_mtime)) -gt "$maximum_heartbeat_age" ]]; then
  printf 'dashboard heartbeat가 새로 갱신되지 않았습니다.\n' >&2
  exit 1
fi
printf '%s\n' "$service" | sed -n '1,80p'
printf '\n-- latest log --\n'
tail -n 50 "$repo_root/orchestrator/logs/orchestrator.log" 2>/dev/null || true
printf '\n-- latest errors --\n'
tail -n 50 "$repo_root/orchestrator/logs/orchestrator-error.log" 2>/dev/null || true
printf '\n-- launch agent --\n%s\n' "$plist"

