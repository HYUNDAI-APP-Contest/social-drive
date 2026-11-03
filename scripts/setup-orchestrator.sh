#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
env_file="$repo_root/.env"
logs_dir="$repo_root/orchestrator/logs"
launch_agents_dir="$HOME/Library/LaunchAgents"
plist="$launch_agents_dir/com.socialdrive.codex-orchestrator.plist"
node_bin="$(command -v node)"
if ! codex_bin="$(command -v codex)"; then
  printf 'Codex CLI 실행 파일을 찾을 수 없습니다. Codex를 설치한 뒤 다시 실행하세요.\n' >&2
  exit 1
fi
if [[ "$codex_bin" != /* || ! -x "$codex_bin" ]]; then
  printf 'Codex CLI 경로가 안전한 절대 실행 파일이 아닙니다: %s\n' "$codex_bin" >&2
  exit 1
fi
codex_env_value="$("$node_bin" -e 'process.stdout.write(JSON.stringify(process.argv[1]))' "$codex_bin")"

cd "$repo_root"
gh auth status >/dev/null
github_token="$(gh auth token)"
gh repo view HYUNDAI-APP-Contest/social-drive >/dev/null

while true; do
  printf 'Discord Webhook URL만 붙여넣으세요 (https://discord.com/api/webhooks/...): '
  IFS= read -r webhook_url
  if [[ "$webhook_url" =~ ^https://(canary\.|ptb\.)?discord\.com/api/webhooks/[0-9]+/.+ ]]; then
    break
  fi
  printf '올바른 Discord Webhook URL 형식이 아닙니다. 실행 명령이 아니라 URL만 입력하세요.\n' >&2
done

while true; do
  printf 'Discord Developer Portal의 Bot Token을 붙여넣으세요 (input hidden): '
  IFS= read -rs bot_token
  printf '\n'
  if [[ -n "$bot_token" ]]; then break; fi
  printf 'Bot Token은 비어 있을 수 없습니다.\n' >&2
done

webhook_meta="$(curl -fsS "$webhook_url")"
bot_meta="$(curl -fsS -H "Authorization: Bot $bot_token" https://discord.com/api/v10/users/@me)"

channel_id="$(node -e 'process.stdout.write(String(JSON.parse(process.argv[1]).channel_id ?? ""))' "$webhook_meta")"
guild_id="$(node -e 'process.stdout.write(String(JSON.parse(process.argv[1]).guild_id ?? ""))' "$webhook_meta")"
application_id="$(node -e 'process.stdout.write(String(JSON.parse(process.argv[1]).id ?? ""))' "$bot_meta")"

if [[ -z "$channel_id" || -z "$guild_id" || -z "$application_id" ]]; then
  printf 'Discord webhook or bot metadata is incomplete.\n' >&2
  exit 1
fi

invite_url="https://discord.com/oauth2/authorize?client_id=${application_id}&permissions=67193856&integration_type=0&scope=bot%20applications.commands"
if ! curl -fsS -H "Authorization: Bot $bot_token" "https://discord.com/api/v10/guilds/$guild_id" >/dev/null; then
  printf 'The bot is not in the webhook server yet. Open this URL, add it, then rerun this script:\n%s\n' "$invite_url" >&2
  exit 1
fi
if ! curl -fsS -H "Authorization: Bot $bot_token" "https://discord.com/api/v10/channels/$channel_id" >/dev/null; then
  printf 'The bot cannot view the selected webhook channel. Grant View Channel permission, then rerun this script.\n' >&2
  exit 1
fi
if ! DISCORD_BOT_TOKEN="$bot_token" DISCORD_CHANNEL_ID="$channel_id" "$node_bin" "$repo_root/scripts/verify-discord-channel.mjs"; then
  printf 'Discord send/history verification failed. Re-add the bot with the required permissions, then rerun setup:\n%s\n' "$invite_url" >&2
  exit 1
fi

bootstrap_code="$(openssl rand -hex 4)"
umask 077
{
  printf 'GITHUB_OWNER=HYUNDAI-APP-Contest\n'
  printf 'GITHUB_REPO=social-drive\n'
  printf 'GITHUB_TOKEN=%s\n' "$github_token"
  printf 'GITHUB_READY_LABEL=agent:ready\n'
  printf 'GITHUB_BASE_BRANCH=main\n'
  printf 'DISCORD_WEBHOOK_URL=%s\n' "$webhook_url"
  printf 'DISCORD_BOT_TOKEN=%s\n' "$bot_token"
  printf 'DISCORD_CHANNEL_ID=%s\n' "$channel_id"
  printf 'DISCORD_GUILD_ID=%s\n' "$guild_id"
  printf 'DISCORD_APPLICATION_ID=%s\n' "$application_id"
  printf 'DISCORD_APPROVER_USER_IDS=\n'
  printf 'DISCORD_BOOTSTRAP_CODE=%s\n' "$bootstrap_code"
  printf 'DISCORD_DISPLAY_NAME=개발자\n'
  printf 'DISCORD_AVATAR_PATH=%s/orchestrator/assets/developer-avatar.png\n' "$repo_root"
  printf 'DISCORD_ARCHITECT_AVATAR_PATH=%s/orchestrator/assets/personas/aki-architect.png\n' "$repo_root"
  printf 'DISCORD_DESIGNER_AVATAR_PATH=%s/orchestrator/assets/personas/pixie-designer.png\n' "$repo_root"
  printf 'DISCORD_REVIEWER_AVATAR_PATH=%s/orchestrator/assets/personas/sentinel-reviewer.png\n' "$repo_root"
  printf 'CODEX_EXECUTABLE=%s\n' "$codex_env_value"
  printf 'CODEX_MODEL=\n'
  printf 'MAX_PARALLEL_AGENTS=3\n'
  printf 'MAX_REVIEW_ROUNDS=2\n'
  printf 'POLL_INTERVAL_SECONDS=30\n'
  printf 'TURN_TIMEOUT_MINUTES=90\n'
  printf 'APP_SERVER_REQUEST_TIMEOUT_SECONDS=20\n'
  printf 'MAX_RETRIES=3\n'
  printf 'USAGE_LIMIT_COOLDOWN_MINUTES=60\n'
  printf 'DAILY_TOKEN_LIMIT=2000000\n'
  printf 'DAILY_COST_LIMIT_USD=25\n'
  printf 'ESTIMATED_USD_PER_1M_TOKENS=10\n'
  printf 'ACCOUNT_USAGE_PAUSE_PERCENT=95\n'
  printf 'ACCOUNT_USAGE_MAX_AGE_SECONDS=300\n'
  printf 'ACCOUNT_USAGE_SYNC_SECONDS=60\n'
  printf 'APPROVAL_TTL_MINUTES=720\n'
  printf 'SOURCE_REPOSITORY=%s\n' "$repo_root"
  printf 'WORKSPACE_ROOT=%s/orchestrator/workspaces\n' "$repo_root"
  printf 'STATE_PATH=%s/orchestrator/data/state.json\n' "$repo_root"
  printf 'DEPLOYMENT_ROOT=%s/orchestrator/deployments\n' "$repo_root"
  printf 'AWS_DEPLOY_ENABLED=true\n'
  printf 'AWS_COMMAND=/usr/local/bin/aws\n'
  printf 'AWS_PROFILE=social-drive-deployer\n'
  printf 'AWS_REGION=ap-northeast-2\n'
  printf 'AWS_STACK_NAME=social-drive-dev\n'
  printf 'AWS_ENVIRONMENT=dev\n'
  printf 'AWS_ARTIFACT_BUCKET=\n'
  printf 'AWS_CLOUDFORMATION_ROLE_ARN=\n'
  printf 'AWS_DEPLOYMENT_TIMEOUT_MINUTES=30\n'
  printf 'LOG_LEVEL=info\n'
} > "$env_file"
chmod 600 "$env_file"

mkdir -p "$logs_dir" "$repo_root/orchestrator/data" "$repo_root/orchestrator/workspaces" "$repo_root/orchestrator/deployments" "$launch_agents_dir"

cat > "$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.socialdrive.codex-orchestrator</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/bin/caffeinate</string><string>-dimsu</string>
    <string>$node_bin</string><string>--env-file=$env_file</string><string>$repo_root/orchestrator/src/index.mjs</string>
  </array>
  <key>WorkingDirectory</key><string>$repo_root</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$logs_dir/orchestrator.log</string>
  <key>StandardErrorPath</key><string>$logs_dir/orchestrator-error.log</string>
</dict>
</plist>
EOF

launchctl bootout "gui/$(id -u)" "$plist" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$plist"
launchctl kickstart -k "gui/$(id -u)/com.socialdrive.codex-orchestrator"

gh label create agent:ready --repo HYUNDAI-APP-Contest/social-drive --color 1D76DB --description "Ready for autonomous Codex implementation" --force

"$node_bin" "--env-file=$env_file" "$repo_root/scripts/send-discord-test.mjs"

printf '\nSetup complete.\n'
printf 'Discord claim code: %s\n' "$bootstrap_code"
printf 'In the configured Discord channel run: /agent-claim code:%s\n' "$bootstrap_code"
printf 'Bot invite URL (only if it is not already in the server):\n'
printf '%s\n' "$invite_url"
printf 'Logs: %s/orchestrator.log\n' "$logs_dir"
printf 'Code changes were not staged, committed, pushed, or merged. Use 개발-관제센터-시작.command for the approval branch and PR flow.\n'

