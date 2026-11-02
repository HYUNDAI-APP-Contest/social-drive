#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
label="com.socialdrive.codex-orchestrator"
branch="agent/issue-development-control-center"
invite_url="https://discord.com/oauth2/authorize?client_id=1564993911510794361&permissions=67193856&integration_type=0&scope=bot%20applications.commands"
control_center_files=(
  ".env.example"
  ".gitignore"
  "orchestrator/README.md"
  "orchestrator/package.json"
  "orchestrator/src/config.mjs"
  "orchestrator/src/control-center.mjs"
  "orchestrator/src/dashboard.mjs"
  "orchestrator/src/discord.mjs"
  "orchestrator/src/follow-up.mjs"
  "orchestrator/src/github.mjs"
  "orchestrator/src/index.mjs"
  "orchestrator/src/outbox.mjs"
  "orchestrator/src/project-admin.mjs"
  "orchestrator/src/ready-queue.mjs"
  "orchestrator/src/store.mjs"
  "orchestrator/src/usage.mjs"
  "orchestrator/src/visual-report.mjs"
  "orchestrator/test/config.test.mjs"
  "orchestrator/test/activation-policy.test.mjs"
  "orchestrator/test/control-center.test.mjs"
  "orchestrator/test/dashboard.test.mjs"
  "orchestrator/test/discord-delivery.test.mjs"
  "orchestrator/test/follow-up.test.mjs"
  "orchestrator/test/github-control-center.test.mjs"
  "orchestrator/test/outbox.test.mjs"
  "orchestrator/test/project-admin.test.mjs"
  "orchestrator/test/ready-queue.test.mjs"
  "orchestrator/test/setup-control-center-env.test.mjs"
  "orchestrator/test/setup-discord-permissions.test.mjs"
  "orchestrator/test/usage.test.mjs"
  "orchestrator/test/visual-report.test.mjs"
  "scripts/apply-and-test.sh"
  "scripts/orchestrator-status.sh"
  "scripts/publish-reviewed-work.sh"
  "scripts/reviewed-pr-plan.mjs"
  "scripts/setup-control-center.mjs"
  "scripts/setup-orchestrator.sh"
  "scripts/verify-discord-channel.mjs"
  "개발-관제센터-시작.command"
)

cd "$repo_root"
test -f .env

printf '1/8 GitHub 로그인과 최소 권한을 확인합니다...\n'
if ! gh auth status -h github.com >/dev/null 2>&1; then
  gh auth login -h github.com -p https -w -s project
elif ! gh project list --owner HYUNDAI-APP-Contest --limit 1 >/dev/null 2>&1; then
  gh auth refresh -h github.com -s project
fi
gh auth setup-git -h github.com
runtime_github_token="$(gh auth token -h github.com)"
GITHUB_TOKEN="$runtime_github_token" node --input-type=module -e \
  'import { updateEnvFile } from "./scripts/setup-control-center.mjs"; await updateEnvFile(".env", { GITHUB_TOKEN: process.env.GITHUB_TOKEN });'
unset runtime_github_token

printf '2/8 관제센터 테스트를 실행합니다...\n'
npm --prefix orchestrator run check
npm --prefix infra test
git diff --check

printf '3/8 승인용 브랜치와 PR을 준비합니다...\n'
if ! git diff --cached --quiet; then
  printf '이미 stage된 파일이 있어 안전하게 중단합니다. 기존 stage를 먼저 검토하세요.\n' >&2
  exit 1
fi
while IFS= read -r -d '' changed; do
  allowed=false
  for file in "${control_center_files[@]}"; do
    if [[ "$changed" == "$file" ]]; then allowed=true; break; fi
  done
  if [[ "$allowed" != true ]]; then
    printf '허용 목록 밖의 tracked 변경이 있어 실행을 중단합니다: %s\n' "$changed" >&2
    exit 1
  fi
done < <(git diff --name-only -z)
current_branch="$(git branch --show-current)"
if [[ "$current_branch" == "main" ]]; then
  if git show-ref --verify --quiet "refs/heads/$branch"; then
    git switch "$branch"
  else
    git switch -c "$branch"
  fi
elif [[ "$current_branch" != "$branch" ]]; then
  if [[ "$current_branch" == "$branch"-* ]]; then
    branch="$current_branch"
  else
    printf '현재 브랜치 %s에는 적용하지 않습니다. main 또는 %s 계열에서 다시 실행하세요.\n' "$current_branch" "$branch" >&2
    exit 1
  fi
fi

for file in "${control_center_files[@]}"; do
  if [[ ! -e "$file" ]]; then
    printf '승인된 파일이 없습니다: %s\n' "$file" >&2
    exit 1
  fi
done
cleanup_staging() {
  git restore --staged -- "${control_center_files[@]}" >/dev/null 2>&1 || true
}
abort_staging() {
  cleanup_staging
  exit 1
}
trap abort_staging ERR INT TERM
git add -- "${control_center_files[@]}"

while IFS= read -r -d '' staged; do
  allowed=false
  for file in "${control_center_files[@]}"; do
    if [[ "$staged" == "$file" ]]; then allowed=true; break; fi
  done
  if [[ "$allowed" != true ]]; then
    printf '허용 목록 밖의 stage 파일을 감지했습니다: %s\n' "$staged" >&2
    abort_staging
  fi
