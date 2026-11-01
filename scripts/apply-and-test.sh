#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
label="com.socialdrive.codex-orchestrator"

cd "$repo_root"
test -f .env

git add -- .gitignore .env.example .github compose.yaml design infra orchestrator scripts
if ! git diff --cached --quiet; then
  git commit -m "feat: update autonomous development orchestrator"
  git push origin main
fi

launchctl kickstart -k "gui/$(id -u)/$label"
sleep 3
node --env-file=.env scripts/send-discord-test.mjs

printf '\nApplied and running. In Discord run:\n'
printf '  /agent-claim code:<the code from your previous setup>\n'
printf '  /agent-status\n'
printf '  /agent-test-approval\n'
printf '  /agent-deploy\n'
