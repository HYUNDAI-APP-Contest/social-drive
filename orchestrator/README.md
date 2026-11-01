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

Copy `.env.example` to `.env` and fill the secrets. Use a fine-grained GitHub token limited to this repository with Issues read/write, Pull requests read/write, Contents read/write, and Metadata read. The Discord bot needs View Channel, Send Messages, Read Message History, and Use Application Commands. It uses slash commands and reconciles only its stable event marker; it does not require the privileged Message Content intent or administrator permissions.

Git operations use a short-lived `GIT_ASKPASS` process environment. The token is never placed in the repository remote URL, command arguments, worker environment, or persistent Git configuration.

Run:

```bash
node --env-file=.env orchestrator/src/index.mjs
```

For first-time setup, run `bash scripts/setup-orchestrator.sh`. The invite grants the minimum configured permission integer `67193856`, including Read Message History. Before writing `.env`, setup posts one short bot-owned verification message with mentions disabled, reads that exact returned message ID, and then deletes it best-effort. A failed send, malformed response, or failed exact read stops setup and prints the corrected invite URL; cleanup failure emits only a non-sensitive warning. It then creates `.env` with mode `0600` and prints a one-time Discord claim code. In the configured channel run `/agent-claim code:<code>` once. The claimed Discord user is persisted in runtime state.

Local execution is preferred on macOS because it can reuse the existing Codex login. Docker Compose is provided for an always-on Linux host; it mounts Codex authentication read-only and stores runtime state in a named volume.

After the initial secrets are configured, double-click `개발-관제센터-시작.command` to repair GitHub CLI authentication, add the `project` scope, store that credential in the existing mode-`0600` host `.env`, run tests, create or update the dedicated approval branch and open pull request, verify Discord permissions, reconcile the private Project, restart the LaunchAgent, and send a live report. GitHub CLI's browser login also requires its default `repo`, `read:org`, and `gist` scopes. Existing CLI scopes are preserved rather than silently removed because scope removal could break unrelated user workflows; the script itself never invokes organization administration, billing, repository deletion, or direct-default-branch operations. It never merges the PR. If GitHub or Discord authorization is missing, it opens the provider's authorization page once and waits for the owner to approve it.

## Recovery and limits

- State is atomically persisted in `orchestrator/data/state.json`.
- Jobs interrupted during preparation, execution, or publishing become retryable after restart.
- `MAX_RETRIES`, daily token limit, estimated daily USD limit, turn timeout, and approval expiry are hard circuit breakers.
- An unavailable Discord or GitHub approval path never auto-merges.
- Worker subprocesses do not inherit GitHub, Discord, cloud, or generic token/secret variables.

The dollar limit is an estimate because app-server usage and account billing are not guaranteed to expose a per-turn charge. The token limit is the primary deterministic breaker; also set a platform-level project budget.

## Development control center

The control center keeps GitHub Issues, a private organization Project, and `/agent-status` aligned with the same table-driven status contract. Each Issue has one marked progress comment that is updated in place and two managed labels (`agent:stage:*` and `agent:persona:*`). Existing product labels are never removed.

The current `agent:ready` Issue query is the queue source of truth. If an idle `ready` job disappears from that query, the orchestrator cannot safely infer whether the Issue was closed or the label was removed, so it records `removed_from_queue` / `Cancelled`, updates the comment, managed labels, Project, local dashboard, and `/agent-status`, and does not run it. Active and terminal jobs are never rewritten by this reconciliation. Re-applying `agent:ready` later transitions the cancelled queue record back to `ready`; attempts and audit history remain intact.

External progress writes use a durable outbox in `state.json`. A destination is fixed in code rather than accepted as a URL. Pending and in-flight writes survive restart, use bounded exponential backoff with jitter, and coalesce newer snapshots by Issue and destination. GitHub progress comments are idempotent upserts. Discord transport cannot promise exactly-once delivery: each durable event carries a stable visible event ID, uses Discord's message API nonce enforcement, and searches recent channel messages before a crash retry. This reconciles the accepted-POST-before-state-save window without claiming that a remote service and the local file share one transaction. Reporting outages do not interrupt Codex work, and approval failures remain fail-closed.

Daily token notices are measured against `DAILY_TOKEN_LIMIT`, not presented as work completion. The 30%, 60%, and 90% thresholds are claimed atomically in persisted state. If one usage event crosses more than one threshold, Discord receives one combined event. Restart and concurrent agents do not re-claim a threshold; Discord's event ID and nonce reconciliation suppress a visible duplicate if the process dies after Discord accepts the message but before local delivery state is saved.

