#!/usr/bin/env bash
set -euo pipefail

plist="$HOME/Library/LaunchAgents/com.socialdrive.codex-orchestrator.plist"
launchctl bootout "gui/$(id -u)" "$plist" >/dev/null 2>&1 || true
if [[ -f "$plist" ]]; then
  mv "$plist" "$HOME/.Trash/com.socialdrive.codex-orchestrator.plist.$(date +%s)"
fi
printf 'Orchestrator stopped. Project data and .env were preserved.\n'
