# Social Drive autonomous Codex orchestrator

This service implements the Symphony control-loop pattern for GitHub Issues, Codex app-server, and Discord approvals.

## Flow

1. Add the `agent:ready` label to a GitHub Issue.
2. The orchestrator starts up to three isolated issue workspaces.
3. Codex edits, tests, and commits inside its workspace with GitHub and Discord secrets removed from its environment.
4. The host pushes the branch and opens a pull request.
5. An authorized Discord user runs `!approve pr-<number>` or `!reject pr-<number>`.
6. Only the host performs the squash merge.

## Local setup

Copy `.env.example` to `.env` and fill the secrets. Use a fine-grained GitHub token limited to this repository with Issues read/write, Pull requests read/write, Contents read/write, and Metadata read. The Discord bot needs View Channel, Read Message History, and Message Content intent; it does not need administrator permissions.

Git operations use a short-lived `GIT_ASKPASS` process environment. The token is never placed in the repository remote URL, command arguments, worker environment, or persistent Git configuration.

Run:

```bash
node --env-file=.env orchestrator/src/index.mjs
```

Local execution is preferred on macOS because it can reuse the existing Codex login. Docker Compose is provided for an always-on Linux host; it mounts Codex authentication read-only and stores runtime state in a named volume.

## Recovery and limits

- State is atomically persisted in `orchestrator/data/state.json`.
- Jobs interrupted during preparation, execution, or publishing become retryable after restart.
- `MAX_RETRIES`, daily token limit, estimated daily USD limit, turn timeout, and approval expiry are hard circuit breakers.
- An unavailable Discord or GitHub approval path never auto-merges.
- Worker subprocesses do not inherit GitHub, Discord, cloud, or generic token/secret variables.

The dollar limit is an estimate because app-server usage and account billing are not guaranteed to expose a per-turn charge. The token limit is the primary deterministic breaker; also set a platform-level project budget.