Discord outcome reports are separate from approvals. They are emitted only for a locked plan, an implementation result, a new independent-review blocker, and a PR-ready three-role result. Each mobile card identifies 아키, 픽시, 센티넬, lists at most three changes, and keeps `PASS`, `FAIL`, `NOT_RUN`, and `BLOCKED` distinct. Repeated delivery uses a stable nonce and visible outcome ID through the durable outbox. A reporting outage never fails a coding job or bypasses an approval.

Outcome reports are text/embed cards only. Automatic screenshot attachment stays disabled until the host can verify the actual pixels, remove metadata, and pin an immutable content hash. A safe same-repository PR link and structured test summary are used instead. Desktop, terminal, IDE, log, credential, real-user, precise-location, and QR captures are forbidden. Approval buttons remain in their own message.

Set up the Project schema without making a network request:

```bash
npm --prefix orchestrator run control-center:dry-run
```

Then use a token with organization Projects and repository Issues administration permission:

```bash
node --env-file=.env scripts/setup-control-center.mjs --check
node --env-file=.env scripts/setup-control-center.mjs --apply
node --env-file=.env scripts/setup-control-center.mjs --apply --write-env
```

`--check` is read-only and exits with status 2 on drift. `--apply` creates or reconciles the private Project fields, Status options, five saved views (`Command Center`, `Now`, `Needs attention`, `PR & Release`, and `History`), and managed labels, then prints the non-secret `GITHUB_PROJECT_ID` and `GITHUB_PROJECT_URL` values to copy into `.env`. `--apply --write-env` writes only those two non-secret values atomically and keeps `.env` mode `0600`, so the one-click activation does not require copying values. It never prints or rewrites the token. Runtime field synchronization begins only when `GITHUB_PROJECT_ID` is configured.

`/agent-status` is ephemeral and attention-first: expiring approvals, blocked work, recovery, active work, and queued work. It shows at most five jobs, daily deterministic token usage, explicitly estimated cost, AWS state, and the Project link. Unknown data is shown as unavailable rather than as zero.

Control-center messages never include workspace paths, raw exceptions, account identifiers, or credentials. Routine transitions remain in the Issue comment, Project, and local state instead of spamming Discord. The main channel receives outcome reports only when the plan is locked, an implementation result exists, an independent review finds a new blocker, or the three-role gate reaches PR-ready; token milestones and visible recovery/completion events remain separate. Outcome reports are information-only mobile cards without automatic image attachments. Merge and deployment decisions always use a different 🔐 approval card.

### Local dashboard

The same long-running orchestrator process atomically regenerates `status-dashboard.html` every 10 seconds and immediately after job or usage transitions. Open that file directly in Finder or a browser. The page reload interval follows `DASHBOARD_INTERVAL_SECONDS`; if its generated heartbeat is older than three write intervals (never less than 30 seconds), it displays a fail-closed red warning that the visible data is only the last snapshot. It does not start localhost, bind a port, or make browser network requests. The generated file is ignored by Git and contains only allowlisted job summaries: Issue, phase, persona, health, token counts, branch/PR metadata, and whether a workspace is prepared. Absolute workspace paths, raw errors, agent transcripts, and credentials are excluded.

The HTML template lives in `src/dashboard.mjs` and uses escaped inline JSON plus `textContent` for all runtime values. A restrictive CSP disables network connections. Set `DASHBOARD_PATH` or `DASHBOARD_INTERVAL_SECONDS` only if the default root file and 10-second refresh are unsuitable. Because generation runs inside the existing orchestrator, the existing LaunchAgent restart behavior also restores dashboard updates after reboot.

## AWS dev approval gateway

The macOS host uses the `social-drive-deployer` AWS CLI profile. Access keys remain in the local AWS credential store and are never copied into `.env`, issue workspaces, Codex subprocesses, Discord, or GitHub. Worker AWS shared credential and config paths are redirected to `/dev/null`, preventing fallback to the host profile.

Run `/agent-deploy` in the claimed Discord channel to prepare a deployment from the current remote `main`. This packages artifacts and creates a named change set only. Discord shows every logical resource action and warns about removals or replacements. Clicking **승인** executes that exact change set; clicking **거부** deletes it. Completion reports the stack state, output names (never values), and an unauthenticated API smoke-test result.

Infrastructure PRs enter the same deployment flow automatically after their separately approved merge. Docker has the AWS gateway disabled by default because the macOS credential profile is intentionally not mounted into the container.