done < <(git diff --cached --name-only -z)

if ! git diff --cached --quiet; then
  git commit -m "feat: add autonomous development control center"
fi
trap - ERR INT TERM
git fetch origin main
pr_url=""
approval_state="open"
if git diff --quiet HEAD origin/main -- "${control_center_files[@]}"; then
  approval_state="merged"
  pr_url="$(gh pr list --head "$branch" --state closed --limit 20 --json url,mergedAt --jq '[.[] | select(.mergedAt != null)][0].url // ""')"
else
  open_pr="$(gh pr list --head "$branch" --state open --limit 1 --json url --jq '.[0].url // ""')"
  closed_pr="$(gh pr list --head "$branch" --state closed --limit 1 --json url --jq '.[0].url // ""')"
  if [[ -z "$open_pr" && -n "$closed_pr" ]]; then
    branch="${branch}-$(date -u +%Y%m%d%H%M%S)"
    git switch -c "$branch"
  fi
  git push --set-upstream origin "$branch"
  pr_url="$(gh pr list --head "$branch" --state open --limit 1 --json url --jq '.[0].url // ""')"
fi

if [[ "$approval_state" == "open" && -z "$pr_url" ]]; then
  pr_url="$(gh pr create \
    --base main \
    --head "$branch" \
    --title "feat: add autonomous development control center" \
    --body $'아키 설계, 픽시 구현, 센티넬 독립 검토를 통과한 24시간 개발 관제센터입니다.\n\n- Discord 선제 보고와 `/agent-status`\n- 30/60/90% 토큰 사용량 경보\n- 비공개 GitHub Projects 업무판\n- 로컬 10초 자동 갱신 관제판\n- 내구성 있는 보고 outbox와 장애 복구\n\n병합은 Discord 승인 후에만 진행합니다.')"
fi
if [[ "$approval_state" == "open" ]]; then
  [[ -n "$pr_url" ]] || { printf '열린 승인 PR을 만들지 못했습니다.\n' >&2; exit 1; }
  [[ "$(gh pr view "$pr_url" --json state --jq .state)" == "OPEN" ]] || { printf '승인 PR이 OPEN 상태가 아닙니다.\n' >&2; exit 1; }
fi

printf '4/8 검토 완료 개발 브랜치를 승인 PR로 게시합니다...\n'
bash scripts/publish-reviewed-work.sh

printf '5/8 Discord 송신·이력 권한을 검증합니다...\n'
if ! node --env-file=.env scripts/verify-discord-channel.mjs; then
  printf '\nDiscord에서 한 번만 권한을 승인해야 합니다. 브라우저를 엽니다.\n'
  open "$invite_url"
  read -r -p '승인을 마쳤으면 Enter를 누르세요: '
  node --env-file=.env scripts/verify-discord-channel.mjs
fi

printf '6/8 비공개 GitHub 개발 관제판을 설정합니다...\n'
project_ready=true
if ! node --env-file=.env scripts/setup-control-center.mjs --apply --write-env; then
  project_ready=false
  printf '경고: GitHub 토큰에 organization Projects 권한이 없어 웹 관제판 설정을 건너뜁니다. Discord와 로컬 관제판은 계속 활성화합니다.\n' >&2
fi

printf '7/8 상시 프로세스를 새 관제센터 코드로 재시작합니다...\n'
if ! git diff --quiet || ! git diff --cached --quiet; then
  printf '커밋되지 않은 tracked 변경이 남아 있어 LaunchAgent를 재시작하지 않습니다.\n' >&2
  exit 1
fi
dashboard_file="$(node --env-file=.env --input-type=module -e 'import { loadConfig } from "./orchestrator/src/config.mjs"; process.stdout.write(loadConfig({ requireSecrets: false }).dashboardPath)')"
dashboard_before_inode="$(stat -f %i "$dashboard_file" 2>/dev/null || printf '0')"
launchctl kickstart -k "gui/$(id -u)/$label"
for _attempt in {1..20}; do
  dashboard_after_inode="$(stat -f %i "$dashboard_file" 2>/dev/null || printf '0')"
  if [[ "$dashboard_after_inode" != "$dashboard_before_inode" ]] && grep -q 'social-drive-orchestrator-v1' "$dashboard_file" 2>/dev/null; then
    break
  fi
  sleep 1
done

printf '8/8 실행 상태를 확인합니다...\n'
ORCHESTRATOR_PREVIOUS_DASHBOARD_INODE="$dashboard_before_inode" bash scripts/orchestrator-status.sh
node --env-file=.env scripts/send-discord-test.mjs

printf '\n완료했습니다.\n'
if [[ -n "$pr_url" ]]; then printf 'PR (%s): %s\n' "$approval_state" "$pr_url"; fi
printf 'Discord: /agent-status 로 실시간 상태를 볼 수 있습니다.\n'
printf 'Mac: status-dashboard.html 을 열면 10초마다 자동 갱신됩니다.\n'
if [[ "$project_ready" == false ]]; then
  printf '남은 항목: GitHub Projects 권한만 추가하면 모바일 웹 관제판도 연결됩니다.\n'
fi
