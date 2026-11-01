# Social Drive autonomous Codex orchestrator

This service implements the Symphony control-loop pattern for GitHub Issues, Codex app-server, and Discord approvals.

## Flow

1. Add the `agent:ready` label to a GitHub Issue.
2. The orchestrator starts up to three isolated issue workspaces.
3. Codex edits, tests, and commits inside its workspace with GitHub and Discord secrets removed from its environment.
4. The host pushes the branch and opens a pull request.
5. An authorized Discord user clicks the approval or rejection button. Slash commands remain as a fallback.
6. Only the host performs the squash merge.
7. If the merged PR changes `infra/`, the host packages that exact merged commit and creates a CloudFormation change set.
8. AWS resources change only after an authorized user clicks the separate Discord deployment approval button.

## Local setup

Copy `.env.example` to `.env` and fill the secrets. Use a fine-grained GitHub token limited to this repository with Issues read/write, Pull requests read/write, Contents read/write, and Metadata read. The Discord bot needs only View Channel and Use Application Commands. It uses slash commands and does not require the privileged Message Content intent or administrator permissions.

Git operations use a short-lived `GIT_ASKPASS` process environment. The token is never placed in the repository remote URL, command arguments, worker environment, or persistent Git configuration.

Run:

```bash
node --env-file=.env orchestrator/src/index.mjs
```

For first-time setup, run `bash scripts/setup-orchestrator.sh`. It validates the webhook and bot locally, creates `.env` with mode `0600`, and prints a one-time Discord claim code. In the configured channel run `/agent-claim code:<code>` once. The claimed Discord user is persisted in runtime state.

Local execution is preferred on macOS because it can reuse the existing Codex login. Docker Compose is provided for an always-on Linux host; it mounts Codex authentication read-only and stores runtime state in a named volume.

## Recovery and limits

- State is atomically persisted in `orchestrator/data/state.json`.
- Jobs interrupted during preparation, execution, or publishing become retryable after restart.
- `MAX_RETRIES`, daily token limit, estimated daily USD limit, turn timeout, and approval expiry are hard circuit breakers.
- An unavailable Discord or GitHub approval path never auto-merges.
- Worker subprocesses do not inherit GitHub, Discord, cloud, or generic token/secret variables.

The dollar limit is an estimate because app-server usage and account billing are not guaranteed to expose a per-turn charge. The token limit is the primary deterministic breaker; also set a platform-level project budget.

## AWS dev approval gateway

The macOS host uses the `social-drive-deployer` AWS CLI profile. Access keys remain in the local AWS credential store and are never copied into `.env`, issue workspaces, Codex subprocesses, Discord, or GitHub. Worker AWS shared credential and config paths are redirected to `/dev/null`, preventing fallback to the host profile.

Run `/agent-deploy` in the claimed Discord channel to prepare a deployment from the current remote `main`. This packages artifacts and creates a named change set only. Discord shows every logical resource action and warns about removals or replacements. Clicking **승인** executes that exact change set; clicking **거부** deletes it. Completion reports the stack state, output names (never values), and an unauthenticated API smoke-test result.

Infrastructure PRs enter the same deployment flow automatically after their separately approved merge. Docker has the AWS gateway disabled by default because the macOS credential profile is intentionally not mounted into the container.

