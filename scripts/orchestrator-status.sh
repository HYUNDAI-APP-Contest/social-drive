#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
plist="$HOME/Library/LaunchAgents/com.socialdrive.codex-orchestrator.plist"

launchctl print "gui/$(id -u)/com.socialdrive.codex-orchestrator" 2>/dev/null | sed -n '1,80p' || true
printf '\n-- latest log --\n'
tail -n 50 "$repo_root/orchestrator/logs/orchestrator.log" 2>/dev/null || true
printf '\n-- latest errors --\n'
tail -n 50 "$repo_root/orchestrator/logs/orchestrator-error.log" 2>/dev/null || true
printf '\n-- launch agent --\n%s\n' "$plist"

